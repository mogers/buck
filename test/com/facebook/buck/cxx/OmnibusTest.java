/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

public class OmnibusTest {

  @Test
  public void includedDeps() throws NoSuchBuildTargetException {
    NativeLinkable a = new Node("//:a");
    NativeLinkable b = new Node("//:b");
    SharedNativeLinkTarget root = new Root("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.<NativeLinkable>of());
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.containsInAnyOrder(a.getBuildTarget(), b.getBuildTarget()));
    assertThat(
        spec.getBody().keySet(),
        Matchers.containsInAnyOrder(a.getBuildTarget(), b.getBuildTarget()));
    assertThat(
        spec.getRoots().keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(
        spec.getDeps().keySet(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.<BuildTarget>empty());

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> libs =
        Omnibus.getSharedLibraries(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
            resolver,
            pathResolver,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.<NativeLinkable>of())
            .toSonameMap();
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get(root.getBuildTarget().toString())),
        root.getSharedNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get("libomnibus.so")),
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC),
        b.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void excludedAndIncludedDeps() throws NoSuchBuildTargetException {
    NativeLinkable a = new Node("//:a");
    NativeLinkable b = new SharedOnlyNode("//:b");
    SharedNativeLinkTarget root = new Root("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.<NativeLinkable>of());
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getBody().keySet(),
        Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getRoots().keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(
        spec.getDeps().keySet(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(b.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> libs =
        Omnibus.getSharedLibraries(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
            resolver,
            pathResolver,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.<NativeLinkable>of())
            .toSonameMap();
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get(root.getBuildTarget().toString())),
        root.getSharedNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM),
        b.getNativeLinkableInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get("libomnibus.so")),
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void excludedDepExcludesTransitiveDep() throws NoSuchBuildTargetException {
    NativeLinkable a = new Node("//:a");
    NativeLinkable b = new Node("//:b");
    NativeLinkable c = new SharedOnlyNode("//:c", ImmutableList.of(b));
    SharedNativeLinkTarget root = new Root("//:root", ImmutableList.of(a, c));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.<NativeLinkable>of());
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getBody().keySet(),
        Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getRoots().keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(
        spec.getDeps().keySet(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(b.getBuildTarget(), c.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> libs =
        Omnibus.getSharedLibraries(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
            resolver,
            pathResolver,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.<NativeLinkable>of())
            .toSonameMap();
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            c.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get(root.getBuildTarget().toString())),
        root.getSharedNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM),
        c.getNativeLinkableInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
    assertThat(
        libs.get(c.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get("libomnibus.so")),
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void depOfExcludedRoot() throws NoSuchBuildTargetException {
    NativeLinkable a = new Node("//:a");
    SharedNativeLinkTarget root = new Root("//:root", ImmutableList.of(a));
    NativeLinkable b = new Node("//:b");
    NativeLinkable excludedRoot = new Node("//:excluded_root", ImmutableList.of(b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot));
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getBody().keySet(),
        Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getRoots().keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(
        spec.getDeps().keySet(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(excludedRoot.getBuildTarget(), b.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> libs =
        Omnibus.getSharedLibraries(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
            resolver,
            pathResolver,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot))
            .toSonameMap();
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            excludedRoot.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get(root.getBuildTarget().toString())),
        root.getSharedNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertThat(
        libs.get(excludedRoot.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get("libomnibus.so")),
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void commondDepOfIncludedAndExcludedRoots() throws NoSuchBuildTargetException {
    NativeLinkable a = new Node("//:a");
    SharedNativeLinkTarget root = new Root("//:root", ImmutableList.of(a));
    NativeLinkable excludedRoot = new Node("//:excluded_root", ImmutableList.of(a));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot));
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getBody().keySet(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getRoots().keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(
        spec.getDeps().keySet(),
        Matchers.<BuildTarget>empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(excludedRoot.getBuildTarget(), a.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> libs =
        Omnibus.getSharedLibraries(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
            resolver,
            pathResolver,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot))
            .toSonameMap();
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            excludedRoot.getBuildTarget().toString(),
            a.getBuildTarget().toString()));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(pathResolver, libs.get(root.getBuildTarget().toString())),
        root.getSharedNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertThat(
        libs.get(excludedRoot.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
    assertThat(
        libs.get(a.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(BuildTargetSourcePath.class)));
  }

  private CxxLink getCxxLinkRule(SourcePathResolver resolver, SourcePath path) {
    return ((CxxLink) resolver.getRule(path).get());
  }

  private void assertCxxLinkContainsNativeLinkableInput(
      CxxLink link,
      NativeLinkableInput... inputs) {
    for (NativeLinkableInput input : inputs) {
      assertThat(
          link.getArgs(),
          Matchers.hasItems(Arg.stringify(input.getArgs()).toArray(new String[1])));
    }
  }

  private static class Node implements NativeLinkable {

    private final BuildTarget target;
    private final Iterable<? extends NativeLinkable> deps;
    private final Iterable<? extends NativeLinkable> exportedDeps;

    public Node(
        String target,
        Iterable<? extends NativeLinkable> deps,
        Iterable<? extends NativeLinkable> exportedDeps) {
      this.target = BuildTargetFactory.newInstance(target);
      this.deps = deps;
      this.exportedDeps = exportedDeps;
    }

    public Node(
        String target,
        Iterable<? extends NativeLinkable> deps) {
      this(target, deps, ImmutableList.<NativeLinkable>of());
    }

    public Node(String target) {
      this(target, ImmutableList.<NativeLinkable>of(), ImmutableList.<NativeLinkable>of());
    }

    @Override
    public BuildTarget getBuildTarget() {
      return target;
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
      return deps;
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
        CxxPlatform cxxPlatform) {
      return exportedDeps;
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type) {
      return NativeLinkableInput.builder()
          .addArgs(new StringArg(getBuildTarget().toString()))
          .build();
    }

    @Override
    public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return Linkage.ANY;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
      return ImmutableMap.<String, SourcePath>of(
          getBuildTarget().toString(),
          new FakeSourcePath(getBuildTarget().toString()));
    }

  }

  private static class SharedOnlyNode extends Node {

    public SharedOnlyNode(
        String target,
        Iterable<? extends NativeLinkable> deps) {
      super(target, deps);
    }

    public SharedOnlyNode(String target) {
      super(target);
    }

    @Override
    public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return Linkage.SHARED;
    }

  }

  private static class Root extends Node implements SharedNativeLinkTarget {

    public Root(
        String target,
        Iterable<? extends NativeLinkable> deps,
        Iterable<? extends NativeLinkable> exportedDeps) {
      super(target, deps, exportedDeps);
    }

    public Root(
        String target,
        Iterable<? extends NativeLinkable> deps) {
      super(target, deps);
    }

    public Root(String target) {
      super(target);
    }

    @Override
    public Iterable<? extends NativeLinkable> getSharedNativeLinkTargetDeps(
        CxxPlatform cxxPlatform) {
      return Iterables.concat(
          getNativeLinkableDeps(cxxPlatform),
          getNativeLinkableExportedDeps(cxxPlatform));
    }

    @Override
    public String getSharedNativeLinkTargetLibraryName(CxxPlatform cxxPlatform) {
      return getBuildTarget().toString();
    }

    @Override
    public NativeLinkableInput getSharedNativeLinkTargetInput(CxxPlatform cxxPlatform) {
      return NativeLinkableInput.builder()
          .addArgs(new StringArg(getBuildTarget().toString()))
          .build();
    }

  }

}
