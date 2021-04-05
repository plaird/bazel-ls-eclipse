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
 */
package com.salesforce.b2eclipse.command.internal;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.salesforce.b2eclipse.BazelJdtPlugin;
import com.salesforce.b2eclipse.abstractions.BazelAspectLocation;
import com.salesforce.b2eclipse.abstractions.WorkProgressMonitor;
import com.salesforce.b2eclipse.command.BazelCommandLineToolConfigurationException;
import com.salesforce.b2eclipse.command.BazelWorkspaceCommandRunner;
import com.salesforce.b2eclipse.internal.TimeTracker;
import com.salesforce.b2eclipse.model.AspectPackageInfo;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

/**
 * Manages running, collecting, and caching all of the build info aspects for a specific workspace.
 */
public class BazelWorkspaceAspectHelper {
    private final BazelWorkspaceCommandRunner bazelWorkspaceCommandRunner;
    private final BazelCommandExecutor bazelCommandExecutor;

    /**
     * These arguments are added to all "bazel build" commands that run for aspect processing
     */
    private List<String> aspectOptions;

    private boolean befVersion;

    /**
     * Cache of the Aspect data for each target. key=String target (//a/b/c) value=AspectPackageInfo data that came from
     * running the aspect. This cache is cleared often (currently, every build, but that is too often)
     */
    @VisibleForTesting
    final Map<String, AspectPackageInfo> aspectInfoCacheCurrent = new HashMap<>();

    /**
     * For wildcard targets //a/b/c:* we need to capture the resulting aspects that come from evaluation so that the
     * underlying list of aspects can be rebuilt from cache
     */
    @VisibleForTesting
    final Map<String, Set<String>> aspectInfoCacheWildcards = new HashMap<>();

    /**
     * Cache of the Aspect data for each target. key=String target (//a/b/c) value=AspectPackageInfo data that came from
     * running the aspect. This cache is never cleared and is used for cases in which the developer introduces a compile
     * error into the package, such that the Aspect will fail to run.
     */
    @VisibleForTesting
    final Map<String, AspectPackageInfo> aspectInfoCacheLastgood = new HashMap<>();

    /**
     * Tracks the number of cache hits for getAspectPackageInfos() invocations.
     */
    @VisibleForTesting
    int numberCacheHits = 0;

    // CTORS

    public BazelWorkspaceAspectHelper(BazelWorkspaceCommandRunner bazelWorkspaceCommandRunner,
            BazelAspectLocation aspectLocation, BazelCommandExecutor bazelCommandExecutor) {
        this.bazelWorkspaceCommandRunner = bazelWorkspaceCommandRunner;
        this.bazelCommandExecutor = bazelCommandExecutor;
        String aspectVersion = System.getProperty("aspectVersion");
        this.befVersion = "bef".equalsIgnoreCase(StringUtils.trimToNull(aspectVersion));
        if (this.befVersion) {
            buildBefAspect(aspectLocation);
        } else {
            buildIntellijAspect(aspectLocation);
        }
    }

    private void buildIntellijAspect(BazelAspectLocation aspectLocation) {
        this.aspectOptions = ImmutableList.<String>builder().add("--nobuild_event_binary_file_path_conversion") //
                //                .add("--curses=no") //
                //                .add("--color=yes") //
                //                .add("--progress_in_terminal_title=no") //
                .add("--noexperimental_run_validations") //
                .add("--aspects=@intellij_aspect//:intellij_info_bundled.bzl%intellij_info_aspect") //
                .add("--override_repository=intellij_aspect=" + aspectLocation.getAspectDirectory()) //
                .add(
                    "--output_groups=intellij-info-generic,intellij-info-java-direct-deps,intellij-resolve-java-direct-deps") //
                .add("--experimental_show_artifacts")//
                .build();
    }

    private void buildBefAspect(BazelAspectLocation aspectLocation) {
        this.aspectOptions = ImmutableList.<String>builder()
                .add("--override_repository=local_eclipse_aspect=" + aspectLocation.getAspectDirectory(),
                    "--aspects=@local_eclipse_aspect" + aspectLocation.getAspectLabel(), "-k",
                    "--output_groups=json-files,classpath-jars,-_,-defaults", "--experimental_show_artifacts")
                .build();
    }

