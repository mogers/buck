/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.EnumSet;

public class CxxLinkableEnhancer {

  private static final EnumSet<Linker.LinkType> SONAME_REQUIRED_LINK_TYPES = EnumSet.of(
      Linker.LinkType.SHARED,
      Linker.LinkType.MACH_O_BUNDLE
  );

  // Utility class doesn't instantiate.
  private CxxLinkableEnhancer() {}

  public static CxxLink createCxxLinkableBuildRule(
      CxxPlatform cxxPlatform,
      BuildRuleParams params,
      final SourcePathResolver resolver,
      BuildTarget target,
      Path output,
      ImmutableList<Arg> args,
      Linker.LinkableDepType depType,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType) {

    final Linker linker = cxxPlatform.getLd();

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Pass any platform specific or extra linker flags.
    argsBuilder.addAll(
        SanitizedArg.from(
            cxxPlatform.getDebugPathSanitizer().sanitize(Optional.<Path>absent()),
            cxxPlatform.getLdflags()));

    argsBuilder.addAll(args);

    // Add all arguments needed to link in the C/C++ platform runtime.
    Linker.LinkableDepType runtimeDepType = depType;
    if (cxxRuntimeType.or(Linker.CxxRuntimeType.DYNAMIC) == Linker.CxxRuntimeType.STATIC) {
      runtimeDepType = Linker.LinkableDepType.STATIC;
    }
    argsBuilder.addAll(StringArg.from(cxxPlatform.getRuntimeLdflags().get(runtimeDepType)));

    final ImmutableList<Arg> allArgs = argsBuilder.build();

    // Build the C/C++ link step.
    return new CxxLink(
        // Construct our link build rule params.  The important part here is combining the build
        // rules that construct our object file inputs and also the deps that build our
        // dependencies.
        params.copyWithChanges(
            target,
            new Supplier<ImmutableSortedSet<BuildRule>>() {
              @Override
              public ImmutableSortedSet<BuildRule> get() {
                return FluentIterable.from(allArgs)
                    .transformAndConcat(Arg.getDepsFunction(resolver))
                    .append(linker.getDeps(resolver))
                    .toSortedSet(Ordering.natural());
              }
            },
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        resolver,
        linker,
        output,
        allArgs);
  }

  /**
   * Construct a {@link CxxLink} rule that builds a native linkable from top-level input objects
   * and a dependency tree of {@link NativeLinkable} dependencies.
   */
  public static CxxLink createCxxLinkableBuildRule(
      CxxPlatform cxxPlatform,
      BuildRuleParams params,
      final SourcePathResolver resolver,
      BuildTarget target,
      Linker.LinkType linkType,
      Optional<String> soname,
      Path output,
      ImmutableList<Arg> args,
      Linker.LinkableDepType depType,
      Iterable<? extends BuildRule> nativeLinkableDeps,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSet<FrameworkPath> frameworks) throws NoSuchBuildTargetException {

    // Soname should only ever be set when linking a "shared" library.
    Preconditions.checkState(!soname.isPresent() || SONAME_REQUIRED_LINK_TYPES.contains(linkType));

    // Bundle loaders are only supported for Mach-O bundle libraries
    Preconditions.checkState(
        !bundleLoader.isPresent() || linkType == Linker.LinkType.MACH_O_BUNDLE);

    // Collect and topologically sort our deps that contribute to the link.
    ImmutableList.Builder<NativeLinkableInput> nativeLinkableInputs = ImmutableList.builder();
    for (NativeLinkable nativeLinkable : Maps.filterKeys(
        NativeLinkables.getNativeLinkables(
            cxxPlatform,
            FluentIterable.from(nativeLinkableDeps)
                .filter(NativeLinkable.class),
            depType),
        Predicates.not(Predicates.in(blacklist))).values()) {
      nativeLinkableInputs.add(
          NativeLinkables.getNativeLinkableInput(cxxPlatform, depType, nativeLinkable));
    }
    NativeLinkableInput linkableInput = NativeLinkableInput.concat(nativeLinkableInputs.build());

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // If we're doing a shared build, pass the necessary flags to the linker, including setting
    // the soname.
    if (linkType == Linker.LinkType.SHARED) {
      argsBuilder.add(new StringArg("-shared"));
    } else if (linkType == Linker.LinkType.MACH_O_BUNDLE) {
      argsBuilder.add(new StringArg("-bundle"));
      // It's possible to build a Mach-O bundle without a bundle loader (logic tests, for example).
      if (bundleLoader.isPresent()) {
        argsBuilder.add(
            new StringArg("-bundle_loader"),
            new SourcePathArg(resolver, bundleLoader.get()));
      }
    }
    if (soname.isPresent()) {
      argsBuilder.addAll(StringArg.from(cxxPlatform.getLd().soname(soname.get())));
    }

    // Add all the top-level arguments.
    argsBuilder.addAll(args);

    // Add all arguments from our dependencies.
    argsBuilder.addAll(linkableInput.getArgs());

    // Add all shared libraries
    addSharedLibrariesLinkerArgs(
        cxxPlatform,
        resolver,
        ImmutableSortedSet.copyOf(linkableInput.getLibraries()),
        argsBuilder);

    // Add framework args - from both linkable dependancies and the frameworks for the binary
    addFrameworkLinkerArgs(
        cxxPlatform,
        resolver,
        mergeFrameworks(linkableInput, frameworks),
        argsBuilder);

    final ImmutableList<Arg> allArgs = argsBuilder.build();

    return createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        resolver,
        target,
        output,
        allArgs,
        depType,
        cxxRuntimeType);
  }

