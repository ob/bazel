// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.android.desugar;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.commons.ClassRemapper;

/**
 * A visitor that renames packages so configured using {@link CoreLibrarySupport}..
 */
class CorePackageRenamer extends ClassRemapper {

  public CorePackageRenamer(ClassVisitor cv, CoreLibrarySupport support) {
    super(cv, support.getRemapper());
  }
}
