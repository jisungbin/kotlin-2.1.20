/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm

import org.jetbrains.kotlin.backend.common.ir.Symbols.Companion.isTypeOfIntrinsic
import org.jetbrains.kotlin.backend.common.ir.isReifiable
import org.jetbrains.kotlin.backend.common.lower.ArrayConstructorLowering
import org.jetbrains.kotlin.backend.common.lower.ConstEvaluationLowering
import org.jetbrains.kotlin.backend.common.lower.DefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterCleaner
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterInjector
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterPatchOverridenSymbolsLowering
import org.jetbrains.kotlin.backend.common.lower.EnumWhenLowering
import org.jetbrains.kotlin.backend.common.lower.ExpectDeclarationsRemoveLowering
import org.jetbrains.kotlin.backend.common.lower.ExpressionBodyTransformer
import org.jetbrains.kotlin.backend.common.lower.InitializersCleanupLowering
import org.jetbrains.kotlin.backend.common.lower.InitializersLowering
import org.jetbrains.kotlin.backend.common.lower.InlineClassLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesMemberBodyLowering
import org.jetbrains.kotlin.backend.common.lower.LateinitLowering
import org.jetbrains.kotlin.backend.common.lower.LocalClassPopupLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDelegatedPropertiesLowering
import org.jetbrains.kotlin.backend.common.lower.MaskedDefaultArgumentFunctionFactory
import org.jetbrains.kotlin.backend.common.lower.PropertiesLowering
import org.jetbrains.kotlin.backend.common.lower.RangeContainsLowering
import org.jetbrains.kotlin.backend.common.lower.SharedVariablesLowering
import org.jetbrains.kotlin.backend.common.lower.StringConcatenationLowering
import org.jetbrains.kotlin.backend.common.lower.TailrecLowering
import org.jetbrains.kotlin.backend.common.lower.WrapInlineDeclarationsWithReifiedTypeParametersLowering
import org.jetbrains.kotlin.backend.common.lower.coroutines.AddContinuationToNonLocalSuspendFunctionsLowering
import org.jetbrains.kotlin.backend.common.lower.inline.LocalClassesInInlineFunctionsLowering
import org.jetbrains.kotlin.backend.common.lower.inline.LocalClassesInInlineLambdasLowering
import org.jetbrains.kotlin.backend.common.lower.inline.OuterThisInInlineFunctionsSpecialAccessorLowering
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.lower.optimizations.PropertyAccessorInlineLowering
import org.jetbrains.kotlin.backend.common.phaser.DEFAULT_IR_ACTIONS
import org.jetbrains.kotlin.backend.common.phaser.IrValidationAfterInliningAllFunctionsPhase
import org.jetbrains.kotlin.backend.common.phaser.IrValidationAfterInliningOnlyPrivateFunctionsPhase
import org.jetbrains.kotlin.backend.common.phaser.IrValidationAfterLoweringPhase
import org.jetbrains.kotlin.backend.common.phaser.IrValidationBeforeLoweringPhase
import org.jetbrains.kotlin.backend.common.phaser.makeIrModulePhase
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.backend.wasm.lower.AssociatedObjectsLowering
import org.jetbrains.kotlin.backend.wasm.lower.BuiltInsLowering
import org.jetbrains.kotlin.backend.wasm.lower.ComplexExternalDeclarationsToTopLevelFunctionsLowering
import org.jetbrains.kotlin.backend.wasm.lower.ComplexExternalDeclarationsUsageLowering
import org.jetbrains.kotlin.backend.wasm.lower.EraseVirtualDispatchReceiverParametersTypes
import org.jetbrains.kotlin.backend.wasm.lower.ExcludeDeclarationsFromCodegen
import org.jetbrains.kotlin.backend.wasm.lower.ExplicitlyCastExternalTypesLowering
import org.jetbrains.kotlin.backend.wasm.lower.GenerateMainFunctionWrappers
import org.jetbrains.kotlin.backend.wasm.lower.GenerateWasmTests
import org.jetbrains.kotlin.backend.wasm.lower.GenericReturnTypeLowering
import org.jetbrains.kotlin.backend.wasm.lower.InvokeOnExportedFunctionExitLowering
import org.jetbrains.kotlin.backend.wasm.lower.JsCodeCallsLowering
import org.jetbrains.kotlin.backend.wasm.lower.JsInteropFunctionsLowering
import org.jetbrains.kotlin.backend.wasm.lower.TryCatchCanonicalization
import org.jetbrains.kotlin.backend.wasm.lower.UnhandledExceptionLowering
import org.jetbrains.kotlin.backend.wasm.lower.UnitToVoidLowering
import org.jetbrains.kotlin.backend.wasm.lower.VirtualDispatchReceiverExtraction
import org.jetbrains.kotlin.backend.wasm.lower.WasmBridgesConstruction
import org.jetbrains.kotlin.backend.wasm.lower.WasmClassReferenceLowering
import org.jetbrains.kotlin.backend.wasm.lower.WasmFunctionInlining
import org.jetbrains.kotlin.backend.wasm.lower.WasmInlineCallableReferenceToLambdaPhase
import org.jetbrains.kotlin.backend.wasm.lower.WasmInlineFunctionResolver
import org.jetbrains.kotlin.backend.wasm.lower.WasmPropertyReferenceLowering
import org.jetbrains.kotlin.backend.wasm.lower.WasmStaticCallableReferenceLowering
import org.jetbrains.kotlin.backend.wasm.lower.WasmStringSwitchOptimizerLowering
import org.jetbrains.kotlin.backend.wasm.lower.WasmTypeOperatorLowering
import org.jetbrains.kotlin.backend.wasm.lower.WasmVarargExpressionLowering
import org.jetbrains.kotlin.backend.wasm.lower.WhenBranchOptimiserLowering
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.KlibConfigurationKeys
import org.jetbrains.kotlin.config.phaser.CompilerPhase
import org.jetbrains.kotlin.config.phaser.SameTypeNamedCompilerPhase
import org.jetbrains.kotlin.config.phaser.SimpleNamedCompilerPhase
import org.jetbrains.kotlin.ir.backend.js.lower.AutoboxingTransformer
import org.jetbrains.kotlin.ir.backend.js.lower.CallableReferenceLowering
import org.jetbrains.kotlin.ir.backend.js.lower.DelegateToSyntheticPrimaryConstructor
import org.jetbrains.kotlin.ir.backend.js.lower.EnumClassConstructorBodyTransformer
import org.jetbrains.kotlin.ir.backend.js.lower.EnumClassConstructorLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumClassCreateInitializerLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumClassRemoveEntriesLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumEntryCreateGetInstancesFunsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumEntryInstancesBodyLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumEntryInstancesLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumSyntheticFunctionsAndPropertiesLowering
import org.jetbrains.kotlin.ir.backend.js.lower.EnumUsageLowering
import org.jetbrains.kotlin.ir.backend.js.lower.InlineObjectsWithPureInitializationLowering
import org.jetbrains.kotlin.ir.backend.js.lower.InvokeStaticInitializersLowering
import org.jetbrains.kotlin.ir.backend.js.lower.JsSingleAbstractMethodLowering
import org.jetbrains.kotlin.ir.backend.js.lower.ObjectDeclarationLowering
import org.jetbrains.kotlin.ir.backend.js.lower.ObjectUsageLowering
import org.jetbrains.kotlin.ir.backend.js.lower.PrimaryConstructorLowering
import org.jetbrains.kotlin.ir.backend.js.lower.PropertyLazyInitLowering
import org.jetbrains.kotlin.ir.backend.js.lower.PurifyObjectInstanceGettersLowering
import org.jetbrains.kotlin.ir.backend.js.lower.RemoveInitializersForLazyProperties
import org.jetbrains.kotlin.ir.backend.js.lower.StaticMembersLowering
import org.jetbrains.kotlin.ir.backend.js.lower.coroutines.AddContinuationToFunctionCallsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.coroutines.JsSuspendFunctionsLowering
import org.jetbrains.kotlin.ir.backend.js.lower.inline.RemoveInlineDeclarationsWithReifiedTypeParametersLowering
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.inline.DumpSyntheticAccessors
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineMode
import org.jetbrains.kotlin.ir.inline.SyntheticAccessorLowering
import org.jetbrains.kotlin.ir.inline.isConsideredAsPrivateForInlining
import org.jetbrains.kotlin.ir.interpreter.IrInterpreterConfiguration
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import org.jetbrains.kotlin.utils.bind
import org.jetbrains.kotlin.wasm.config.WasmConfigurationKeys

