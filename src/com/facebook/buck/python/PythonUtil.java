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

package com.facebook.buck.python;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.cxx.OmnibusLibraries;
import com.facebook.buck.cxx.SharedLibrary;
import com.facebook.buck.cxx.SharedNativeLinkTarget;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.graph.AbstractBreadthFirstThrowingTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class PythonUtil {

  private PythonUtil() {}

  public static ImmutableMap<Path, SourcePath> toModuleMap(
      BuildTarget target,
      SourcePathResolver resolver,
      String parameter,
      Path baseModule,
      Iterable<SourceList> inputs) {

    ImmutableMap.Builder<Path, SourcePath> moduleNamesAndSourcePaths = ImmutableMap.builder();

    for (SourceList input : inputs) {
      ImmutableMap<String, SourcePath> namesAndSourcePaths;
      if (input.getUnnamedSources().isPresent()) {
        namesAndSourcePaths =
            resolver.getSourcePathNames(
                target,
                parameter,
                input.getUnnamedSources().get());
      } else {
        namesAndSourcePaths = input.getNamedSources().get();
      }
      for (ImmutableMap.Entry<String, SourcePath> entry : namesAndSourcePaths.entrySet()) {
        moduleNamesAndSourcePaths.put(
            baseModule.resolve(entry.getKey()),
            entry.getValue());
      }
    }

    return moduleNamesAndSourcePaths.build();
  }

  /** Convert a path to a module to it's module name as referenced in import statements. */
  public static String toModuleName(BuildTarget target, String name) {
    int ext = name.lastIndexOf('.');
    if (ext == -1) {
      throw new HumanReadableException(
          "%s: missing extension for module path: %s",
          target,
          name);
    }
    name = name.substring(0, ext);
    return MorePaths.pathWithUnixSeparators(name).replace('/', '.');
  }

  public static ImmutableSortedSet<BuildRule> getDepsFromComponents(
      SourcePathResolver resolver,
      PythonPackageComponents components) {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resolver.filterBuildRuleInputs(components.getModules().values()))
        .addAll(resolver.filterBuildRuleInputs(components.getResources().values()))
        .addAll(resolver.filterBuildRuleInputs(components.getNativeLibraries().values()))
        .addAll(resolver.filterBuildRuleInputs(components.getPrebuiltLibraries()))
        .build();
  }

  private static boolean hasNativeCode(
      CxxPlatform cxxPlatform,
      PythonPackageComponents components) {
    for (Path module : components.getModules().keySet()) {
      if (module.toString().endsWith(cxxPlatform.getSharedLibraryExtension())) {
        return true;
      }
    }
    return false;
  }

  public static PythonPackageComponents getAllComponents(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      final PythonPackageComponents packageComponents,
      final PythonPlatform pythonPlatform,
      final CxxPlatform cxxPlatform,
      final NativeLinkStrategy nativeLinkStrategy)
      throws NoSuchBuildTargetException {

    final PythonPackageComponents.Builder allComponents =
        new PythonPackageComponents.Builder(params.getBuildTarget());

    final Map<BuildTarget, CxxPythonExtension> extensions = new LinkedHashMap<>();
    final Map<BuildTarget, SharedNativeLinkTarget> nativeLinkTargetRoots = new LinkedHashMap<>();
    final Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();
    final Set<BuildTarget> excludedNativeLinkableRoots = new LinkedHashSet<>();

    // Add the top-level components.
    allComponents.addComponent(packageComponents, params.getBuildTarget());

    // Walk all our transitive deps to build our complete package that we'll
    // turn into an executable.
    new AbstractBreadthFirstThrowingTraversal<BuildRule, NoSuchBuildTargetException>(
        params.getDeps()) {
      private final ImmutableList<BuildRule> empty = ImmutableList.of();
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) throws NoSuchBuildTargetException {
        Iterable<BuildRule> deps = empty;
        Optional<SharedNativeLinkTarget> linkTarget =
            NativeLinkables.getSharedNativeLinkTarget(rule, cxxPlatform);
        if (rule instanceof CxxPythonExtension) {
          extensions.put(rule.getBuildTarget(), (CxxPythonExtension) rule);
        } else if (rule instanceof PythonPackagable) {
          PythonPackagable packagable = (PythonPackagable) rule;
          PythonPackageComponents comps =
              packagable.getPythonPackageComponents(pythonPlatform, cxxPlatform);
          allComponents.addComponent(comps, rule.getBuildTarget());
          if (hasNativeCode(cxxPlatform, comps)) {
            for (BuildRule dep : rule.getDeps()) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                excludedNativeLinkableRoots.add(linkable.getBuildTarget());
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
              }
            }
          }
          deps = rule.getDeps();
        } else if (linkTarget.isPresent()) {
          nativeLinkTargetRoots.put(linkTarget.get().getBuildTarget(), linkTarget.get());
        } else if (rule instanceof NativeLinkable) {
          nativeLinkableRoots.put(rule.getBuildTarget(), (NativeLinkable) rule);
        }
        return deps;
      }
    }.start();

    // For the merged strategy, build up the lists of included native linkable roots, and the
    // excluded native linkable roots.
    if (nativeLinkStrategy == NativeLinkStrategy.MERGED) {

      // Include all native link targets we found which aren't explicitly excluded.
      Map<BuildTarget, SharedNativeLinkTarget> includedNativeLinkTargetRoots =
          new LinkedHashMap<>();
      for (Map.Entry<BuildTarget, SharedNativeLinkTarget> ent : nativeLinkTargetRoots.entrySet()) {
        if (!excludedNativeLinkableRoots.contains(ent.getKey())) {
          includedNativeLinkTargetRoots.put(ent.getKey(), ent.getValue());
        }
      }

      // All C/C++ python extensions are included native link targets.
      Map<BuildTarget, CxxPythonExtension> includedExtensions = new LinkedHashMap<>();
      for (CxxPythonExtension extension : extensions.values()) {
        SharedNativeLinkTarget target = extension.getNativeLinkTarget(pythonPlatform);
        includedExtensions.put(target.getBuildTarget(), extension);
        includedNativeLinkTargetRoots.put(target.getBuildTarget(), target);
      }

      OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              params,
              ruleResolver,
              pathResolver,
              cxxPlatform,
              includedNativeLinkTargetRoots.values(),
              Maps.filterKeys(
                  nativeLinkableRoots,
                  Predicates.not(Predicates.in(includedNativeLinkTargetRoots.keySet()))).values());

      // Add all the roots from the omnibus link.  If it's an extension, add it as a module.
      // Otherwise, add it as a native library.
      for (Map.Entry<BuildTarget, SharedLibrary> root : libraries.getRoots().entrySet()) {
        CxxPythonExtension extension = includedExtensions.get(root.getKey());
        if (extension != null) {
          allComponents.addModule(extension.getModule(), root.getValue().getPath(), root.getKey());
        } else {
          allComponents.addNativeLibraries(
              Paths.get(root.getValue().getSoname()),
              root.getValue().getPath(),
              root.getKey());
        }
      }

      // Add all remaining libraries as native libraries.
      for (SharedLibrary library : libraries.getLibraries()) {
        allComponents.addNativeLibraries(
            Paths.get(library.getSoname()),
            library.getPath(),
            params.getBuildTarget());
      }
    } else {

      // For regular linking, add all extensions via the package components interface.
      for (Map.Entry<BuildTarget, CxxPythonExtension> entry : extensions.entrySet()) {
        allComponents.addComponent(
            entry.getValue().getPythonPackageComponents(pythonPlatform, cxxPlatform),
            entry.getKey());
      }

      // Add all the native libraries.
      ImmutableMap<String, SourcePath> libs =
          NativeLinkables.getTransitiveSharedLibraries(
              cxxPlatform,
              params.getDeps(),
              Predicates.instanceOf(PythonPackagable.class));
      for (Map.Entry<String, SourcePath> ent : libs.entrySet()) {
        allComponents.addNativeLibraries(
            Paths.get(ent.getKey()),
            ent.getValue(),
            params.getBuildTarget());
      }
    }


    return allComponents.build();
  }

  public static Path getBasePath(BuildTarget target, Optional<String> override) {
    return override.isPresent()
        ? Paths.get(override.get().replace('.', '/'))
        : target.getBasePath();
  }

}
