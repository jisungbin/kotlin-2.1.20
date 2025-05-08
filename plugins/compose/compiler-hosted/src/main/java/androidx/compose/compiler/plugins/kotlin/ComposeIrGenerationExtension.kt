/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.compiler.plugins.kotlin

import androidx.compose.compiler.plugins.kotlin.analysis.FqNameMatcher
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.k1.ComposeDescriptorSerializerContext
import androidx.compose.compiler.plugins.kotlin.lower.ClassStabilityTransformer
import androidx.compose.compiler.plugins.kotlin.lower.ComposableDefaultParamLowering
import androidx.compose.compiler.plugins.kotlin.lower.ComposableFunInterfaceLowering
import androidx.compose.compiler.plugins.kotlin.lower.ComposableFunctionBodyTransformer
import androidx.compose.compiler.plugins.kotlin.lower.ComposableLambdaAnnotator
import androidx.compose.compiler.plugins.kotlin.lower.ComposableTargetAnnotationsTransformer
import androidx.compose.compiler.plugins.kotlin.lower.ComposerIntrinsicTransformer
import androidx.compose.compiler.plugins.kotlin.lower.ComposerLambdaMemoization
import androidx.compose.compiler.plugins.kotlin.lower.ComposerParamTransformer
import androidx.compose.compiler.plugins.kotlin.lower.DurableFunctionKeyTransformer
import androidx.compose.compiler.plugins.kotlin.lower.DurableKeyVisitor
import androidx.compose.compiler.plugins.kotlin.lower.LiveLiteralTransformer
import org.jetbrains.kotlin.backend.common.IrValidatorConfig
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.validateIr
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.IrVerificationMode
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class ComposeIrGenerationExtension(
  private val liveLiteralsEnabled: Boolean = false,
  private val liveLiteralsV2Enabled: Boolean = false,
  private val generateFunctionKeyMetaAnnotations: Boolean = false,
  private val sourceInformationEnabled: Boolean = true,
  private val traceMarkersEnabled: Boolean = true,
  private val metricsDestination: String? = null,
  private val reportsDestination: String? = null,
  private val irVerificationMode: IrVerificationMode = IrVerificationMode.NONE,
  private val useK2: Boolean = false,
  private val stableTypeMatchers: Set<FqNameMatcher> = emptySet(),
  private val moduleMetricsFactory: ((StabilityInferencer, FeatureFlags) -> ModuleMetrics)? = null,
  @Suppress("unused") private val descriptorSerializerContext: ComposeDescriptorSerializerContext? = null, // always null in K2
  private val featureFlags: FeatureFlags,
  private val skipIfRuntimeNotFound: Boolean = false,
  private val messageCollector: MessageCollector,
) : IrGenerationExtension {
  var metrics: ModuleMetrics = EmptyModuleMetrics
    private set

  override fun generate(
    moduleFragment: IrModuleFragment,
    pluginContext: IrPluginContext,
  ) {
    if (VersionChecker(pluginContext, messageCollector).check(skipIfRuntimeNotFound) == VersionCheckerResult.NOT_FOUND) {
      return
    }

    val stabilityInferencer = StabilityInferencer(
      currentModule = pluginContext.moduleDescriptor,
      externalStableTypeMatchers = stableTypeMatchers,
    )

    val irValidatorConfig = IrValidatorConfig(
      checkProperties = true,
      checkTypes = false, // TODO: Re-enable checking types (KT-68663)
    )

    // Input check. This should always pass, else something is horribly wrong upstream.
    // Necessary because oftentimes the issue is upstream (compiler bug, prior plugin, etc)
    validateIr(messageCollector, irVerificationMode) {
      performBasicIrValidation(
        moduleFragment,
        pluginContext.irBuiltIns,
        phaseName = "Before Compose Compiler Plugin",
        irValidatorConfig,
      )
    }

    moduleFragment.acceptVoid(ComposableLambdaAnnotator(pluginContext))

    if (moduleMetricsFactory != null) {
      metrics = moduleMetricsFactory.invoke(stabilityInferencer, featureFlags)
    } else if (metricsDestination != null || reportsDestination != null) {
      metrics = ModuleMetricsImpl(moduleFragment.name.asString(), featureFlags) {
        stabilityInferencer.stabilityOfType(it)
      }
    }

    ClassStabilityTransformer(
      useK2 = useK2,
      context = pluginContext,
      metrics = metrics,
      stabilityInferencer = stabilityInferencer,
      classStabilityInferredCollection = null, // always null in K2 or non-JVM
      featureFlags = featureFlags,
      messageCollector = messageCollector
    ).lower(moduleFragment)

    if (liveLiteralsEnabled || liveLiteralsV2Enabled) {
      LiveLiteralTransformer(
        liveLiteralsEnabled = true,
        usePerFileEnabledFlag = liveLiteralsV2Enabled,
        keyVisitor = DurableKeyVisitor(),
        context = pluginContext,
        metrics = metrics,
        stabilityInferencer = stabilityInferencer,
        featureFlags = featureFlags,
      ).lower(moduleFragment)
    }

    ComposableFunInterfaceLowering(pluginContext).lower(moduleFragment)

    val functionKeyTransformer = DurableFunctionKeyTransformer(
      context = pluginContext,
      metrics = metrics,
      stabilityInferencer = stabilityInferencer,
      featureFlags = featureFlags,
    )
    functionKeyTransformer.lower(moduleFragment)

    // Generate default wrappers for virtual functions
    ComposableDefaultParamLowering(
      context = pluginContext,
      metrics = metrics,
      stabilityInferencer = stabilityInferencer,
      featureFlags = featureFlags
    ).lower(moduleFragment)

    // Memoize normal lambdas and wrap composable lambdas
    ComposerLambdaMemoization(
      context = pluginContext,
      metrics = metrics,
      stabilityInferencer = stabilityInferencer,
      featureFlags = featureFlags,
    ).lower(moduleFragment)

    // Transform all composable functions to have an extra synthetic composer parameter.
    // This will also transform all types and calls to include the extra parameter.
    //
    // 모든 컴포저블 함수를 변환하여 추가적인 `composer` 매개변수를 갖도록 합니다.
    // 이렇게 하면 모든 타입과 호출도 추가 매개변수를 포함하도록 변환됩니다.
    ComposerParamTransformer(
      context = pluginContext,
      stabilityInferencer = stabilityInferencer,
      metrics = metrics,
      featureFlags = featureFlags,
    ).lower(moduleFragment)

    ComposableTargetAnnotationsTransformer(
      context = pluginContext,
      metrics = metrics,
      stabilityInferencer = stabilityInferencer,
      featureFlags = featureFlags,
    ).lower(moduleFragment)

    // transform calls to the currentComposer to just use the local parameter from the
    // previous transform
    ComposerIntrinsicTransformer(pluginContext).lower(moduleFragment)

    ComposableFunctionBodyTransformer(
      pluginContext,
      metrics,
      stabilityInferencer,
      sourceInformationEnabled,
      traceMarkersEnabled,
      featureFlags,
    ).lower(moduleFragment)

    if (generateFunctionKeyMetaAnnotations) {
      functionKeyTransformer.realizeKeyMetaAnnotations(moduleFragment)
    }

    if (metricsDestination != null) {
      metrics.saveMetricsTo(metricsDestination)
    }
    if (reportsDestination != null) {
      metrics.saveReportsTo(reportsDestination)
    }

    // Verify that our transformations didn't break something
    validateIr(messageCollector, irVerificationMode) {
      performBasicIrValidation(
        moduleFragment,
        pluginContext.irBuiltIns,
        phaseName = "After Compose Compiler Plugin",
        irValidatorConfig,
      )
    }
  }
}
