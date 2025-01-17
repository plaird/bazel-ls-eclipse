/**
 * Copyright (c) 2020, Salesforce.com, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.salesforce.b2eclipse.config;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.google.common.collect.ImmutableList;
import com.salesforce.b2eclipse.BazelJdtPlugin;
import com.salesforce.b2eclipse.BazelNature;
import com.salesforce.b2eclipse.abstractions.WorkProgressMonitor;
import com.salesforce.b2eclipse.builder.BazelBuilder;
import com.salesforce.b2eclipse.classpath.BazelClasspathContainer;
import com.salesforce.b2eclipse.classpath.BazelClasspathContainerInitializer;
import com.salesforce.b2eclipse.command.BazelCommandManager;
import com.salesforce.b2eclipse.command.BazelWorkspaceCommandRunner;
import com.salesforce.b2eclipse.managers.B2EPreferncesManager;
import com.salesforce.b2eclipse.model.AspectPackageInfo;
import com.salesforce.b2eclipse.model.AspectPackageInfos;
import com.salesforce.b2eclipse.model.BazelLabel;
import com.salesforce.b2eclipse.runtime.api.ResourceHelper;
import com.salesforce.bazel.sdk.model.BazelPackageInfo;
import com.salesforce.bazel.sdk.util.BazelConstants;

/**
 * A factory class to create Eclipse projects from packages in a Bazel workspace.
 * <p>
 * TODO add test coverage.
 */
public final class BazelEclipseProjectFactory {

    // TODO do an analysis of the workspace to determine the correct JDK to bind
    // into the bazel project
    public static final String STANDARD_VM_CONTAINER_PREFIX = "org.eclipse.jdt.launching.JRE_CONTAINER";

    /**
     * The Java version to assign to the classpath of each created Java Eclipse project.
     */
    private static final int JAVA_LANG_VERSION = 8;

    /**
     * Alternate code path that we no longer use, but retained for possible future use.
     */
    private static final boolean PRECOMPUTE_ALL_ASPECTS_FOR_WORKSPACE = true;

    /**
     * The default path is always divided using “/”.
     */
    private static final String SPLITTER_FOR_SOURCE_DIRECTORY_PATH = "/";

    // signals that we are in a delicate bootstrapping operation
    private static AtomicBoolean importInProgress = new AtomicBoolean(false);

    // add the directory name to the label, if it is meaningful (>3 chars)
    private static final int MIN_NUMBER_OF_CHARACTER_FOR_NAME = 3;

    private static final String[] BUILD_FILE_NAMES = ArrayUtils.addAll(BazelConstants.BUILD_FILE_NAMES.toArray(new String[0]),
        BazelConstants.WORKSPACE_FILE_NAMES.toArray(new String[0]));

    private BazelEclipseProjectFactory() {

    }

    /**
     * Imports a workspace. This version does not yet allow the user to be selective - it imports all Java packages that
     * it finds in the workspace.
     *
     * @return the list of Eclipse IProject objects created during import; by contract the first element in the list is
     *         the IProject object created for the 'bazel workspace' project node which is a special container project
     */
    public static List<IProject> importWorkspace(BazelPackageInfo bazelWorkspaceRootPackageInfo,
            List<BazelPackageInfo> selectedBazelPackages, WorkProgressMonitor progressMonitor,
            IProgressMonitor monitor) {
        URI eclipseProjectLocation = null;
        String bazelWorkspaceRoot = bazelWorkspaceRootPackageInfo.getWorkspaceRootDirectory().getAbsolutePath();
        File bazelWorkspaceRootDirectory = new File(bazelWorkspaceRoot);

        SubMonitor subMonitor = SubMonitor.convert(monitor, selectedBazelPackages.size());
        subMonitor.setTaskName("Getting the Aspect Information for targets");
        subMonitor.split(1);

        // Many collaborators need the Bazel workspace directory location, so we stash it in an accessible global location
        // currently we only support one Bazel workspace in an Eclipse workspace
        BazelJdtPlugin.setBazelWorkspaceRootDirectory(bazelWorkspaceRootDirectory);

        // Set the flag that an import is in progress
        importInProgress.set(true);

        // clear out state flag in the Bazel classpath initializer in case there was a previous failed import run
        BazelClasspathContainerInitializer.getIsCorrupt().set(false);

        String eclipseProjectNameForBazelWorkspace = BazelNature.BAZELWORKSPACE_PROJECT_BASENAME;
        // TODO pull the workspace name out of the WORKSPACE file, until then use the directory name (e.g. bazel-demo)
        int lastSlash = bazelWorkspaceRoot.lastIndexOf(File.separator);
        if (lastSlash >= 0 && (bazelWorkspaceRoot.length() - lastSlash) > MIN_NUMBER_OF_CHARACTER_FOR_NAME) {
            eclipseProjectNameForBazelWorkspace = BazelNature.BAZELWORKSPACE_PROJECT_BASENAME + " ("
                    + bazelWorkspaceRoot.substring(lastSlash + 1) + ")";
        }

        // TODO send this message to the EclipseConsole so the user actually sees it
        BazelJdtPlugin.logInfo("Starting import of [" + eclipseProjectNameForBazelWorkspace
                + "]. This may take some time, please be patient.");

        // create the Eclipse project for the Bazel workspace (directory that contains the WORKSPACE file)
        IProject rootEclipseProject = BazelEclipseProjectFactory.createEclipseProjectForBazelPackage(
            eclipseProjectNameForBazelWorkspace, eclipseProjectLocation, bazelWorkspaceRoot, "",
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), JAVA_LANG_VERSION);
        if (rootEclipseProject == null) {
            throw new RuntimeException(
                    "Could not create the root workspace project. Look back in the log for more details.");
        }
        List<IProject> importedProjectsList = new ArrayList<>();
        importedProjectsList.add(rootEclipseProject);