    /**
     * Runs the analysis of the given list of targets using the build information Bazel Aspect and returns a map of
     * {@link AspectPackageInfo}-s (key is the label of the target) containing the parsed form of the JSON file created
     * by the aspect.
     * <p>
     * This method caches its results and won't recompute a previously computed version unless
     * {@link #flushAspectInfoCache()} has been called in between.
     * <p>
     * TODO it would be worthwhile to evaluate whether Aspects are the best way to get build info, as we could otherwise
     * use Bazel Query here as well.
     *
     * @throws BazelCommandLineToolConfigurationException
     */
    public synchronized Map<String, AspectPackageInfo> getAspectPackageInfos(String eclipseProjectName,
            Collection<String> targets, WorkProgressMonitor progressMonitor, String caller)
            throws IOException, InterruptedException, BazelCommandLineToolConfigurationException {

        progressMonitor.subTask("Load Bazel dependency information");
        Map<String, AspectPackageInfo> resultMap = new LinkedHashMap<>();

        for (String target : targets) {
            // is this a wilcard target? we have to handle that differently
            if (target.endsWith("*")) {
                Set<String> wildcardTargets = aspectInfoCacheWildcards.get(target);
                if (wildcardTargets != null) {
                    // we know what sub-targets resolve from the wildcard target, so add each sub-target aspect
                    for (String wildcardTarget : wildcardTargets) {
                        getAspectPackageInfoForTarget(wildcardTarget, eclipseProjectName, progressMonitor, caller,
                            resultMap);
                    }
                } else {
                    // we haven't seen this wildcard before, we need to ask bazel what sub-targets it maps to
                    Map<String, AspectPackageInfo> wildcardResultMap = new LinkedHashMap<>();
                    getAspectPackageInfoForTarget(target, eclipseProjectName, progressMonitor, caller,
                        wildcardResultMap);
                    resultMap.putAll(wildcardResultMap);
                    aspectInfoCacheWildcards.put(target, wildcardResultMap.keySet());
                }
            } else {
                getAspectPackageInfoForTarget(target, eclipseProjectName, progressMonitor, caller, resultMap);
            }
        }

        progressMonitor.worked(resultMap.size());

        return resultMap;
    }

    /**
     * Clear the entire AspectPackageInfo cache. This flushes the dependency graph for the workspace.
     */
    public synchronized void flushAspectInfoCache() {
        this.aspectInfoCacheCurrent.clear();
        this.aspectInfoCacheWildcards.clear();
    }

    /**
     * Clear the AspectPackageInfo cache for the passed targets. This flushes the dependency graph for those targets.
     */
    public synchronized void flushAspectInfoCache(List<String> targets) {
        for (String target : targets) {
            // the target may not even be in cache, that is ok, just try to remove it from both current and wildcard caches
            // if the target exists in either it will get flushed
            this.aspectInfoCacheCurrent.remove(target);
            this.aspectInfoCacheWildcards.remove(target);
        }
    }

    // INTERNALS

    private void getAspectPackageInfoForTarget(String target, String eclipseProjectName,
            WorkProgressMonitor progressMonitor, String caller, Map<String, AspectPackageInfo> resultMap)
            throws IOException, InterruptedException, BazelCommandLineToolConfigurationException {
        String logstr = " [prj=" + eclipseProjectName + ", src=" + caller + "]";

        AspectPackageInfo aspectInfo = aspectInfoCacheCurrent.get(target);
        if (aspectInfo != null) {
            BazelJdtPlugin.logInfo("ASPECT CACHE HIT target: " + target + logstr);
            resultMap.put(target, aspectInfo);
            this.numberCacheHits++;
        } else {
            BazelJdtPlugin.logInfo("ASPECT CACHE MISS target: " + target + logstr);
            List<String> lookupTargets = new ArrayList<>();
            lookupTargets.add(target);
            List<String> discoveredAspectFilePaths = generateAspectPackageInfoFiles(lookupTargets, progressMonitor);

            ImmutableMap<String, AspectPackageInfo> map =
                    AspectPackageInfo.loadAspectFilePaths(discoveredAspectFilePaths);
            resultMap.putAll(map);
            for (String resultTarget : map.keySet()) {
                BazelJdtPlugin.logInfo("ASPECT CACHE LOAD target: " + resultTarget + logstr);
                aspectInfoCacheCurrent.put(resultTarget, map.get(resultTarget));
                aspectInfoCacheLastgood.put(resultTarget, map.get(resultTarget));
            }
            if (resultMap.get(target) == null) {
                // still don't have the aspect for the target, use the last known one that computed
                // it could be because the user introduced a compile error in it and the Aspect wont run.
                // In this case use the last known good result of the Aspect for that target and hope for the best. The lastgood cache is never
                // cleared, so if the Aspect ran correctly at least once since the IDE started it should be here (but possibly out of date depending
                // on what changes were introduced along with the compile error)
                aspectInfo = aspectInfoCacheLastgood.get(target);
                if (aspectInfo != null) {
                    resultMap.put(target, aspectInfo);
                } else {
                    BazelJdtPlugin.logInfo("ASPECT CACHE FAIL target: " + target + logstr);
                }
            }
        }

        progressMonitor.worked(resultMap.size());
    }

