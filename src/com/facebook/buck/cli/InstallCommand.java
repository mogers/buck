/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleInfoPlistParsing;
import com.facebook.buck.apple.ApplePlatform;
import com.facebook.buck.apple.BuildRuleWithAppleBundle;
import com.facebook.buck.apple.device.AppleDeviceHelper;
import com.facebook.buck.apple.simulator.AppleCoreSimulatorServiceController;
import com.facebook.buck.apple.simulator.AppleSimulator;
import com.facebook.buck.apple.simulator.AppleSimulatorController;
import com.facebook.buck.apple.simulator.AppleSimulatorDiscovery;
import com.facebook.buck.cli.UninstallCommand.UninstallOptions;
import com.facebook.buck.command.Build;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.UnixUserIdFetcher;
import com.facebook.infer.annotation.Assertions;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Command so a user can build and install an APK.
 */
public class InstallCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(InstallCommand.class);
  private static final long APPLE_SIMULATOR_WAIT_MILLIS = 20000;
  private static final ImmutableList<String> APPLE_SIMULATOR_APPS = ImmutableList.of(
      "Simulator.app",
      "iOS Simulator.app");
  private static final String DEFAULT_APPLE_SIMULATOR_NAME = "iPhone 5s";
  private static final InstallResult FAILURE = InstallResult.builder().setExitCode(1).build();

  @VisibleForTesting static final String RUN_LONG_ARG = "--run";
  @VisibleForTesting static final String RUN_SHORT_ARG = "-r";
  @VisibleForTesting static final String WAIT_FOR_DEBUGGER_LONG_ARG = "--wait-for-debugger";
  @VisibleForTesting static final String WAIT_FOR_DEBUGGER_SHORT_ARG = "-w";
  @VisibleForTesting static final String INSTALL_VIA_SD_LONG_ARG = "--via-sd";
  @VisibleForTesting static final String INSTALL_VIA_SD_SHORT_ARG = "-S";
  @VisibleForTesting static final String ACTIVITY_LONG_ARG = "--activity";
  @VisibleForTesting static final String ACTIVITY_SHORT_ARG = "-a";
  @VisibleForTesting static final String UNINSTALL_LONG_ARG = "--uninstall";
  @VisibleForTesting static final String UNINSTALL_SHORT_ARG = "-u";

  @Option(
      name = UNINSTALL_LONG_ARG,
      aliases = { UNINSTALL_SHORT_ARG },
      usage = "Uninstall the existing version before installing.")
  private boolean uninstallFirst = false;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private UninstallOptions uninstallOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private AdbCommandLineOptions adbOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions deviceOptions;

  @Option(
      name = "--",
      usage = "Arguments passed when running with -r. Only valid for Apple targets.",
      handler = ConsumeAllOptionsHandler.class,
      depends = "-r")
  private List<String> runArgs = Lists.newArrayList();

  @Option(
      name = RUN_LONG_ARG,
      aliases = { RUN_SHORT_ARG },
      usage = "Run an activity (the default activity for package unless -a is specified).")
  private boolean run = false;

  @Option(
      name = WAIT_FOR_DEBUGGER_LONG_ARG,
      aliases = { WAIT_FOR_DEBUGGER_SHORT_ARG },
      usage = "Have the launched process wait for the debugger")
  private boolean waitForDebugger = false;

  @Option(
      name = INSTALL_VIA_SD_LONG_ARG,
      aliases = { INSTALL_VIA_SD_SHORT_ARG },
      usage = "Copy package to external storage (SD) instead of /data/local/tmp before installing.")
  private boolean installViaSd = false;

  @Option(
      name = ACTIVITY_LONG_ARG,
      aliases = { ACTIVITY_SHORT_ARG },
      metaVar = "<pkg/activity>",
      usage = "Activity to launch e.g. com.facebook.katana/.LoginActivity. Implies -r.")
  @Nullable
  private String activity = null;

  public AdbOptions adbOptions(BuckConfig buckConfig) {
    return adbOptions.getAdbOptions(buckConfig);
  }

  public TargetDeviceOptions targetDeviceOptions() {
    return deviceOptions.getTargetDeviceOptions();
  }

  public UninstallOptions uninstallOptions() {
    return uninstallOptions;
  }

  public boolean shouldUninstallFirst() {
    return uninstallFirst;
  }

  public boolean shouldStartActivity() {
    return (activity != null) || run;
  }

  public boolean shouldInstallViaSd() {
    return installViaSd;
  }

  @Nullable
  public String getActivityToStart() {
    return activity;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    int exitCode = checkArguments(params);
    if (exitCode != 0) {
      return exitCode;
    }

    try (CommandThreadManager pool = new CommandThreadManager(
        "Install",
        params.getBuckConfig().getWorkQueueExecutionOrder(),
        getConcurrencyLimit(params.getBuckConfig()))) {
      // Get the helper targets if present
      ImmutableSet<String> installHelperTargets;
      try {
        installHelperTargets = getInstallHelperTargets(params, pool.getExecutor());
      } catch (BuildTargetException | BuildFileParseException e) {
        params.getBuckEventBus().post(
            ConsoleEvent.severe(
                MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      // Build the targets
      exitCode = super.run(params, pool.getExecutor(), installHelperTargets);
      if (exitCode != 0) {
        return exitCode;
      }
    }

    // Install the targets
    try {
      exitCode = install(params);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e.getHumanReadableErrorMessage());
    }
    if (exitCode != 0) {
      return exitCode;
    }
    return exitCode;
  }

  private int install(CommandRunnerParams params)
      throws IOException, InterruptedException, NoSuchBuildTargetException {

    Build build = super.getBuild();
    int exitCode = 0;

    for (BuildTarget buildTarget : getBuildTargets()) {

      BuildRule buildRule = build.getRuleResolver().requireRule(buildTarget);

      if (buildRule instanceof InstallableApk) {
        ExecutionContext.Builder builder = ExecutionContext.builder()
            .setExecutionContext(build.getExecutionContext())
            .setAdbOptions(Optional.of(adbOptions(params.getBuckConfig())))
            .setTargetDeviceOptions(Optional.of(targetDeviceOptions()));
        exitCode = installApk(
            params,
            (InstallableApk) buildRule,
            builder.build());
        if (exitCode != 0) {
          return exitCode;
        }
      } else if (buildRule instanceof BuildRuleWithAppleBundle) {
        AppleBundle appleBundle = ((BuildRuleWithAppleBundle) buildRule).getAppleBundle();
        InstallEvent.Started started = InstallEvent.started(appleBundle.getBuildTarget());
        params.getBuckEventBus().post(started);
        InstallResult installResult = installAppleBundle(
            params,
            appleBundle,
            appleBundle.getProjectFilesystem(),
            build.getExecutionContext().getProcessExecutor());
        params.getBuckEventBus().post(
            InstallEvent.finished(
                started,
                installResult.getExitCode() == 0,
                installResult.getLaunchedPid()));
        exitCode = installResult.getExitCode();
        if (exitCode != 0) {
          return exitCode;
        }
      } else {
        params.getBuckEventBus().post(
            ConsoleEvent.severe(
                String.format(
                    "Specified rule %s must be of type android_binary() or apk_genrule() or " +
                        "apple_bundle() but was %s().\n",
                    buildRule.getFullyQualifiedName(),
                    buildRule.getType())));
        return 1;
      }


    }
    return exitCode;
  }

  private ImmutableSet<String> getInstallHelperTargets(
      CommandRunnerParams params,
      Executor executor)
      throws IOException, InterruptedException, BuildTargetException, BuildFileParseException{

    ImmutableSet.Builder<String> installHelperTargets = ImmutableSet.builder();
    for (int index = 0; index < getArguments().size(); index++) {

      // TODO(ryu2): Cache argument parsing
        TargetNodeSpec spec = parseArgumentsAsTargetNodeSpecs(
            params.getBuckConfig(),
            getArguments()).get(index);

        BuildTarget target = params.getParser().resolveTargetSpec(
            params.getBuckEventBus(),
            params.getCell(),
            getEnableProfiling(),
            executor,
            spec).iterator().next();

        TargetNode<?> node = params.getParser().getTargetNode(
            params.getBuckEventBus(),
            params.getCell(),
            getEnableProfiling(),
            target);

        if (node != null &&
            node.getDescription().getBuildRuleType().equals(AppleBundleDescription.TYPE)) {
          for (Flavor flavor : node.getBuildTarget().getFlavors()) {
            if (ApplePlatform.needsInstallHelper(flavor.getName())) {
              AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());

              Optional<BuildTarget> deviceHelperTarget = appleConfig.getAppleDeviceHelperTarget();
              Optionals.addIfPresent(
                  Optionals.bind(deviceHelperTarget,
                      new Function<BuildTarget, Optional<String>>() {
                        @Override
                        public Optional<String> apply(BuildTarget input) {
                          return !input.toString().isEmpty()
                              ? Optional.of(input.toString())
                              : Optional.<String>absent();
                        }}),
                  installHelperTargets);
              }
            }
          }
        }
    return installHelperTargets.build();
  }

  private int installApk(
      CommandRunnerParams params,
      InstallableApk installableApk,
      ExecutionContext executionContext) throws IOException, InterruptedException {
    final AdbHelper adbHelper = AdbHelper.get(
        executionContext,
        params.getBuckConfig().getRestartAdbOnFailure());

    // Uninstall the app first, if requested.
    if (shouldUninstallFirst()) {
      String packageName = AdbHelper.tryToExtractPackageNameFromManifest(installableApk);
      adbHelper.uninstallApp(packageName, uninstallOptions().shouldKeepUserData());
      // Perhaps the app wasn't installed to begin with, shouldn't stop us.
    }

    if (!adbHelper.installApk(installableApk, shouldInstallViaSd(), false)) {
      return 1;
    }

    // We've installed the application successfully.
    // Is either of --activity or --run present?
    if (shouldStartActivity()) {
      int exitCode = adbHelper.startActivity(installableApk, getActivityToStart());
      if (exitCode != 0) {
        return exitCode;
      }
    }

    return 0;
  }

  private InstallResult installAppleBundle(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor)
      throws IOException, InterruptedException, NoSuchBuildTargetException {
    if (appleBundle.getPlatformName().equals(ApplePlatform.IPHONESIMULATOR.getName())) {
      return installAppleBundleForSimulator(params, appleBundle, projectFilesystem,
          processExecutor);
    }
    if (appleBundle.getPlatformName().equals(ApplePlatform.IPHONEOS.getName())) {
      return installAppleBundleForDevice(params, appleBundle, projectFilesystem,
          processExecutor);
    }
    params.getConsole().printBuildFailure("Install not yet supported for platform " +
        appleBundle.getPlatformName() + ".");
    return FAILURE;
  }

  private InstallResult installAppleBundleForDevice(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor)
      throws IOException, InterruptedException, NoSuchBuildTargetException {
    // TODO(bhamiltoncx): This should be shared with the build and passed down.
    AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());

    final Path helperPath;
    Optional<BuildTarget> helperTarget = appleConfig.getAppleDeviceHelperTarget();
    if (helperTarget.isPresent()) {
      BuildRuleResolver resolver = super.getBuild().getRuleResolver();
      BuildRule buildRule = resolver.requireRule(helperTarget.get());
      if (buildRule == null) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (could not resolve build rule for device helper target %s)",
                appleBundle.getFullyQualifiedName(), helperTarget.get().getBaseName()));
        return FAILURE;
      }
      Path buildRuleOutputPath = buildRule.getPathToOutput();
      if (buildRuleOutputPath == null) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (device helper target %s does not specify an output)",
                appleBundle.getFullyQualifiedName(), helperTarget.get().getBaseName()));
        return FAILURE;
      }
      helperPath = projectFilesystem.resolve(buildRuleOutputPath);
    } else {
      Optional<Path> helperOverridePath = appleConfig.getAppleDeviceHelperPath();
      if (helperOverridePath.isPresent()) {
        helperPath = projectFilesystem.resolve(helperOverridePath.get());
      } else {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (could not find path to device install helper tool)",
                appleBundle.getFullyQualifiedName()));
        return FAILURE;
      }
    }

    AppleDeviceHelper helper = new AppleDeviceHelper(processExecutor, helperPath);
    ImmutableMap<String, String> connectedDevices = helper.getConnectedDevices();

    if (connectedDevices.size() == 0) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (no connected devices found)",
              appleBundle.getFullyQualifiedName()));
      return FAILURE;
    }

    String selectedUdid = null;

    if (targetDeviceOptions().hasSerialNumber()) {
      String udidPrefix = Assertions.assertNotNull(
          targetDeviceOptions().getSerialNumber()).toLowerCase();
      for (String udid : connectedDevices.keySet()) {
        if (udid.startsWith(udidPrefix)) {
          selectedUdid = udid;
          break;
        }
      }

      if (selectedUdid == null) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s to the device %s (no connected devices with that UDID/prefix)",
                appleBundle.getFullyQualifiedName(), udidPrefix));
        return FAILURE;
      }
    } else {
      if (connectedDevices.size() > 1) {
        LOG.warn(
            "More than one connected device found, and no device ID specified.  A device will be" +
                " arbitrarily picked.");
      }

      selectedUdid = connectedDevices.keySet().iterator().next();
    }

    LOG.info(
        "Installing " + appleBundle.getFullyQualifiedName() + " to device " + selectedUdid + " (" +
            connectedDevices.get(selectedUdid) + ")");

    if (helper.installBundleOnDevice(
        selectedUdid,
        projectFilesystem.resolve(Preconditions.checkNotNull(appleBundle.getPathToOutput())))) {
      params.getConsole().printSuccess(
          "Installed " + appleBundle.getFullyQualifiedName() + " to device " + selectedUdid + " (" +
              connectedDevices.get(selectedUdid) + ")");
      if (run) {
        Optional<String> appleBundleId;
        try (InputStream bundlePlistStream =
                 projectFilesystem.getInputStreamForRelativePath(appleBundle.getInfoPlistPath())){
          appleBundleId = AppleInfoPlistParsing.getBundleIdFromPlistStream(bundlePlistStream);
        }
        if (!appleBundleId.isPresent()) {
          params.getConsole().printBuildFailure(
              String.format(
                  "Cannot run %s (could not get bundle ID from %s)",
                  appleBundle.getFullyQualifiedName(),
                  appleBundle.getInfoPlistPath()));
          return FAILURE;
        }

        if (waitForDebugger) {
          LOG.warn(WAIT_FOR_DEBUGGER_LONG_ARG + " not yet implemented for devices.");
        }

        if (helper.runBundleOnDevice(selectedUdid, appleBundleId.get())) {
          return InstallResult.builder().setExitCode(0).build();
        } else {
          params.getConsole().printBuildFailure(
              "Failed to run " + appleBundle.getFullyQualifiedName() + " on device " +
                  selectedUdid + " (" + connectedDevices.get(selectedUdid) + ")");
          return FAILURE;
        }
      } else {
        return InstallResult.builder().setExitCode(0).build();
      }
    } else {
      params.getConsole().printBuildFailure(
          "Failed to install " + appleBundle.getFullyQualifiedName() + " to device " +
              selectedUdid + " (" + connectedDevices.get(selectedUdid) + ")");
      return FAILURE;
    }
  }

  private InstallResult installAppleBundleForSimulator(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor) throws IOException, InterruptedException {

    // TODO(bhamiltoncx): This should be shared with the build and passed down.
    AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());
    Optional<Path> xcodeDeveloperPath = appleConfig.getAppleDeveloperDirectorySupplier(
        processExecutor).get();
    if (!xcodeDeveloperPath.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (Xcode not found)", appleBundle.getFullyQualifiedName()));
      return FAILURE;
    }

    UnixUserIdFetcher userIdFetcher = new UnixUserIdFetcher();
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(processExecutor);

    Optional<Path> coreSimulatorServicePath =
        appleCoreSimulatorServiceController.getCoreSimulatorServicePath(userIdFetcher);

    boolean shouldWaitForSimulatorsToShutdown = false;

    if (!coreSimulatorServicePath.isPresent() ||
        !coreSimulatorServicePath.get().toRealPath().startsWith(
            xcodeDeveloperPath.get().toRealPath())) {
      LOG.warn(
          "Core simulator service path %s does not match developer directory %s, " +
          "killing all simulators.",
          coreSimulatorServicePath,
          xcodeDeveloperPath.get());
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        params.getConsole().printBuildFailure("Could not kill running simulator processes.");
        return FAILURE;
      }

      shouldWaitForSimulatorsToShutdown = true;
    }

    Path simctlPath = xcodeDeveloperPath.get().resolve("usr/bin/simctl");
    Optional<AppleSimulator> appleSimulator = getAppleSimulatorForBundle(
        appleBundle,
        processExecutor,
        simctlPath);

    if (!appleSimulator.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (no appropriate simulator found)",
              appleBundle.getFullyQualifiedName()));
      return FAILURE;
    }

    Path iosSimulatorPath = null;
    Path xcodeApplicationsPath = xcodeDeveloperPath.get().resolve("Applications");
    for (String simulatorApp : APPLE_SIMULATOR_APPS) {
      Path resolvedSimulatorPath = xcodeApplicationsPath.resolve(simulatorApp);
      if (projectFilesystem.isDirectory(resolvedSimulatorPath)) {
        iosSimulatorPath = resolvedSimulatorPath;
        break;
      }
    }

    if (iosSimulatorPath == null) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not find simulator under %s, checked %s)",
              appleBundle.getFullyQualifiedName(),
              xcodeApplicationsPath,
              APPLE_SIMULATOR_APPS));
      return FAILURE;
    }

    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        processExecutor,
        simctlPath,
        iosSimulatorPath);

    if (!appleSimulatorController.canStartSimulator(appleSimulator.get().getUdid())) {
      LOG.warn("Cannot start simulator %s, killing simulators and trying again.");
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        params.getConsole().printBuildFailure("Could not kill running simulator processes.");
        return FAILURE;
      }

      shouldWaitForSimulatorsToShutdown = true;

      // Killing the simulator can cause the UDIDs to change, so we need to fetch them again.
      appleSimulator = getAppleSimulatorForBundle(appleBundle, processExecutor, simctlPath);
      if (!appleSimulator.isPresent()) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (no appropriate simulator found)",
                appleBundle.getFullyQualifiedName()));
        return FAILURE;
      }
    }

    long remainingMillis = APPLE_SIMULATOR_WAIT_MILLIS;
    if (shouldWaitForSimulatorsToShutdown) {
      Optional<Long> shutdownMillis = appleSimulatorController.waitForSimulatorsToShutdown(
          remainingMillis);
      if (!shutdownMillis.isPresent()) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (simulators did not shut down within %d ms).",
                appleBundle.getFullyQualifiedName(),
                APPLE_SIMULATOR_WAIT_MILLIS));
        return FAILURE;
      }

      LOG.debug("Simulators shut down in %d millis.", shutdownMillis.get());
      remainingMillis -= shutdownMillis.get();
    }

    LOG.debug("Starting up simulator %s", appleSimulator.get());

    Optional<Long> startMillis = appleSimulatorController.startSimulator(
        appleSimulator.get().getUdid(),
        remainingMillis);

    if (!startMillis.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not start simulator %s within %d ms)",
              appleBundle.getFullyQualifiedName(),
              appleSimulator.get().getName(),
              APPLE_SIMULATOR_WAIT_MILLIS));
      return FAILURE;
    }

    LOG.debug(
        "Simulator started in %d ms. Installing Apple bundle %s in simulator %s",
        startMillis.get(),
        appleBundle,
        appleSimulator.get());

    if (!appleSimulatorController.installBundleInSimulator(
            appleSimulator.get().getUdid(),
            projectFilesystem.resolve(Preconditions.checkNotNull(appleBundle.getPathToOutput())))) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not install bundle %s in simulator %s)",
              appleBundle.getFullyQualifiedName(),
              appleBundle.getPathToOutput(),
              appleSimulator.get().getName()));
      return FAILURE;
    }

    if (run) {
      return launchAppleBundle(
          params,
          appleBundle,
          appleSimulatorController,
          projectFilesystem,
          appleSimulator.get());
    } else {
      params.getBuckEventBus().post(
          ConsoleEvent.info(
              params.getConsole().getAnsi().asHighlightedSuccessText(
                  "Successfully installed %s. (Use `buck install -r %s` to run.)"),
              getArguments().get(0),
              getArguments().get(0)));
      return InstallResult.builder().setExitCode(0).build();
    }
  }

  private InstallResult launchAppleBundle(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      AppleSimulatorController appleSimulatorController,
      ProjectFilesystem projectFilesystem,
      AppleSimulator appleSimulator) throws IOException, InterruptedException {

    LOG.debug("Launching Apple bundle %s in simulator %s", appleBundle, appleSimulator);

    Optional<String> appleBundleId;
    try (InputStream bundlePlistStream =
             projectFilesystem.getInputStreamForRelativePath(appleBundle.getInfoPlistPath())){
        appleBundleId = AppleInfoPlistParsing.getBundleIdFromPlistStream(bundlePlistStream);
    }
    if (!appleBundleId.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not get bundle ID from %s)",
              appleBundle.getFullyQualifiedName(),
              appleBundle.getInfoPlistPath()));
      return FAILURE;
    }

    Optional<Long> launchedPid = appleSimulatorController.launchInstalledBundleInSimulator(
        appleSimulator.getUdid(),
        appleBundleId.get(),
        waitForDebugger ? AppleSimulatorController.LaunchBehavior.WAIT_FOR_DEBUGGER :
            AppleSimulatorController.LaunchBehavior.DO_NOT_WAIT_FOR_DEBUGGER,
        runArgs);
    if (!launchedPid.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot launch %s (failed to launch bundle ID %s)",
              appleBundle.getFullyQualifiedName(),
              appleBundleId.get()));
      return FAILURE;
    }

    params.getBuckEventBus().post(
        ConsoleEvent.info(
            params.getConsole().getAnsi().asHighlightedSuccessText(
                "Successfully launched %s%s. To debug, run: lldb -p %d"),
            getArguments().get(0),
            waitForDebugger ? " (waiting for debugger)" : "",
            launchedPid.get()));

    return InstallResult.builder().setExitCode(0).setLaunchedPid(launchedPid.get()).build();
  }

  private Optional<AppleSimulator> getAppleSimulatorForBundle(
      AppleBundle appleBundle,
      ProcessExecutor processExecutor,
      Path simctlPath) throws IOException, InterruptedException {
    LOG.debug("Choosing simulator for %s", appleBundle);

    Optional<AppleSimulator> simulatorByUdid = Optional.absent();
    Optional<AppleSimulator> simulatorByName = Optional.absent();
    Optional<AppleSimulator> defaultSimulator = Optional.absent();

    boolean wantUdid = deviceOptions.hasSerialNumber();
    boolean wantName = deviceOptions.getSimulatorName().isPresent();

    for (AppleSimulator simulator : AppleSimulatorDiscovery.discoverAppleSimulators(
             processExecutor,
             simctlPath)) {
      if (wantUdid &&
          deviceOptions.getSerialNumber().toLowerCase(Locale.US).equals(
              simulator.getUdid().toLowerCase(Locale.US))) {
        LOG.debug("Got UDID match (%s): %s", deviceOptions.getSerialNumber(), simulator);
        simulatorByUdid = Optional.of(simulator);
        // We shouldn't need to keep looking.
        break;
      } else if (wantName &&
                 deviceOptions.getSimulatorName().get().toLowerCase(Locale.US).equals(
                     simulator.getName().toLowerCase(Locale.US))) {
        LOG.debug("Got name match (%s): %s", simulator.getName(), simulator);
        simulatorByName = Optional.of(simulator);
        // We assume the simulators are sorted by OS version, so we'll keep
        // looking for a more recent simulator with this name.
      } else if (simulator.getName().equals(DEFAULT_APPLE_SIMULATOR_NAME)) {
        LOG.debug("Got default match (%s): %s", DEFAULT_APPLE_SIMULATOR_NAME, simulator);
        defaultSimulator = Optional.of(simulator);
      }
    }

    if (wantUdid) {
      if (simulatorByUdid.isPresent()) {
        return simulatorByUdid;
      } else {
        LOG.warn(
            "Asked to find simulator with UDID %s, but couldn't find one.",
            deviceOptions.getSerialNumber());
        return Optional.<AppleSimulator>absent();
      }
    } else if (wantName) {
      if (simulatorByName.isPresent()) {
        return simulatorByName;
      } else {
        LOG.warn(
            "Asked to find simulator with name %s, but couldn't find one.",
            deviceOptions.getSimulatorName().get());
        return Optional.<AppleSimulator>absent();
      }
    } else {
      return defaultSimulator;
    }
  }

  @Override
  public String getShortDescription() {
    return "builds and installs an application";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

}
