// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.ConstraintValueLookupUtil.InvalidConstraintValueException;
import com.google.devtools.build.lib.skyframe.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.ToolchainUtil.NoMatchingPlatformException;
import com.google.devtools.build.lib.skyframe.ToolchainUtil.UnresolvedToolchainsException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ToolchainUtil}. */
@RunWith(JUnit4.class)
public class ToolchainUtilTest extends ToolchainTestCase {

  /**
   * An {@link AnalysisMock} that injects {@link CreateToolchainContextFunction} into the Skyframe
   * executor.
   */
  private static final class AnalysisMockWithCreateToolchainContextFunction
      extends AnalysisMock.Delegate {
    AnalysisMockWithCreateToolchainContextFunction() {
      super(AnalysisMock.get());
    }

    @Override
    public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
        BlazeDirectories directories) {
      return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
          .putAll(super.getSkyFunctions(directories))
          .put(CREATE_TOOLCHAIN_CONTEXT_FUNCTION, new CreateToolchainContextFunction())
          .build();
    }
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    return new AnalysisMockWithCreateToolchainContextFunction();
  }

  @Test
  public void createToolchainContext() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, because platform
    // mac is listed first.
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContext toolchainContext = result.get(key).toolchainContext();
    assertThat(toolchainContext).isNotNull();

    assertThat(toolchainContext.requiredToolchainTypes()).containsExactly(testToolchainType);
    assertThat(toolchainContext.resolvedToolchainLabels())
        .containsExactly(Label.parseAbsoluteUnchecked("//extra:extra_toolchain_mac_impl"));

    assertThat(toolchainContext.executionPlatform()).isNotNull();
    assertThat(toolchainContext.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:mac"));

    assertThat(toolchainContext.targetPlatform()).isNotNull();
    assertThat(toolchainContext.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_noToolchainType() throws Exception {
    scratch.file("host/BUILD", "platform(name = 'host')");
    rewriteWorkspace("register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create("test", ImmutableSet.of(), targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContext toolchainContext = result.get(key).toolchainContext();
    assertThat(toolchainContext).isNotNull();

    assertThat(toolchainContext.requiredToolchainTypes()).isEmpty();

    // With no toolchains requested, should fall back to the host platform.
    assertThat(toolchainContext.executionPlatform()).isNotNull();
    assertThat(toolchainContext.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//host:host"));

    assertThat(toolchainContext.targetPlatform()).isNotNull();
    assertThat(toolchainContext.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_noToolchainType_hostNotAvailable() throws Exception {
    scratch.file("host/BUILD", "platform(name = 'host')");
    scratch.file(
        "sample/BUILD",
        "constraint_setting(name='demo')",
        "constraint_value(name = 'demo_a', constraint_setting=':demo')",
        "constraint_value(name = 'demo_b', constraint_setting=':demo')",
        "platform(name = 'sample_a',",
        "  constraint_values = [':demo_a'],",
        ")",
        "platform(name = 'sample_b',",
        "  constraint_values = [':demo_b'],",
        ")");
    rewriteWorkspace(
        "register_execution_platforms('//platforms:mac', '//platforms:linux',",
        "    '//sample:sample_a', '//sample:sample_b')");

    useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test",
            ImmutableSet.of(),
            ImmutableSet.of(Label.parseAbsoluteUnchecked("//sample:demo_b")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContext toolchainContext = result.get(key).toolchainContext();
    assertThat(toolchainContext).isNotNull();

    assertThat(toolchainContext.requiredToolchainTypes()).isEmpty();

    // With no toolchains requested, should fall back to the host platform.
    assertThat(toolchainContext.executionPlatform()).isNotNull();
    assertThat(toolchainContext.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//sample:sample_b"));

    assertThat(toolchainContext.targetPlatform()).isNotNull();
    assertThat(toolchainContext.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_unavailableToolchainType_single() throws Exception {
    useConfiguration(
        "--host_platform=//platforms:linux",
        "--platforms=//platforms:mac");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test",
            ImmutableSet.of(
                testToolchainType, Label.parseAbsoluteUnchecked("//fake/toolchain:type_1")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(UnresolvedToolchainsException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("no matching toolchains found for types //fake/toolchain:type_1");
  }

  @Test
  public void createToolchainContext_unavailableToolchainType_multiple() throws Exception {
    useConfiguration(
        "--host_platform=//platforms:linux",
        "--platforms=//platforms:mac");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test",
            ImmutableSet.of(
                testToolchainType,
                Label.parseAbsoluteUnchecked("//fake/toolchain:type_1"),
                Label.parseAbsoluteUnchecked("//fake/toolchain:type_2")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(UnresolvedToolchainsException.class);
    // Only one of the missing types will be reported, so do not check the specific error message.
  }

  @Test
  public void createToolchainContext_invalidTargetPlatform_badTarget() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--platforms=//invalid:not_a_platform");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "//invalid:not_a_platform was referenced as a platform, "
                + "but does not provide PlatformInfo");
  }

  @Test
  public void createToolchainContext_invalidTargetPlatform_badPackage() throws Exception {
    scratch.resolve("invalid").delete();
    useConfiguration("--platforms=//invalid:not_a_platform");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("BUILD file not found");
  }

  @Test
  public void createToolchainContext_invalidHostPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--host_platform=//invalid:not_a_platform");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void createToolchainContext_invalidExecutionPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--extra_execution_platforms=//invalid:not_a_platform");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void createToolchainContext_execConstraints() throws Exception {
    // This should select platform linux, toolchain extra_toolchain_linux, due to extra constraints,
    // even though platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test",
            ImmutableSet.of(testToolchainType),
            ImmutableSet.of(Label.parseAbsoluteUnchecked("//constraints:linux")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContext toolchainContext = result.get(key).toolchainContext();
    assertThat(toolchainContext).isNotNull();

    assertThat(toolchainContext.requiredToolchainTypes()).containsExactly(testToolchainType);
    assertThat(toolchainContext.resolvedToolchainLabels())
        .containsExactly(Label.parseAbsoluteUnchecked("//extra:extra_toolchain_linux_impl"));

    assertThat(toolchainContext.executionPlatform()).isNotNull();
    assertThat(toolchainContext.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));

    assertThat(toolchainContext.targetPlatform()).isNotNull();
    assertThat(toolchainContext.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_execConstraints_invalid() throws Exception {
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test",
            ImmutableSet.of(testToolchainType),
            ImmutableSet.of(Label.parseAbsoluteUnchecked("//platforms:linux")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidConstraintValueException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//platforms:linux");
  }

  @Test
  public void createToolchainContext_noMatchingPlatform() throws Exception {
    // Write toolchain A, and a toolchain implementing it.
    scratch.appendFile(
        "a/BUILD",
        "toolchain_type(name = 'toolchain_type_A')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type_A',",
        "    exec_compatible_with = ['//constraints:mac'],",
        "    target_compatible_with = [],",
        "    toolchain = ':toolchain_impl')",
        "filegroup(name='toolchain_impl')");
    // Write toolchain B, and a toolchain implementing it.
    scratch.appendFile(
        "b/BUILD",
        "load('//toolchain:toolchain_def.bzl', 'test_toolchain')",
        "toolchain_type(name = 'toolchain_type_B')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type_B',",
        "    exec_compatible_with = ['//constraints:linux'],",
        "    target_compatible_with = [],",
        "    toolchain = ':toolchain_impl')",
        "filegroup(name='toolchain_impl')");

    rewriteWorkspace(
        "register_toolchains('//a:toolchain', '//b:toolchain')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    CreateToolchainContextKey key =
        CreateToolchainContextKey.create(
            "test",
            ImmutableSet.of(
                Label.parseAbsoluteUnchecked("//a:toolchain_type_A"),
                Label.parseAbsoluteUnchecked("//b:toolchain_type_B")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextValue> result = createToolchainContext(key);
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(NoMatchingPlatformException.class);
  }

  // Calls ToolchainUtil.createToolchainContext.
  private static final SkyFunctionName CREATE_TOOLCHAIN_CONTEXT_FUNCTION =
      SkyFunctionName.createHermetic("CREATE_TOOLCHAIN_CONTEXT_FUNCTION");

  @AutoValue
  abstract static class CreateToolchainContextKey implements SkyKey {
    @Override
    public SkyFunctionName functionName() {
      return CREATE_TOOLCHAIN_CONTEXT_FUNCTION;
    }

    abstract String targetDescription();

    abstract ImmutableSet<Label> requiredToolchains();

    abstract ImmutableSet<Label> execConstraintLabels();

    abstract BuildConfigurationValue.Key configurationKey();

    public static CreateToolchainContextKey create(
        String targetDescription,
        Set<Label> requiredToolchains,
        BuildConfigurationValue.Key configurationKey) {
      return create(
          targetDescription,
          requiredToolchains,
          /* execConstraintLabels= */ ImmutableSet.of(),
          configurationKey);
    }

    public static CreateToolchainContextKey create(
        String targetDescription,
        Set<Label> requiredToolchains,
        Set<Label> execConstraintLabels,
        BuildConfigurationValue.Key configurationKey) {
      return new AutoValue_ToolchainUtilTest_CreateToolchainContextKey(
          targetDescription,
          ImmutableSet.copyOf(requiredToolchains),
          ImmutableSet.copyOf(execConstraintLabels),
          configurationKey);
    }
  }

  EvaluationResult<CreateToolchainContextValue> createToolchainContext(
      CreateToolchainContextKey key) throws InterruptedException {
    try {
      // Must re-enable analysis for Skyframe functions that create configured targets.
      skyframeExecutor.getSkyframeBuildView().enableAnalysis(true);
      return SkyframeExecutorTestUtils.evaluate(
          skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    } finally {
      skyframeExecutor.getSkyframeBuildView().enableAnalysis(false);
    }
  }

  // TODO(blaze-team): implement equals and hashcode for ToolchainContext and convert this to
  // autovalue.
  static class CreateToolchainContextValue implements SkyValue {
    private final ToolchainContext toolchainContext;

    private CreateToolchainContextValue(ToolchainContext toolchainContext) {
      this.toolchainContext = toolchainContext;
    }

    static CreateToolchainContextValue create(ToolchainContext toolchainContext) {
      return new CreateToolchainContextValue(toolchainContext);
    }

    ToolchainContext toolchainContext() {
      return toolchainContext;
    }
  }

  private static final class CreateToolchainContextFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      CreateToolchainContextKey key = (CreateToolchainContextKey) skyKey;
      ToolchainContext toolchainContext = null;
      try {
        toolchainContext =
            ToolchainUtil.createToolchainContext(
                env,
                key.targetDescription(),
                key.requiredToolchains(),
                key.execConstraintLabels(),
                key.configurationKey());
        if (toolchainContext == null) {
          return null;
        }
        return CreateToolchainContextValue.create(toolchainContext);
      } catch (ToolchainException e) {
        throw new CreateToolchainContextFunctionException(e);
      }
    }

    @Nullable
    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }

  private static class CreateToolchainContextFunctionException extends SkyFunctionException {
    public CreateToolchainContextFunctionException(ToolchainException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