  private static ImmutableSortedSet<FrameworkPath> mergeFrameworks(
      NativeLinkableInput nativeLinkable,
      ImmutableSet<FrameworkPath> frameworkPaths) {
    return ImmutableSortedSet.<FrameworkPath>naturalOrder()
        .addAll(nativeLinkable.getFrameworks())
        .addAll(frameworkPaths)
        .build();
  }

  private static void addSharedLibrariesLinkerArgs(
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> allLibraries,
      ImmutableList.Builder<Arg> argsBuilder) {

    final Function<FrameworkPath, Path> frameworkPathToSearchPath =
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver);

    argsBuilder.add(
        new FrameworkPathArg(resolver, allLibraries) {

          @Override
          public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
            return super.appendToRuleKey(builder)
                .setReflectively("frameworkPathToSearchPath", frameworkPathToSearchPath);
          }

          @Override
          public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
            ImmutableSortedSet<Path> searchPaths = FluentIterable.from(frameworkPaths)
                .transform(frameworkPathToSearchPath)
                .transform(
                    new Function<Path, Path>() {
                      @Override
                      public Path apply(Path input) {
                        return input.getParent();
                      }
                    })
                .filter(Predicates.notNull())
                .toSortedSet(Ordering.natural());
            for (Path searchPath : searchPaths) {
              builder.add("-L");
              builder.add(searchPath.toString());
            }
          }
        });

    // Add all libraries link args
    argsBuilder.add(new FrameworkPathArg(resolver, allLibraries) {
      @Override
      public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
        for (FrameworkPath frameworkPath : frameworkPaths) {
          String libName = MorePaths.stripPathPrefixAndExtension(
              frameworkPath.getFileName(resolver.getAbsolutePathFunction()),
              "lib");
          // libraries set can contain path-qualified libraries, or just library
          // search paths.
          // Assume these end in '../lib' and filter out here.
          if (libName.isEmpty()) {
            continue;
          }
          builder.add("-l" + libName);
        }
      }
    });
  }

  private static void addFrameworkLinkerArgs(
      CxxPlatform cxxPlatform,
      SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> allFrameworks,
      ImmutableList.Builder<Arg> argsBuilder) {

    final Function<FrameworkPath, Path> frameworkPathToSearchPath =
        CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver);

    argsBuilder.add(
        new FrameworkPathArg(resolver, allFrameworks) {
          @Override
          public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
            return super.appendToRuleKey(builder)
                .setReflectively("frameworkPathToSearchPath", frameworkPathToSearchPath);
          }

          @Override
          public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
            ImmutableSortedSet<Path> searchPaths = FluentIterable.from(frameworkPaths)
                .transform(frameworkPathToSearchPath)
                .toSortedSet(Ordering.natural());
            for (Path searchPath : searchPaths) {
              builder.add("-F");
              builder.add(searchPath.toString());
            }
          }
        });

    // Add all framework link args
    argsBuilder.add(frameworksToLinkerArg(resolver, allFrameworks));
  }

  @VisibleForTesting
  static Arg frameworksToLinkerArg(
      final SourcePathResolver resolver,
      ImmutableSortedSet<FrameworkPath> frameworkPaths) {
    return new FrameworkPathArg(resolver, frameworkPaths) {
      @Override
      public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
        for (FrameworkPath frameworkPath : frameworkPaths) {
          builder.add("-framework");
          builder.add(frameworkPath.getName(resolver.getAbsolutePathFunction()));
        }
      }
    };
  }

  public static CxxLink createCxxLinkableSharedBuildRule(
      CxxPlatform cxxPlatform,
      BuildRuleParams params,
      final SourcePathResolver resolver,
      BuildTarget target,
      Path output,
      String soname,
      ImmutableList<Arg> args) {
    return createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        resolver,
        target,
        output,
        ImmutableList.<Arg>builder()
            .add(new StringArg("-shared"))
            .addAll(StringArg.from(cxxPlatform.getLd().soname(soname)))
            .addAll(args)
            .build(),
        Linker.LinkableDepType.SHARED,
        Optional.<Linker.CxxRuntimeType>absent());
  }

}