private fun List<CompilerPhase<WasmBackendContext, IrModuleFragment, IrModuleFragment>>.toCompilerPhase() =
  reduce { acc, lowering -> acc.then(lowering) }

private val validateIrBeforeLowering = makeIrModulePhase(
  ::IrValidationBeforeLoweringPhase,
  name = "ValidateIrBeforeLowering",
)

private val validateIrAfterInliningOnlyPrivateFunctionsPhase = makeIrModulePhase(
  { context: WasmBackendContext ->
    IrValidationAfterInliningOnlyPrivateFunctionsPhase(
      context,
      checkInlineFunctionCallSites = { inlineFunctionUseSite ->
        val inlineFunction = inlineFunctionUseSite.symbol.owner
        when {
          // TODO: remove this condition after the fix of KT-69457:
          inlineFunctionUseSite is IrFunctionReference && !inlineFunction.isReifiable() -> true // temporarily permitted

          // Call sites of only non-private functions are allowed at this stage.
          else -> !inlineFunction.isConsideredAsPrivateForInlining()
        }
      }
    )
  },
  name = "IrValidationAfterInliningOnlyPrivateFunctionsPhase",
)

private val validateIrAfterInliningAllFunctionsPhase = makeIrModulePhase(
  { context: WasmBackendContext ->
    IrValidationAfterInliningAllFunctionsPhase(
      context,
      checkInlineFunctionCallSites = { inlineFunctionUseSite ->
        // No inline function call sites should remain at this stage.
        val inlineFunction = inlineFunctionUseSite.symbol.owner
        when {
          // TODO: remove this condition after the fix of KT-69457:
          inlineFunctionUseSite is IrFunctionReference && !inlineFunction.isReifiable() -> true // temporarily permitted

          // TODO: remove this condition after the fix of KT-70361:
          isTypeOfIntrinsic(inlineFunction.symbol) -> true // temporarily permitted

          else -> false // forbidden
        }
      }
    )
  },
  name = "IrValidationAfterInliningAllFunctionsPhase",
)