    /**
     * Runs the Aspect for the list of passed targets. Returns the list of file paths to the output artifacts created by
     * the Aspects.
     *
     * @throws BazelCommandLineToolConfigurationException
     */
    private synchronized List<String> generateAspectPackageInfoFiles(Collection<String> targets,
            WorkProgressMonitor progressMonitor)
            throws IOException, InterruptedException, BazelCommandLineToolConfigurationException {

        TimeTracker.start(); //TODO remove time tracking

        List<String> args =
                ImmutableList.<String>builder().add("build").addAll(this.aspectOptions).addAll(targets).build();

        // Strip out the artifact list, keeping the xyz.bzleclipse-build.json files (located in subdirs in the bazel-out path)
        // Line must start with >>> and end with the aspect file suffix
        Function<String, String> filter;
        if (this.befVersion) {
            filter = t -> t.startsWith(">>>")
                    ? (t.endsWith(AspectPackageInfo.ASPECT_FILENAME_SUFFIX) ? t.replace(">>>", "") : "") : null;
        } else {
            filter = t -> t.startsWith(">>>") ? (t.endsWith(".intellij-info.txt") ? t.replace(">>>", "") : "") : null;
        }
        List<String> listOfGeneratedFilePaths =
                this.bazelCommandExecutor.runBazelAndGetErrorLines(ConsoleType.WORKSPACE,
                    this.bazelWorkspaceCommandRunner.getBazelWorkspaceRootDirectory(), progressMonitor, args, filter);
        if (!this.befVersion) {
            // TODO post command processing with JQ
            listOfGeneratedFilePaths = filterIntellijOutput(listOfGeneratedFilePaths, targets);
        }
        TimeTracker.addAndFinish(); //TODO remove time tracking

        return listOfGeneratedFilePaths;
    }

    private List<String> filterIntellijOutput(List<String> outputLines, Collection<String> targets) {
        final ArrayList<String> modules = new ArrayList<String>();
        final boolean isWindows = SystemUtils.IS_OS_WINDOWS;
        final Pattern pattern = Pattern.compile(".*\\.intellij-info\\.txt");

        outputLines.parallelStream().filter((line) -> {
            Matcher matcher = pattern.matcher(line);
            return matcher.matches();
        }).forEach((String intellijOutputModule) -> {
            String jsonFile = intellijOutputModule.replaceAll("\\.intellij-info\\.txt", "")
                    + AspectPackageInfo.ASPECT_FILENAME_SUFFIX;

            try {
                int exitCode = isWindows ? generateWindowsJson(intellijOutputModule, jsonFile)
                        : generateLinuxJson(intellijOutputModule, jsonFile);

                if (0 == exitCode) {
                    modules.add(jsonFile);
                }
            } catch (IOException exc) {
                BazelJdtPlugin.logException(exc);
            } catch (InterruptedException exc) {
                BazelJdtPlugin.logException(exc);
            }
        });

        return modules;
    }

    private int generateWindowsJson(String intellijOutputModule, String jsonFile)
            throws InterruptedException, IOException {
        String fromFile = intellijOutputModule.replaceAll("/", "\\\\");
        String toFile = jsonFile.replaceAll("/", "\\\\");
        URL fileUrl = BazelJdtPlugin.findResource("/resources/jq/runjq.bat");
        String cmd = fileUrl.getPath() + " " + fromFile + " " + toFile;
        Process process = Runtime.getRuntime().exec(cmd);
        int exitCode = process.waitFor();
        return exitCode;
    }

    private int generateLinuxJson(String intellijOutputModule, String jsonFile)
            throws InterruptedException, IOException {
        URL fileUrl = BazelJdtPlugin.findResource("/resources/jq/runjq.sh");
        String cmd = "bash " + fileUrl.getPath() + " " + intellijOutputModule + " " + jsonFile;
        Process process = Runtime.getRuntime().exec(cmd);
        int exitCode = process.waitFor();
        return exitCode;
    }

}