        // see the method level comment about this option (currently disabled)
        AspectPackageInfos aspects;

        if (PRECOMPUTE_ALL_ASPECTS_FOR_WORKSPACE) {
            aspects = precomputeBazelAspectsForWorkspace(rootEclipseProject, selectedBazelPackages, progressMonitor);
        }

        Iterable<BazelPackageInfo> postOrderedModules = ImportOrderResolver
                .resolveModulesImportOrder(bazelWorkspaceRootPackageInfo, selectedBazelPackages, aspects);

        // finally, create an Eclipse Project for each Bazel Package being imported
        subMonitor.setTaskName("Importing bazel packages: ");
        for (BazelPackageInfo childPackageInfo : postOrderedModules) {
            subMonitor.subTask("Importing " + childPackageInfo.getBazelPackageName());
            if (childPackageInfo.isWorkspaceRoot()) {
                // the workspace root node has been already created (above)
                continue;
            }
            List<String> generatedSources =
                    aspects.lookByPackageName(childPackageInfo.getBazelPackageName()).getSources().stream()
                            .filter(fileName -> !fileName.startsWith(childPackageInfo.getBazelPackageNameLastSegment()))
                            .collect(Collectors.toList());

            importBazelWorkspacePackagesAsProjects(childPackageInfo, bazelWorkspaceRoot, importedProjectsList,
                generatedSources);
            subMonitor.split(1);
        }

        subMonitor.done();
        // reset flag that indicates we are doing import
        importInProgress.set(false);