private val dumpSyntheticAccessorsPhase = makeIrModulePhase<WasmBackendContext>(
  ::DumpSyntheticAccessors,
  name = "DumpSyntheticAccessorsPhase",
)

private val validateIrAfterLowering = makeIrModulePhase(
  ::IrValidationAfterLoweringPhase,
  name = "ValidateIrAfterLowering",
)

private val generateTests = makeIrModulePhase(
  ::GenerateWasmTests,
  name = "GenerateTests",
)

private val expectDeclarationsRemovingPhase = makeIrModulePhase(
  ::ExpectDeclarationsRemoveLowering,
  name = "ExpectDeclarationsRemoving",
)

private val stringConcatenationLowering = makeIrModulePhase(
  ::StringConcatenationLowering,
  name = "StringConcatenation",
)

private val lateinitPhase = makeIrModulePhase(
  ::LateinitLowering,
  name = "LateinitLowering",
)

private val rangeContainsLoweringPhase = makeIrModulePhase(
  ::RangeContainsLowering,
  name = "RangeContainsLowering",
)

private val inlineCallableReferenceToLambdaPhase = makeIrModulePhase(
  ::WasmInlineCallableReferenceToLambdaPhase,
  name = "WasmInlineCallableReferenceToLambdaPhase",
)


private val wrapInlineDeclarationsWithReifiedTypeParametersLowering = makeIrModulePhase(
  ::WrapInlineDeclarationsWithReifiedTypeParametersLowering,
  name = "WrapInlineDeclarationsWithReifiedTypeParametersLowering",
)

private val arrayConstructorPhase = makeIrModulePhase(
  ::ArrayConstructorLowering,
  name = "ArrayConstructor",
  prerequisite = setOf(inlineCallableReferenceToLambdaPhase)
)

private val sharedVariablesLoweringPhase = makeIrModulePhase(
  ::SharedVariablesLowering,
  name = "SharedVariablesLowering",
  prerequisite = setOf(lateinitPhase)
)

private val localClassesInInlineLambdasPhase = makeIrModulePhase(
  ::LocalClassesInInlineLambdasLowering,
  name = "LocalClassesInInlineLambdasPhase",
)

private val localClassesInInlineFunctionsPhase = makeIrModulePhase(
  ::LocalClassesInInlineFunctionsLowering,
  name = "LocalClassesInInlineFunctionsPhase",
)

private val outerThisSpecialAccessorInInlineFunctionsPhase = makeIrModulePhase(
  ::OuterThisInInlineFunctionsSpecialAccessorLowering,
  name = "OuterThisInInlineFunctionsSpecialAccessorLowering",
)

/**
 * The first phase of inlining (inline only private functions).
 */
