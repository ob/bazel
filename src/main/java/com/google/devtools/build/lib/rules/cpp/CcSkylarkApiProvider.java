// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.skylark.SkylarkApiProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * A class that exposes the C++ providers to Skylark. It is intended to provide a simple and stable
 * interface for Skylark users.
 */
@SkylarkModule(
  name = "CcSkylarkApiProvider",
  category = SkylarkModuleCategory.PROVIDER,
  doc =
      "Provides access to information about C++ rules.  "
          + "Every C++-related target provides this struct, accessible as a <code>cc</code> field "
          + "on <a href=\"Target.html\">target</a>."
)
@AutoCodec
public final class CcSkylarkApiProvider extends SkylarkApiProvider {
  /** The name of the field in Skylark used to access this class. */
  public static final String NAME = "cc";

  @SkylarkCallable(
      name = "transitive_headers",
      structField = true,
      doc =
          "Returns a <a href=\"depset.html\">depset</a> of headers that have been declared in the "
              + " <code>src</code> or <code>headers</code> attribute"
              + "(possibly empty but never <code>None</code>).")
  public NestedSet<Artifact> getTransitiveHeaders() {
    CcCompilationContext ccCompilationContext =
        getInfo().get(CcCompilationInfo.PROVIDER).getCcCompilationContext();
    return ccCompilationContext.getDeclaredIncludeSrcs();
  }

  @SkylarkCallable(
      name = "libs",
      structField = true,
      doc =
          "Returns the <a href=\"depset.html\">depset</a> of libraries for either "
              + "<code>FULLY STATIC</code> mode (<code>linkopts=[\"-static\"]</code>) or "
              + "<code>MOSTLY STATIC</code> mode (<code>linkstatic=1</code>) "
              + "(possibly empty but never <code>None</code>)")
  public NestedSet<Artifact> getLibraries() {
    NestedSetBuilder<Artifact> libs = NestedSetBuilder.linkOrder();
    CcLinkingInfo ccLinkingInfo = getInfo().get(CcLinkingInfo.PROVIDER);
    CcLinkParamsStore ccLinkParams =
        ccLinkingInfo == null ? null : ccLinkingInfo.getCcLinkParamsStore();
    if (ccLinkParams == null) {
      return libs.build();
    }
    for (LinkerInput lib : ccLinkParams.getCcLinkParams(true, false).getLibraries()) {
      libs.add(lib.getArtifact());
    }
    return libs.build();
  }

  @SkylarkCallable(
      name = "link_flags",
      structField = true,
      doc =
          "Returns the list of flags given to the C++ linker command for either "
              + "<code>FULLY STATIC</code> mode (<code>linkopts=[\"-static\"]</code>) or "
              + "<code>MOSTLY STATIC</code> mode (<code>linkstatic=1</code>) "
              + "(possibly empty but never <code>None</code>)")
  public ImmutableList<String> getLinkopts() {
    CcLinkingInfo ccLinkingInfo = getInfo().get(CcLinkingInfo.PROVIDER);
    CcLinkParamsStore ccLinkParams =
        ccLinkingInfo == null ? null : ccLinkingInfo.getCcLinkParamsStore();
    if (ccLinkParams == null) {
      return ImmutableList.of();
    }
    return ccLinkParams.getCcLinkParams(true, false).flattenedLinkopts();
  }

  @SkylarkCallable(
      name = "defines",
      structField = true,
      doc =
          "Returns the list of defines used to compile this target "
              + "(possibly empty but never <code>None</code>).")
  public ImmutableList<String> getDefines() {
    CcCompilationContext ccCompilationContext =
        getInfo().get(CcCompilationInfo.PROVIDER).getCcCompilationContext();
    return ccCompilationContext == null
        ? ImmutableList.<String>of()
        : ccCompilationContext.getDefines();
  }

  @SkylarkCallable(
      name = "system_include_directories",
      structField = true,
      doc =
          "Returns the list of system include directories used to compile this target "
              + "(possibly empty but never <code>None</code>).")
  public ImmutableList<String> getSystemIncludeDirs() {
    CcCompilationContext ccCompilationContext =
        getInfo().get(CcCompilationInfo.PROVIDER).getCcCompilationContext();
    if (ccCompilationContext == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (PathFragment path : ccCompilationContext.getSystemIncludeDirs()) {
      builder.add(path.getSafePathString());
    }
    return builder.build();
  }

  @SkylarkCallable(
      name = "include_directories",
      structField = true,
      doc =
          "Returns the list of include directories used to compile this target "
              + "(possibly empty but never <code>None</code>).")
  public ImmutableList<String> getIncludeDirs() {
    CcCompilationContext ccCompilationContext =
        getInfo().get(CcCompilationInfo.PROVIDER).getCcCompilationContext();
    if (ccCompilationContext == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (PathFragment path : ccCompilationContext.getIncludeDirs()) {
      builder.add(path.getSafePathString());
    }
    return builder.build();
  }

  @SkylarkCallable(
      name = "quote_include_directories",
      structField = true,
      doc =
          "Returns the list of quote include directories used to compile this target "
              + "(possibly empty but never <code>None</code>).")
  public ImmutableList<String> getQuoteIncludeDirs() {
    CcCompilationContext ccCompilationContext =
        getInfo().get(CcCompilationInfo.PROVIDER).getCcCompilationContext();
    if (ccCompilationContext == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (PathFragment path : ccCompilationContext.getQuoteIncludeDirs()) {
      builder.add(path.getSafePathString());
    }
    return builder.build();
  }

  @SkylarkCallable(
      name = "compile_flags",
      structField = true,
      doc =
          "Returns the list of flags used to compile this target "
              + "(possibly empty but never <code>None</code>).")
  public ImmutableList<String> getCcFlags() {
    CcCompilationContext ccCompilationContext =
        getInfo().get(CcCompilationInfo.PROVIDER).getCcCompilationContext();

    ImmutableList.Builder<String> options = ImmutableList.builder();
    for (String define : ccCompilationContext.getDefines()) {
      options.add("-D" + define);
    }
    for (PathFragment path : ccCompilationContext.getSystemIncludeDirs()) {
      options.add("-isystem " + path.getSafePathString());
    }
    for (PathFragment path : ccCompilationContext.getIncludeDirs()) {
      options.add("-I " + path.getSafePathString());
    }
    for (PathFragment path : ccCompilationContext.getQuoteIncludeDirs()) {
      options.add("-iquote " + path.getSafePathString());
    }

    return options.build();
  }
}