        return importedProjectsList;
    }

    private static void importBazelWorkspacePackagesAsProjects(BazelPackageInfo packageInfo, String bazelWorkspaceRoot,
            List<IProject> importedProjectsList, List<String> generatedSources) {
        List<String> packageSourceCodeFSPaths = new ArrayList<>();
        List<String> packageBazelTargets = new ArrayList<>();
        BazelEclipseProjectFactory.computePackageSourceCodePaths(packageInfo, packageSourceCodeFSPaths,
            packageBazelTargets);

        String eclipseProjectNameForBazelPackage = packageInfo.getBazelPackageNameLastSegment();
        URI eclipseProjectLocation = null; // let Eclipse use the default location
        IProject eclipseProject = createEclipseProjectForBazelPackage(eclipseProjectNameForBazelPackage,
            eclipseProjectLocation, bazelWorkspaceRoot, packageInfo.getBazelPackageFSRelativePath(),
            packageSourceCodeFSPaths, generatedSources, packageBazelTargets, JAVA_LANG_VERSION);

        if (eclipseProject != null) {
            importedProjectsList.add(eclipseProject);
        }

        //        for (BazelPackageInfo childPackageInfo : packageInfo.getChildPackageInfos()) {
        //            importBazelWorkspacePackagesAsProjects(childPackageInfo, bazelWorkspaceRoot, importedProjectsList);
        //        }
    }

    /**
     * Create a Bazel Eclipse project. This method adds the natures to the project, saves the list of targets and the
     * workspace root to the project settings, make Bazel the default builder instead of ECJ and create the classpath
     * using ide build informations from Bazel.
     *
     * @param projectName
     *            the name to use for the Eclipse project, will be the label for the project in the Eclipse Project
     *            Explorer
     * @param eclipseProjectLocation
     *            typically null (let Eclipse set the location) or some other path
     * @param bazelWorkspaceRoot
     *            the absolute file system path to the Bazel workspace being imported
     * @param packageFSPath
     *            the relative path from the Bazel workspace root to the Bazel package this project belongs to
     * @param packageSourceCodeFSPaths
     *            the relative paths from the Bazel workspace root to each source directory in this project
     * @param bazelTargets
     *            the list of Bazel targets for a build/test of this project
     * @param javaLanguageVersion
     *            the Java version to use in the classpath
     */
    public static IProject createEclipseProjectForBazelPackage(String projectName, URI eclipseProjectLocation,
            String bazelWorkspaceRoot, String packageFSPath, List<String> packageSourceCodeFSPaths,
            List<String> generatedSources, List<String> bazelTargets, int javaLanguageVersion) {

        IProject eclipseProject = createBaseEclipseProject(projectName, eclipseProjectLocation, bazelWorkspaceRoot);
        try {
            addNatureToEclipseProject(eclipseProject, BazelNature.BAZEL_NATURE_ID);
            addNatureToEclipseProject(eclipseProject, JavaCore.NATURE_ID);
            addSettingsToEclipseProject(eclipseProject, bazelWorkspaceRoot, bazelTargets, ImmutableList.of()); // TODO
                                                                                                               // pass
                                                                                                               // buildFlags

            // this may throw if the user has deleted the .project file on disk while the
            // project is open for import
            // but it will try to recover so we should catch now instead of allowing the
            // entire flow to fail.
            // "The project description file (.project) for 'Bazel Workspace (simplejava)'
            // was missing. This file contains important information about the project.
            // A new project description file has been created, but some information about
            // the project may have been lost."
            setBuildersOnEclipseProject(eclipseProject);
        } catch (CoreException e) {
            BazelJdtPlugin.logException(e.getMessage(), e);
        } catch (BackingStoreException e) {
            BazelJdtPlugin.logException(e.getMessage(), e);
        }

        try {
            // TODO this seems to be the right place to configure the project for Search to
            // avoid such directories as .git and bazel-*

            // TODO investigate using the configure() hook in the BazelNature for the
            // configuration part of this createProject() method
            IJavaProject eclipseJavaProject =
                    BazelJdtPlugin.getJavaCoreHelper().getJavaProjectForProject(eclipseProject);
            createBazelClasspathForEclipseProject(new Path(bazelWorkspaceRoot), packageFSPath, packageSourceCodeFSPaths,
                generatedSources, eclipseJavaProject, javaLanguageVersion);

            // lets link to (== include in the project) some well known files
            linkFiles(bazelWorkspaceRoot, packageFSPath, eclipseProject, BUILD_FILE_NAMES);
        } catch (CoreException e) {
            BazelJdtPlugin.logException(e.getMessage(), e);
            eclipseProject = null;
        }

        return eclipseProject;
    }

    private static void addSettingsToEclipseProject(IProject eclipseProject, String bazelWorkspaceRoot,
            List<String> bazelTargets, List<String> bazelBuildFlags) throws BackingStoreException {

        Preferences eclipseProjectBazelPrefs =
                BazelJdtPlugin.getResourceHelper().getProjectBazelPreferences(eclipseProject);

        int i = 0;
        for (String bazelTarget : bazelTargets) {
            eclipseProjectBazelPrefs.put(BazelEclipseProjectSupport.TARGET_PROPERTY_PREFIX + i, bazelTarget);
            i++;
        }
        eclipseProjectBazelPrefs.put(BazelEclipseProjectSupport.WORKSPACE_ROOT_PROPERTY, bazelWorkspaceRoot);
        i = 0;
        for (String bazelBuildFlag : bazelBuildFlags) {
            eclipseProjectBazelPrefs.put(BazelEclipseProjectSupport.BUILDFLAG_PROPERTY_PREFIX + i, bazelBuildFlag);
            i++;
        }
        eclipseProjectBazelPrefs.flush();
    }

    private static void setBuildersOnEclipseProject(IProject eclipseProject) throws CoreException {
        IProjectDescription eclipseProjectDescription = eclipseProject.getDescription();
        final ICommand buildCommand = eclipseProjectDescription.newCommand();
        buildCommand.setBuilderName(BazelBuilder.BUILDER_NAME);
        eclipseProjectDescription.setBuildSpec(new ICommand[] {buildCommand});
        eclipseProject.setDescription(eclipseProjectDescription, null);
    }

    private static void linkFiles(String bazelWorkspaceRoot, String packageFSPath, IProject eclipseProject,
            String... fileNames) {
        ResourceHelper resourceHelper = BazelJdtPlugin.getResourceHelper();

        for (String fileName : fileNames) {
            File f = new File(new File(bazelWorkspaceRoot, packageFSPath), fileName);
            IFile projectFile = resourceHelper.getProjectFile(eclipseProject, fileName);
            if (f.isFile() && !projectFile.exists()) {
                try {
                    resourceHelper.createFileLink(projectFile, Path.fromOSString(f.getAbsolutePath()),
                        IResource.REPLACE, null);
                } catch (Exception anyE) {
                    // TODO throwing this exception just writes a log message, we need a modal error
                    // popup for this error
                    BazelJdtPlugin.logError("Failure to link file [" + f.getAbsolutePath() + "] for project ["
                            + eclipseProject.getName() + "]");
                    throw anyE;
                }
            } else {
                BazelJdtPlugin.logInfo("Tried to link a non-existant file [" + f.getAbsolutePath() + "] for project ["
                        + eclipseProject.getName() + "]");
            }
        }
    }

    // bazelPackageFSPath: the relative path from the Bazel WORKSPACE root, to the
    // Bazel Package being processed
    // packageSourceCodeFSRelativePaths: the relative paths from the WORKSPACE root
    // to the Java Source directories
    // where the Java Package structure starts
    private static void createBazelClasspathForEclipseProject(IPath bazelWorkspacePath, String bazelPackageFSPath,
            List<String> packageSourceCodeFSRelativePaths, List<String> generatedSources, IJavaProject eclipseProject,
            int javaLanguageLevel) throws CoreException {
        List<IClasspathEntry> classpathEntries = new LinkedList<>();
        ResourceHelper resourceHelper = BazelJdtPlugin.getResourceHelper();

        for (String path : packageSourceCodeFSRelativePaths) {
            IPath realSourceDir = Path.fromOSString(bazelWorkspacePath + File.separator + path);
            IFolder projectSourceFolder =
                    createFoldersForRelativePackagePath(eclipseProject.getProject(), bazelPackageFSPath, path, false);
            try {
                resourceHelper.createFolderLink(projectSourceFolder, realSourceDir, IResource.NONE, null);
            } catch (IllegalArgumentException e) {
                BazelJdtPlugin.logInfo("Folder link " + projectSourceFolder + " already exists");
            }
            IPath outputDir = null; // null is a legal value, it means use the default
            boolean isTestSource = false;
            if (path.endsWith("src/test/java")) { // NON_CONFORMING PROJECT SUPPORT
                isTestSource = true;
                outputDir = new Path(eclipseProject.getPath().toOSString() + "/testbin");
            }

            IPath sourceDir = projectSourceFolder.getFullPath();
            IClasspathEntry sourceClasspathEntry =
                    BazelJdtPlugin.getJavaCoreHelper().newSourceEntry(sourceDir, outputDir, isTestSource);
            classpathEntries.add(sourceClasspathEntry);
        }

        for (String path : generatedSources) {
            IPath generatedSourceDir = Path.fromOSString(bazelWorkspacePath + File.separator + path);
            if (generatedSourceDir.toFile().exists() && generatedSourceDir.toFile().isDirectory()) {
                IFolder projectSourceFolder = createFoldersForRelativePackagePath(eclipseProject.getProject(),
                    bazelPackageFSPath, path, true);
                try {
                    resourceHelper.createFolderLink(projectSourceFolder, generatedSourceDir, IResource.NONE, null);
                } catch (IllegalArgumentException e) {
                    BazelJdtPlugin.logInfo("Folder link " + projectSourceFolder + " already exists");
                }

                IClasspathEntry sourceClasspathEntry = BazelJdtPlugin.getJavaCoreHelper()
                        .newSourceEntry(projectSourceFolder.getFullPath(), null, false);
                classpathEntries.add(sourceClasspathEntry);
            }
        }

        IClasspathEntry bazelClasspathContainerEntry =
                BazelJdtPlugin.getJavaCoreHelper().newContainerEntry(new Path(BazelClasspathContainer.CONTAINER_NAME));
        classpathEntries.add(bazelClasspathContainerEntry);

        // add in a JDK to the classpath
        classpathEntries.add(BazelJdtPlugin.getJavaCoreHelper()
                .newContainerEntry(new Path(STANDARD_VM_CONTAINER_PREFIX)));

        IClasspathEntry[] newClasspath = classpathEntries.toArray(new IClasspathEntry[classpathEntries.size()]);
        eclipseProject.setRawClasspath(newClasspath, null);
    }

    private static IFolder createFoldersForRelativePackagePath(IProject project, String bazelPackageFSPath,
            String packageSourceCodeFSRelativePath, Boolean generatedSources) {
        // figure out the src folder path under the Bazel Package, typically
        // src/[main|test]/java, but we don't have to
        // assume/hardcode that here

        String sourceDirectoryPath;

        if (!generatedSources) {
            if (!packageSourceCodeFSRelativePath.startsWith(bazelPackageFSPath)) {
                throw new IllegalStateException("src code path expected to be under bazel package path");
            }
            if (Paths.get(packageSourceCodeFSRelativePath).equals(Paths.get(bazelPackageFSPath))) {
                throw new IllegalStateException("did not expect src code path to be equals to the bazel package path");
            }
            sourceDirectoryPath = packageSourceCodeFSRelativePath.substring(bazelPackageFSPath.length() + 1);
            // +1
            // for
            // '/'
        } else {
            sourceDirectoryPath = packageSourceCodeFSRelativePath;
        }

        String[] pathComponents = sourceDirectoryPath.split(SPLITTER_FOR_SOURCE_DIRECTORY_PATH);
        IFolder currentFolder = null;
        for (int i = 0; i < pathComponents.length; i++) {
            String pathComponent = pathComponents[i];
            if (currentFolder == null) {
                currentFolder = project.getFolder(pathComponent);
            } else {
                currentFolder = currentFolder.getFolder(pathComponent);
            }
            if (i < (pathComponents.length - 1)) {
                // don't create the last folder - that happens as part of "linking" it
                create(currentFolder);
            }
        }
        return currentFolder;
    }

    private static void create(IFolder folder) {
        if (folder.exists()) {
            return;
        }
        try {
            folder.create(false, true, null);
        } catch (CoreException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static IProject createBaseEclipseProject(String eclipseProjectName, URI location,
            String bazelWorkspaceRoot) {
        ResourceHelper resourceHelper = BazelJdtPlugin.getResourceHelper();
        IProgressMonitor progressMonitor = null;

        // Request the project by name, which will create a new shell IProject instance
        // if the project doesn't already exist.
        // For this case, we expect it not to exist, but there may be a problem here if
        // there are multiple Bazel packages
        // with the same name in different parts of the Bazel workspace (we don't
        // support that yet).
        IProject newEclipseProject = resourceHelper.getProjectByName(eclipseProjectName);
        IProject createdEclipseProject = null;

        if (!newEclipseProject.exists()) {
            URI eclipseProjectLocation = location;
            IWorkspaceRoot workspaceRoot = resourceHelper.getEclipseWorkspaceRoot();

            // create the project description, which is initialized to:
            // 1. the given project name 2. no references to other projects 3. an empty
            // build spec 4. an empty comment
            // to which we add the location uri
            IProjectDescription eclipseProjectDescription = resourceHelper.createProjectDescription(newEclipseProject);
            if (location != null && workspaceRoot.getLocationURI().equals(location)) {
                eclipseProjectLocation = null;
            }
            eclipseProjectDescription.setLocationURI(eclipseProjectLocation);

            try {
                createdEclipseProject =
                        resourceHelper.createProject(newEclipseProject, eclipseProjectDescription, progressMonitor);
                if (!createdEclipseProject.isOpen()) {
                    resourceHelper.openProject(createdEclipseProject, progressMonitor);
                }
            } catch (CoreException e) {
                BazelJdtPlugin.logException(e.getMessage(), e);
                createdEclipseProject = null;
            }
        } else {
            BazelJdtPlugin.logError("Project [" + eclipseProjectName
                    + "] already exists, which is unexpected. Project initialization will not occur.");
            createdEclipseProject = newEclipseProject;
        }

        return createdEclipseProject;
    }

    // TODO this code also exists in BazelProjectConfigurator, dedupe
    static void addNatureToEclipseProject(IProject eclipseProject, String nature) throws CoreException {
        if (!eclipseProject.hasNature(nature)) {
            ResourceHelper resourceHelper = BazelJdtPlugin.getResourceHelper();

            IProjectDescription eclipseProjectDescription = resourceHelper.getProjectDescription(eclipseProject);
            String[] prevNatures = eclipseProjectDescription.getNatureIds();
            String[] newNatures = new String[prevNatures.length + 1];
            System.arraycopy(prevNatures, 0, newNatures, 0, prevNatures.length);
            newNatures[prevNatures.length] = nature;
            eclipseProjectDescription.setNatureIds(newNatures);

            resourceHelper.setProjectDescription(eclipseProject, eclipseProjectDescription);
        }
    }

    /**
     * This method populates the following arguments, based on the value of the specified packageNode:
     *
     * packageSourceCodeFSPaths: the relative paths, from the root of the WORKSPACE, to the start of the Java Package
     * structure directories For example, if a Java Class, com.bit.coin.Main, lives at
     * //projects/libs/bitcoin/src/main/java/com/bit/coin/Main.java, the path projects/libs/bitcoin/src/main/java is
     * added to this list
     *
     * bazelTargets: the list of Bazel labels to associate with the given packageNode
     */
    private static void computePackageSourceCodePaths(BazelPackageInfo packageNode,
            List<String> packageSourceCodeFSPaths, List<String> bazelTargets) {
        boolean foundSourceCodePaths = false;

        // add this node buildable target
        String bazelPackageRootDirectory = packageNode.getWorkspaceRootDirectory().getAbsolutePath();

        B2EPreferncesManager preferencesManager = B2EPreferncesManager.getInstance();

        String mainSrcRelPath =
                packageNode.getBazelPackageFSRelativePath() + preferencesManager.getImportBazelSrcPath();
        File mainSrcDir = new File(bazelPackageRootDirectory + File.separator + mainSrcRelPath);
        if (mainSrcDir.exists()) {
            packageSourceCodeFSPaths.add(mainSrcRelPath);
            foundSourceCodePaths = true;
        }
        String testSrcRelPath =
                packageNode.getBazelPackageFSRelativePath() + preferencesManager.getImportBazelTestPath();
        File testSrcDir = new File(bazelPackageRootDirectory + File.separator + testSrcRelPath);
        if (testSrcDir.exists()) {
            packageSourceCodeFSPaths.add(testSrcRelPath);
            foundSourceCodePaths = true;
        }

        if (foundSourceCodePaths) {
            BazelLabel packageTarget = new BazelLabel(packageNode.getBazelPackageName());
            if (packageTarget.isPackageDefault()) {
                // if the label is //foo, we want foo:* so that we pick up all targets in the
                // BUILD file, instead of only the default package target
                packageTarget = packageTarget.toPackageWildcardLabel();
            }
            bazelTargets.add(packageTarget.getLabel());
        } else {
            throw new IllegalStateException(
                    "Couldn't find sources for the following package: " + packageNode.getBazelPackageName());
        }
    }

    /**
     * Computes the aspects for all selected Bazel packages in the Bazel workspace during import. We were doing this at
     * an early point in the Bazel Eclipse Feature history, but this no longer is necessary. Retaining the code just in
     * case we find it useful again.
     */
    private static AspectPackageInfos precomputeBazelAspectsForWorkspace(IProject rootEclipseProject,
            List<BazelPackageInfo> selectedBazelPackages, WorkProgressMonitor progressMonitor) {
        BazelCommandManager bazelCommandManager = BazelJdtPlugin.getBazelCommandManager();
        BazelWorkspaceCommandRunner bazelWorkspaceCmdRunner =
                bazelCommandManager.getWorkspaceCommandRunner(BazelJdtPlugin.getBazelWorkspaceRootDirectory());

        // figure out which Bazel targets will be imported, and generated
        // AspectPackageInfos for each
        // The AspectPackageInfos have useful information that we use during import
        List<String> packageBazelTargets = new ArrayList<>();
        for (BazelPackageInfo childPackageInfo : selectedBazelPackages) {
            BazelEclipseProjectFactory.computePackageSourceCodePaths(childPackageInfo, new ArrayList<>(),
                packageBazelTargets);
        }

        // run the aspect for specified targets and get an AspectPackageInfo for each
        AspectPackageInfos aspectPackageInfos = null;
        try {
            Map<String, AspectPackageInfo> packageInfos = bazelWorkspaceCmdRunner.getAspectPackageInfos(
                rootEclipseProject.getName(), packageBazelTargets, progressMonitor, "importWorkspace");
            aspectPackageInfos = new AspectPackageInfos(packageInfos.values());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return aspectPackageInfos;
    }

    public static AtomicBoolean getImportInProgress() {
        return importInProgress;
    }

}
