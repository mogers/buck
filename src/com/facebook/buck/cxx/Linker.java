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

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * An object wrapping a linker, providing its source path and an interface to decorate
 * arguments with specific flags.
 */
public interface Linker extends Tool {

  ImmutableList<FileScrubber> getScrubbers(ImmutableCollection<Path> cellRoots);

  /**
   * @return the platform-specific way to specify that the library represented by the
   *     given argument should be linked whole.
   */
  Iterable<Arg> linkWhole(Arg input);

  /**
   * @return the platform-specific way to specify that linker should use the given soname
   *     when linking a shared library.
   */
  Iterable<String> soname(String soname);

  /**
   * @return the placeholder used by the dynamic loader for the directory containing the top-level
   *     executable.
   */
  String origin();

  /**
   * @return the placeholder used by the dynamic loader for the directory containing the top-level
   *     library (useful for e.g. plugins).
   */
  String libOrigin();

  /**
   * @return the name of the environment variable for the shared library runtime search path.
   */
  String searchPathEnvVar();

  /**
   * Generate a necessary linker arguments to propagate undefined symbols to a link command.  May
   * need to create a {@link BuildRule}, in which case, {@code target} will be used as its name.
   *
   * @param target the name to give any {@link BuildRule} that needs to be created to facilitate
   *               generating the arguments,
   * @param symbolFiles the symbols files, each listing undefined symbols, one per line, to add to
   *                    the link.
   * @return the list of linker arguments needed to propagate the list of undefined symbols to the
   *         link command.
   */
  ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildTarget target,
      Iterable<? extends SourcePath> symbolFiles);

  /**
   * The various ways to link an output file.
   */
  enum LinkType {

    // Link as standalone executable.
    EXECUTABLE,

    // Link as shared library, which can be loaded into a process image.
    SHARED,

    // Mach-O only: Link as a bundle, which can be loaded into a process image and
    // use that image's symbols.
    MACH_O_BUNDLE,

  }

  /**
   * The various ways to link in dependencies.
   */
  enum LinkableDepType {

    // Provide input suitable for statically linking this linkable (e.g. return references to
    // static libraries, libfoo.a).
    STATIC,

    // Provide input suitable for statically linking this linkable using PIC-enabled binaries
    // (e.g. return references to static libraries, libfoo_pic.a).
    STATIC_PIC,

    // Provide input suitable for dynamically linking this linkable (e.g. return references to
    // shared libraries, libfoo.so).
    SHARED

  }

  /**
   * The various styles of runtime library to which we can link shared objects.  In some cases, it's
   * useful to link against a static version of the usual dynamic support library.
   */
  enum CxxRuntimeType {
    // Link in the C++ runtime library dynamically
    DYNAMIC,

    // Link in the C++ runtime statically
    STATIC,

  }
}