private val inlineOnlyPrivateFunctionsPhase = makeIrModulePhase(
  ::WasmFunctionInlining.bind(InlineMode.PRIVATE_INLINE_FUNCTIONS),
  name = "InlineOnlyPrivateFunctions",
  prerequisite = setOf(outerThisSpecialAccessorInInlineFunctionsPhase)
)

internal val syntheticAccessorGenerationPhase = makeIrModulePhase(
  lowering = ::SyntheticAccessorLowering,
  name = "SyntheticAccessorGeneration",
  prerequisite = setOf(inlineOnlyPrivateFunctionsPhase),
)

private val inlineAllFunctionsPhase = makeIrModulePhase(
  { context: WasmBackendContext ->
    FunctionInlining(
      context,
      WasmInlineFunctionResolver(context, inlineMode = InlineMode.ALL_INLINE_FUNCTIONS),
      produceOuterThisFields = false,
    )
  },
  name = "InlineAllFunctions",
  prerequisite = setOf(outerThisSpecialAccessorInInlineFunctionsPhase)
)

private val removeInlineDeclarationsWithReifiedTypeParametersLoweringPhase = makeIrModulePhase(
  { RemoveInlineDeclarationsWithReifiedTypeParametersLowering() },
  name = "RemoveInlineFunctionsWithReifiedTypeParametersLowering",
  prerequisite = setOf(inlineAllFunctionsPhase)
)

private val tailrecLoweringPhase = makeIrModulePhase(
  ::TailrecLowering,
  name = "TailrecLowering",
)

private val wasmStringSwitchOptimizerLowering = makeIrModulePhase(
  ::WasmStringSwitchOptimizerLowering,
  name = "WasmStringSwitchOptimizerLowering",
)

private val jsCodeCallsLowering = makeIrModulePhase(
  ::JsCodeCallsLowering,
  name = "JsCodeCallsLowering",
)

private val complexExternalDeclarationsToTopLevelFunctionsLowering = makeIrModulePhase(
  ::ComplexExternalDeclarationsToTopLevelFunctionsLowering,
  name = "ComplexExternalDeclarationsToTopLevelFunctionsLowering",
)

private val complexExternalDeclarationsUsagesLowering = makeIrModulePhase(
  ::ComplexExternalDeclarationsUsageLowering,
  name = "ComplexExternalDeclarationsUsageLowering",
)

private val jsInteropFunctionsLowering = makeIrModulePhase(
  ::JsInteropFunctionsLowering,
  name = "JsInteropFunctionsLowering",
)

private val enumWhenPhase = makeIrModulePhase(
  ::EnumWhenLowering,
  name = "EnumWhenLowering",
)

private val enumClassConstructorLoweringPhase = makeIrModulePhase(
  ::EnumClassConstructorLowering,
  name = "EnumClassConstructorLowering",
)

private val enumClassConstructorBodyLoweringPhase = makeIrModulePhase(
  ::EnumClassConstructorBodyTransformer,
  name = "EnumClassConstructorBodyLowering",
)

