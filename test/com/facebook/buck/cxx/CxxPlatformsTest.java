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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * Unit tests for {@link CxxPlatforms}.
 */
public class CxxPlatformsTest {
  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Test
  public void returnsKnownDefaultPlatformSetInConfig() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    CxxPlatform borlandCxx452Platform =
      CxxPlatform.builder()
          .setFlavor(ImmutableFlavor.of("borland_cxx_452"))
          .setAs(new HashedFileTool(Paths.get("borland")))
          .setAspp(new DefaultPreprocessor(new HashedFileTool(Paths.get("borland"))))
          .setCc(new DefaultCompiler(new HashedFileTool(Paths.get("borland"))))
          .setCpp(new DefaultPreprocessor(new HashedFileTool(Paths.get("borland"))))
          .setCxx(new DefaultCompiler(new HashedFileTool(Paths.get("borland"))))
          .setCxxpp(new DefaultPreprocessor(new HashedFileTool(Paths.get("borland"))))
          .setLd(new GnuLinker(new HashedFileTool(Paths.get("borland"))))
          .setStrip(new HashedFileTool(Paths.get("borland")))
          .setSymbolNameTool(new PosixNmSymbolNameTool(new HashedFileTool(Paths.get("borland"))))
          .setAr(new GnuArchiver(new HashedFileTool(Paths.get("borland"))))
          .setRanlib(new HashedFileTool(Paths.get("borland")))
          .setSharedLibraryExtension(".so")
          .setSharedLibraryVersionedExtensionFormat(".so.%s")
          .setDebugPathSanitizer(CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER)
          .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig),
            ImmutableMap.of(borlandCxx452Platform.getFlavor(), borlandCxx452Platform),
            CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(
            borlandCxx452Platform));
  }

  @Test
  public void unknownDefaultPlatformSetInConfigFallsBackToSystemDefault() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of("default_platform", "borland_cxx_452"));
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(sections).build();
    assertThat(
        CxxPlatforms.getConfigDefaultCxxPlatform(
            new CxxBuckConfig(buckConfig),
            ImmutableMap.<Flavor, CxxPlatform>of(),
            CxxPlatformUtils.DEFAULT_PLATFORM),
        equalTo(
            CxxPlatformUtils.DEFAULT_PLATFORM));
  }

  @Test
  public void combinesPreprocessAndCompileFlagsIsDefault() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "cflags", "-Wtest",
            "cxxflags", "-Wexample",
            "cppflags", "-Wp",
            "cxxppflags", "-Wxp"));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(FakeBuckConfig.builder().setSections(sections).build());

    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    assertThat(
        platform.getCflags(),
        hasItem("-Wtest"));
    assertThat(
        platform.getCxxflags(),
        hasItem("-Wexample"));
    assertThat(
        platform.getCppflags(),
        hasItems("-Wtest", "-Wp"));
    assertThat(
        platform.getCxxppflags(),
        hasItems("-Wexample", "-Wxp"));
  }

  @Test
  public void compilerOnlyFlagsNotAddedToPreprocessor() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "compiler_only_flags", "-Wtest",
            "cppflags", "-Wp",
            "cxxppflags", "-Wxp"));

    CxxBuckConfig buckConfig =
        new CxxBuckConfig(FakeBuckConfig.builder().setSections(sections).build());

    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    assertThat(
        platform.getCflags(),
        hasItem("-Wtest"));
    assertThat(
        platform.getCxxflags(),
        hasItem("-Wtest"));
    assertThat(
        platform.getCppflags(),
        hasItem("-Wp"));
    assertThat(
        platform.getCppflags(),
        not(hasItem("-Wtest")));
    assertThat(
        platform.getCxxppflags(),
        hasItem("-Wxp"));
    assertThat(
        platform.getCxxppflags(),
        not(hasItem("-Wtest")));
  }

  public Linker getPlatformLinker(Platform linkerPlatform) {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "ld", Paths.get("fake_path").toString(),
            "linker_platform", linkerPlatform.name()));

    CxxBuckConfig buckConfig = new CxxBuckConfig(
        FakeBuckConfig.builder()
            .setSections(sections)
            .setFilesystem(
                new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
            .build());

    return DefaultCxxPlatforms.build(buckConfig).getLd();
  }

  @Test
  public void linkerOverriddenByConfig() {
    assertThat("MACOS linker was not a DarwinLinker instance",
        getPlatformLinker(Platform.MACOS), instanceOf(DarwinLinker.class));
    assertThat("LINUX linker was not a GnuLinker instance",
        getPlatformLinker(Platform.LINUX), instanceOf(GnuLinker.class));
    assertThat("WINDOWS linker was not a GnuLinker instance",
        getPlatformLinker(Platform.WINDOWS), instanceOf(GnuLinker.class));
    assertThat("UNKNOWN linker was not a UnknownLinker instance",
        getPlatformLinker(Platform.UNKNOWN), instanceOf(UnknownLinker.class));
  }

  @Test
  public void invalidLinkerOverrideFails() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "ld", Paths.get("fake_path").toString(),
            "linker_platform", "WRONG_PLATFORM"));

    CxxBuckConfig buckConfig = new CxxBuckConfig(
        FakeBuckConfig.builder()
            .setSections(sections)
            .setFilesystem(new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
            .build());

    expectedException.expect(RuntimeException.class);
    DefaultCxxPlatforms.build(buckConfig);
  }

  public Archiver getPlatformArchiver(Platform archiverPlatform) {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
        "cxx", ImmutableMap.of(
            "ar", Paths.get("fake_path").toString(),
            "archiver_platform", archiverPlatform.name()));

    CxxBuckConfig buckConfig = new CxxBuckConfig(
        FakeBuckConfig.builder()
            .setSections(sections)
            .setFilesystem(new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
            .build());

    return DefaultCxxPlatforms.build(buckConfig).getAr();
  }

  @Test
  public void archiverrOverriddenByConfig() {
    assertThat("MACOS archiver was not a BsdArchiver instance",
        getPlatformArchiver(Platform.MACOS), instanceOf(BsdArchiver.class));
    assertThat("LINUX archiver was not a GnuArchiver instance",
        getPlatformArchiver(Platform.LINUX), instanceOf(GnuArchiver.class));
    assertThat("WINDOWS archiver was not a GnuArchiver instance",
        getPlatformArchiver(Platform.WINDOWS), instanceOf(GnuArchiver.class));
    assertThat("UNKNOWN archiver was not a UnknownArchiver instance",
        getPlatformArchiver(Platform.UNKNOWN), instanceOf(UnknownArchiver.class));
  }

  @Test
  public void invalidArchiverOverrideFails() {
    ImmutableMap<String, ImmutableMap<String, String>> sections = ImmutableMap.of(
      "cxx", ImmutableMap.of(
            "ar", Paths.get("fake_path").toString(),
            "archiver_platform", "WRONG_PLATFORM"));

    CxxBuckConfig buckConfig = new CxxBuckConfig(
        FakeBuckConfig.builder()
            .setSections(sections)
            .setFilesystem(new FakeProjectFilesystem(ImmutableSet.of(Paths.get("fake_path"))))
            .build());

    expectedException.expect(RuntimeException.class);
    DefaultCxxPlatforms.build(buckConfig);
  }
}