private val enumEntryInstancesLoweringPhase = makeIrModulePhase(
  ::EnumEntryInstancesLowering,
  name = "EnumEntryInstancesLowering",
  prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumEntryInstancesBodyLoweringPhase = makeIrModulePhase(
  ::EnumEntryInstancesBodyLowering,
  name = "EnumEntryInstancesBodyLowering",
  prerequisite = setOf(enumEntryInstancesLoweringPhase)
)

private val enumClassCreateInitializerLoweringPhase = makeIrModulePhase(
  ::EnumClassCreateInitializerLowering,
  name = "EnumClassCreateInitializerLowering",
  prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumEntryCreateGetInstancesFunsLoweringPhase = makeIrModulePhase(
  ::EnumEntryCreateGetInstancesFunsLowering,
  name = "EnumEntryCreateGetInstancesFunsLowering",
  prerequisite = setOf(enumClassConstructorLoweringPhase)
)

private val enumSyntheticFunsLoweringPhase = makeIrModulePhase(
  ::EnumSyntheticFunctionsAndPropertiesLowering,
  name = "EnumSyntheticFunctionsAndPropertiesLowering",
  prerequisite = setOf(
    enumClassConstructorLoweringPhase,
    enumClassCreateInitializerLoweringPhase,
    enumEntryCreateGetInstancesFunsLoweringPhase
  )
)

private val enumUsageLoweringPhase = makeIrModulePhase(
  ::EnumUsageLowering,
  name = "EnumUsageLowering",
  prerequisite = setOf(enumEntryCreateGetInstancesFunsLoweringPhase)
)

private val enumEntryRemovalLoweringPhase = makeIrModulePhase(
  ::EnumClassRemoveEntriesLowering,
  name = "EnumEntryRemovalLowering",
  prerequisite = setOf(enumUsageLoweringPhase)
)


private val propertyReferenceLowering = makeIrModulePhase(
  ::WasmPropertyReferenceLowering,
  name = "WasmPropertyReferenceLowering",
)

private val callableReferencePhase = makeIrModulePhase(
  ::CallableReferenceLowering,
  name = "WasmCallableReferenceLowering",
)

private val singleAbstractMethodPhase = makeIrModulePhase(
  ::JsSingleAbstractMethodLowering,
  name = "SingleAbstractMethod",
)

private val localDelegatedPropertiesLoweringPhase = makeIrModulePhase<WasmBackendContext>(
  { LocalDelegatedPropertiesLowering() },
  name = "LocalDelegatedPropertiesLowering",
)

private val localDeclarationsLoweringPhase = makeIrModulePhase(
  ::LocalDeclarationsLowering,
  name = "LocalDeclarationsLowering",
  prerequisite = setOf(sharedVariablesLoweringPhase, localDelegatedPropertiesLoweringPhase)
)

private val localClassExtractionPhase = makeIrModulePhase(
  ::LocalClassPopupLowering,
  name = "LocalClassExtractionPhase",
  prerequisite = setOf(localDeclarationsLoweringPhase)
)

private val staticCallableReferenceLoweringPhase = makeIrModulePhase(
  ::WasmStaticCallableReferenceLowering,
  name = "WasmStaticCallableReferenceLowering",
  prerequisite = setOf(callableReferencePhase, localClassExtractionPhase)
)

private val innerClassesLoweringPhase = makeIrModulePhase<WasmBackendContext>(
  ::InnerClassesLowering,
  name = "InnerClassesLowering",
)

private val innerClassesMemberBodyLoweringPhase = makeIrModulePhase(
  ::InnerClassesMemberBodyLowering,
  name = "InnerClassesMemberBody",
  prerequisite = setOf(innerClassesLoweringPhase)
)

private val innerClassConstructorCallsLoweringPhase = makeIrModulePhase<WasmBackendContext>(
  ::InnerClassConstructorCallsLowering,
  name = "InnerClassConstructorCallsLowering",
)

private val suspendFunctionsLoweringPhase = makeIrModulePhase(
  ::JsSuspendFunctionsLowering,
  name = "SuspendFunctionsLowering",
)

private val addContinuationToNonLocalSuspendFunctionsLoweringPhase = makeIrModulePhase(
  ::AddContinuationToNonLocalSuspendFunctionsLowering,
  name = "AddContinuationToNonLocalSuspendFunctionsLowering",
)

private val addContinuationToFunctionCallsLoweringPhase = makeIrModulePhase(
  ::AddContinuationToFunctionCallsLowering,
  name = "AddContinuationToFunctionCallsLowering",
  prerequisite = setOf(
    addContinuationToNonLocalSuspendFunctionsLoweringPhase,
  )
)

private val generateMainFunctionWrappersPhase = makeIrModulePhase(
  ::GenerateMainFunctionWrappers,
  name = "GenerateMainFunctionWrappers",
)

private val defaultArgumentStubGeneratorPhase = makeIrModulePhase<WasmBackendContext>(
  { context ->
    DefaultArgumentStubGenerator(
      context,
      MaskedDefaultArgumentFunctionFactory(context, copyOriginalFunctionLocation = false),
      skipExternalMethods = true
    )
  },
  name = "DefaultArgumentStubGenerator",
)

private val defaultArgumentPatchOverridesPhase = makeIrModulePhase(
  ::DefaultParameterPatchOverridenSymbolsLowering,
  name = "DefaultArgumentsPatchOverrides",
  prerequisite = setOf(defaultArgumentStubGeneratorPhase)
)

private val defaultParameterInjectorPhase = makeIrModulePhase(
  { context -> DefaultParameterInjector(context, MaskedDefaultArgumentFunctionFactory(context), skipExternalMethods = true) },
  name = "DefaultParameterInjector",
  prerequisite = setOf(innerClassesLoweringPhase)
)

private val defaultParameterCleanerPhase = makeIrModulePhase(
  ::DefaultParameterCleaner,
  name = "DefaultParameterCleaner",
)

private val propertiesLoweringPhase = makeIrModulePhase<WasmBackendContext>(
  { PropertiesLowering() },
  name = "PropertiesLowering",
)

private val primaryConstructorLoweringPhase = makeIrModulePhase(
  ::PrimaryConstructorLowering,
  name = "PrimaryConstructorLowering",
)

private val delegateToPrimaryConstructorLoweringPhase = makeIrModulePhase(
  ::DelegateToSyntheticPrimaryConstructor,
  name = "DelegateToSyntheticPrimaryConstructor",
  prerequisite = setOf(primaryConstructorLoweringPhase)
)

private val initializersLoweringPhase = makeIrModulePhase(
  ::InitializersLowering,
  name = "InitializersLowering",
  prerequisite = setOf(primaryConstructorLoweringPhase, localClassExtractionPhase)
)

private val initializersCleanupLoweringPhase = makeIrModulePhase(
  ::InitializersCleanupLowering,
  name = "InitializersCleanupLowering",
  prerequisite = setOf(initializersLoweringPhase)
)

private val excludeDeclarationsFromCodegenPhase = makeIrModulePhase(
  ::ExcludeDeclarationsFromCodegen,
  name = "ExcludeDeclarationsFromCodegen",
)

private val unhandledExceptionLowering = makeIrModulePhase(
  ::UnhandledExceptionLowering,
  name = "UnhandledExceptionLowering",
)

private val tryCatchCanonicalization = makeIrModulePhase(
  ::TryCatchCanonicalization,
  name = "TryCatchCanonicalization",
  prerequisite = setOf(inlineAllFunctionsPhase, unhandledExceptionLowering)
)

private val bridgesConstructionPhase = makeIrModulePhase(
  ::WasmBridgesConstruction,
  name = "BridgesConstruction",
)

private val inlineClassDeclarationLoweringPhase = makeIrModulePhase<WasmBackendContext>(
  { InlineClassLowering(it).inlineClassDeclarationLowering },
  name = "InlineClassDeclarationLowering",
)

private val inlineClassUsageLoweringPhase = makeIrModulePhase<WasmBackendContext>(
  { InlineClassLowering(it).inlineClassUsageLowering },
  name = "InlineClassUsageLowering",
)

private val autoboxingTransformerPhase = makeIrModulePhase<WasmBackendContext>(
  { context -> AutoboxingTransformer(context) },
  name = "AutoboxingTransformer",
)

private val staticMembersLoweringPhase = makeIrModulePhase(
  ::StaticMembersLowering,
  name = "StaticMembersLowering",
)

private val classReferenceLoweringPhase = makeIrModulePhase(
  ::WasmClassReferenceLowering,
  name = "WasmClassReferenceLowering",
)

private val wasmVarargExpressionLoweringPhase = makeIrModulePhase(
  ::WasmVarargExpressionLowering,
  name = "WasmVarargExpressionLowering",
)

private val builtInsLoweringPhase0 = makeIrModulePhase(
  ::BuiltInsLowering,
  name = "BuiltInsLowering0",
)

private val builtInsLoweringPhase = makeIrModulePhase(
  ::BuiltInsLowering,
  name = "BuiltInsLowering",
)

private val associatedObjectsLowering = makeIrModulePhase(
  ::AssociatedObjectsLowering,
  name = "AssociatedObjectsLowering",
  prerequisite = setOf(localClassExtractionPhase)
)

private val objectDeclarationLoweringPhase = makeIrModulePhase(
  ::ObjectDeclarationLowering,
  name = "ObjectDeclarationLowering",
  prerequisite = setOf(enumClassCreateInitializerLoweringPhase, staticCallableReferenceLoweringPhase)
)

private val invokeStaticInitializersPhase = makeIrModulePhase(
  ::InvokeStaticInitializersLowering,
  name = "InvokeStaticInitializersLowering",
  prerequisite = setOf(objectDeclarationLoweringPhase)
)

private val objectUsageLoweringPhase = makeIrModulePhase(
  ::ObjectUsageLowering,
  name = "ObjectUsageLowering",
)

private val explicitlyCastExternalTypesPhase = makeIrModulePhase(
  ::ExplicitlyCastExternalTypesLowering,
  name = "ExplicitlyCastExternalTypesLowering",
)

private val typeOperatorLoweringPhase = makeIrModulePhase(
  ::WasmTypeOperatorLowering,
  name = "TypeOperatorLowering",
)

private val genericReturnTypeLowering = makeIrModulePhase(
  ::GenericReturnTypeLowering,
  name = "GenericReturnTypeLowering",
)

private val eraseVirtualDispatchReceiverParametersTypes = makeIrModulePhase(
  ::EraseVirtualDispatchReceiverParametersTypes,
  name = "EraseVirtualDispatchReceiverParametersTypes",
)

private val virtualDispatchReceiverExtractionPhase = makeIrModulePhase(
  ::VirtualDispatchReceiverExtraction,
  name = "VirtualDispatchReceiverExtraction",
)

private val forLoopsLoweringPhase = makeIrModulePhase(
  ::ForLoopsLowering,
  name = "ForLoopsLowering",
)

private val propertyLazyInitLoweringPhase = makeIrModulePhase(
  ::PropertyLazyInitLowering,
  name = "PropertyLazyInitLowering",
)

private val removeInitializersForLazyProperties = makeIrModulePhase(
  ::RemoveInitializersForLazyProperties,
  name = "RemoveInitializersForLazyProperties",
)

private val propertyAccessorInlinerLoweringPhase = makeIrModulePhase(
  ::PropertyAccessorInlineLowering,
  name = "PropertyAccessorInlineLowering",
)

private val invokeOnExportedFunctionExitLowering = makeIrModulePhase(
  ::InvokeOnExportedFunctionExitLowering,
  name = "InvokeOnExportedFunctionExitLowering",
)

private val expressionBodyTransformer = makeIrModulePhase(
  ::ExpressionBodyTransformer,
  name = "ExpressionBodyTransformer",
)

private val unitToVoidLowering = makeIrModulePhase(
  ::UnitToVoidLowering,
  name = "UnitToVoidLowering",
)

private val purifyObjectInstanceGettersLoweringPhase = makeIrModulePhase(
  ::PurifyObjectInstanceGettersLowering,
  name = "PurifyObjectInstanceGettersLowering",
  prerequisite = setOf(objectDeclarationLoweringPhase, objectUsageLoweringPhase)
)

private val inlineObjectsWithPureInitializationLoweringPhase = makeIrModulePhase(
  ::InlineObjectsWithPureInitializationLowering,
  name = "InlineObjectsWithPureInitializationLowering",
  prerequisite = setOf(purifyObjectInstanceGettersLoweringPhase)
)

private val whenBranchOptimiserLoweringPhase = makeIrModulePhase(
  ::WhenBranchOptimiserLowering,
  name = "WhenBranchOptimiserLowering",
)

val constEvaluationPhase = makeIrModulePhase(
  { context ->
    val configuration = IrInterpreterConfiguration(
      printOnlyExceptionMessage = true,
      platform = WasmPlatforms.unspecifiedWasmPlatform,
    )
    ConstEvaluationLowering(context, configuration = configuration)
  },
  name = "ConstEvaluationLowering",
  prerequisite = setOf(inlineAllFunctionsPhase)
)

fun getWasmLowerings(
  configuration: CompilerConfiguration,
  isIncremental: Boolean,
): List<SimpleNamedCompilerPhase<WasmBackendContext, IrModuleFragment, IrModuleFragment>> {
  val syntheticAccessorsDumpDir = configuration[KlibConfigurationKeys.SYNTHETIC_ACCESSORS_DUMP_DIR]
  val isDebugFriendlyCompilation = configuration.getBoolean(WasmConfigurationKeys.WASM_FORCE_DEBUG_FRIENDLY_COMPILATION)

  return listOfNotNull(
    // BEGIN: Common Native/JS/Wasm prefix.
    validateIrBeforeLowering,
    lateinitPhase,
    sharedVariablesLoweringPhase,
    outerThisSpecialAccessorInInlineFunctionsPhase,
    localClassesInInlineLambdasPhase,
    inlineCallableReferenceToLambdaPhase,
    arrayConstructorPhase,
    wrapInlineDeclarationsWithReifiedTypeParametersLowering,
    inlineOnlyPrivateFunctionsPhase,
    syntheticAccessorGenerationPhase,
    // Note: The validation goes after both `inlineOnlyPrivateFunctionsPhase` and `syntheticAccessorGenerationPhase`
    // just because it goes so in Native.
    validateIrAfterInliningOnlyPrivateFunctionsPhase,
    inlineAllFunctionsPhase,
    dumpSyntheticAccessorsPhase.takeIf { syntheticAccessorsDumpDir != null },
    validateIrAfterInliningAllFunctionsPhase,
    // END: Common Native/JS/Wasm prefix.

    constEvaluationPhase,
    removeInlineDeclarationsWithReifiedTypeParametersLoweringPhase,

    jsCodeCallsLowering,

    generateTests,

    excludeDeclarationsFromCodegenPhase,
    expectDeclarationsRemovingPhase,
    rangeContainsLoweringPhase,

    tailrecLoweringPhase,

    enumWhenPhase,
    enumClassConstructorLoweringPhase,
    enumClassConstructorBodyLoweringPhase,
    enumEntryInstancesLoweringPhase,
    enumEntryInstancesBodyLoweringPhase,
    enumClassCreateInitializerLoweringPhase,
    enumEntryCreateGetInstancesFunsLoweringPhase,
    enumSyntheticFunsLoweringPhase,

    propertyReferenceLowering,
    callableReferencePhase,
    singleAbstractMethodPhase,
    localDelegatedPropertiesLoweringPhase,
    localDeclarationsLoweringPhase,
    localClassExtractionPhase,
    staticCallableReferenceLoweringPhase,
    innerClassesLoweringPhase,
    innerClassesMemberBodyLoweringPhase,
    innerClassConstructorCallsLoweringPhase,
    propertiesLoweringPhase,
    primaryConstructorLoweringPhase,
    delegateToPrimaryConstructorLoweringPhase,

    wasmStringSwitchOptimizerLowering.takeIf { !isDebugFriendlyCompilation },

    associatedObjectsLowering,

    complexExternalDeclarationsToTopLevelFunctionsLowering,
    complexExternalDeclarationsUsagesLowering,

    jsInteropFunctionsLowering,

    enumUsageLoweringPhase,
    enumEntryRemovalLoweringPhase,

    suspendFunctionsLoweringPhase,
    initializersLoweringPhase,
    initializersCleanupLoweringPhase,

    addContinuationToNonLocalSuspendFunctionsLoweringPhase,
    addContinuationToFunctionCallsLoweringPhase,
    generateMainFunctionWrappersPhase,

    invokeOnExportedFunctionExitLowering,

    unhandledExceptionLowering,
    tryCatchCanonicalization,

    forLoopsLoweringPhase,
    propertyLazyInitLoweringPhase,
    removeInitializersForLazyProperties,

    // This doesn't work with IC as of now for accessors within inline functions because
    //  there is no special case for Wasm in the computation of inline function transitive
    //  hashes the same way it's being done with the calculation of symbol hashes.
    propertyAccessorInlinerLoweringPhase.takeIf { !isIncremental && !isDebugFriendlyCompilation },

    stringConcatenationLowering,

    defaultArgumentStubGeneratorPhase,
    defaultArgumentPatchOverridesPhase,
    defaultParameterInjectorPhase,
    defaultParameterCleanerPhase,

//            TODO:
//            multipleCatchesLoweringPhase,
    classReferenceLoweringPhase,

    wasmVarargExpressionLoweringPhase,
    inlineClassDeclarationLoweringPhase,
    inlineClassUsageLoweringPhase,

    expressionBodyTransformer,
    eraseVirtualDispatchReceiverParametersTypes,
    bridgesConstructionPhase,

    objectDeclarationLoweringPhase,
    genericReturnTypeLowering,
    unitToVoidLowering,

    // Replace builtins before autoboxing
    builtInsLoweringPhase0,

    autoboxingTransformerPhase,

    objectUsageLoweringPhase,
    purifyObjectInstanceGettersLoweringPhase.takeIf { !isIncremental && !isDebugFriendlyCompilation },

    explicitlyCastExternalTypesPhase,
    typeOperatorLoweringPhase,

    // Clean up built-ins after type operator lowering
    builtInsLoweringPhase,

    virtualDispatchReceiverExtractionPhase,
    invokeStaticInitializersPhase,
    staticMembersLoweringPhase,

    // This is applied for non-IC mode, which is a better optimization than inlineUnitInstanceGettersLowering
    inlineObjectsWithPureInitializationLoweringPhase.takeIf { !isIncremental && !isDebugFriendlyCompilation },

    whenBranchOptimiserLoweringPhase,
    validateIrAfterLowering,
  )
}

fun getWasmPhases(
  configuration: CompilerConfiguration,
  isIncremental: Boolean,
): SameTypeNamedCompilerPhase<WasmBackendContext, IrModuleFragment> = SameTypeNamedCompilerPhase(
  name = "IrModuleLowering",
  lower = getWasmLowerings(configuration, isIncremental).toCompilerPhase(),
  actions = DEFAULT_IR_ACTIONS,
  nlevels = 1
)
