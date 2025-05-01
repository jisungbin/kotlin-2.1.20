/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.common.lower.ArrayConstructorLowering
import org.jetbrains.kotlin.backend.common.lower.ConstEvaluationLowering
import org.jetbrains.kotlin.backend.common.lower.FlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.common.lower.ProvisionalFunctionExpressionLowering
import org.jetbrains.kotlin.backend.common.lower.RangeContainsLowering
import org.jetbrains.kotlin.backend.common.lower.SharedVariablesLowering
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.phaser.DEFAULT_IR_ACTIONS
import org.jetbrains.kotlin.backend.common.phaser.buildModuleLoweringsPhase
import org.jetbrains.kotlin.backend.common.phaser.createFilePhases
import org.jetbrains.kotlin.backend.common.phaser.performByIrFile
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.backend.jvm.lower.AddContinuationLowering
import org.jetbrains.kotlin.backend.jvm.lower.AdditionalClassAnnotationLowering
import org.jetbrains.kotlin.backend.jvm.lower.AnnotationLowering
import org.jetbrains.kotlin.backend.jvm.lower.AnonymousObjectSuperConstructorLowering
import org.jetbrains.kotlin.backend.jvm.lower.ApiVersionIsAtLeastEvaluationLowering
import org.jetbrains.kotlin.backend.jvm.lower.AssertionLowering
import org.jetbrains.kotlin.backend.jvm.lower.BridgeLowering
import org.jetbrains.kotlin.backend.jvm.lower.CollectionStubMethodLowering
import org.jetbrains.kotlin.backend.jvm.lower.CreateSeparateCallForInlinedLambdasLowering
import org.jetbrains.kotlin.backend.jvm.lower.DirectInvokeLowering
import org.jetbrains.kotlin.backend.jvm.lower.EnumClassLowering
import org.jetbrains.kotlin.backend.jvm.lower.EnumExternalEntriesLowering
import org.jetbrains.kotlin.backend.jvm.lower.ExternalPackageParentPatcherLowering
import org.jetbrains.kotlin.backend.jvm.lower.FakeLocalVariablesForBytecodeInlinerLowering
import org.jetbrains.kotlin.backend.jvm.lower.FakeLocalVariablesForIrInlinerLowering
import org.jetbrains.kotlin.backend.jvm.lower.FileClassLowering
import org.jetbrains.kotlin.backend.jvm.lower.FragmentLocalFunctionPatchLowering
import org.jetbrains.kotlin.backend.jvm.lower.FragmentSharedVariablesLowering
import org.jetbrains.kotlin.backend.jvm.lower.FunctionNVarargBridgeLowering
import org.jetbrains.kotlin.backend.jvm.lower.FunctionReferenceLowering
import org.jetbrains.kotlin.backend.jvm.lower.GenerateJvmDefaultCompatibilityBridges
import org.jetbrains.kotlin.backend.jvm.lower.GenerateMultifileFacades
import org.jetbrains.kotlin.backend.jvm.lower.InheritedDefaultMethodsOnClassesLowering
import org.jetbrains.kotlin.backend.jvm.lower.InlinedClassReferencesBoxingLowering
import org.jetbrains.kotlin.backend.jvm.lower.InterfaceDefaultCallsLowering
import org.jetbrains.kotlin.backend.jvm.lower.InterfaceLowering
import org.jetbrains.kotlin.backend.jvm.lower.InterfaceObjectCallsLowering
import org.jetbrains.kotlin.backend.jvm.lower.InterfaceSuperCallsLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmAnnotationImplementationLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmArgumentNullabilityAssertionsLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmBuiltInsLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmDefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.jvm.lower.JvmDefaultConstructorLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmDefaultParameterCleaner
import org.jetbrains.kotlin.backend.jvm.lower.JvmDefaultParameterInjector
import org.jetbrains.kotlin.backend.jvm.lower.JvmExpectDeclarationRemover
import org.jetbrains.kotlin.backend.jvm.lower.JvmInitializersCleanupLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmInitializersLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmInlineCallableReferenceToLambdaPhase
import org.jetbrains.kotlin.backend.jvm.lower.JvmInlineCallableReferenceToLambdaWithDefaultsPhase
import org.jetbrains.kotlin.backend.jvm.lower.JvmInlineClassLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmInnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmInnerClassesLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmInnerClassesMemberBodyLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmInventNamesForLocalClasses
import org.jetbrains.kotlin.backend.jvm.lower.JvmIrInliner
import org.jetbrains.kotlin.backend.jvm.lower.JvmIrValidationAfterLoweringPhase
import org.jetbrains.kotlin.backend.jvm.lower.JvmIrValidationBeforeLoweringPhase
import org.jetbrains.kotlin.backend.jvm.lower.JvmKotlinNothingValueExceptionLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmLateinitLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmLocalClassPopupLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmLocalDeclarationsLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmMultiFieldValueClassLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmOptimizationLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmOverloadsAnnotationLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmPropertiesLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmReturnableBlockLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmSafeCallChainFoldingLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmSingleAbstractMethodLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmStaticInCompanionLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmStaticInObjectLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmStringConcatenationLowering
import org.jetbrains.kotlin.backend.jvm.lower.JvmTailrecLowering
import org.jetbrains.kotlin.backend.jvm.lower.MainMethodGenerationLowering
import org.jetbrains.kotlin.backend.jvm.lower.MakePropertyDelegateMethodsStaticLowering
import org.jetbrains.kotlin.backend.jvm.lower.MappedEnumWhenLowering
import org.jetbrains.kotlin.backend.jvm.lower.MarkNecessaryInlinedClassesAsRegeneratedLowering
import org.jetbrains.kotlin.backend.jvm.lower.MoveOrCopyCompanionObjectFieldsLowering
import org.jetbrains.kotlin.backend.jvm.lower.ObjectClassLowering
import org.jetbrains.kotlin.backend.jvm.lower.PolymorphicSignatureLowering
import org.jetbrains.kotlin.backend.jvm.lower.ProcessOptionalAnnotations
import org.jetbrains.kotlin.backend.jvm.lower.PropertyReferenceDelegationLowering
import org.jetbrains.kotlin.backend.jvm.lower.PropertyReferenceLowering
import org.jetbrains.kotlin.backend.jvm.lower.RecordEnclosingMethodsLowering
import org.jetbrains.kotlin.backend.jvm.lower.RemapObjectFieldAccesses
import org.jetbrains.kotlin.backend.jvm.lower.RemoveDuplicatedInlinedLocalClassesLowering
import org.jetbrains.kotlin.backend.jvm.lower.RenameFieldsLowering
import org.jetbrains.kotlin.backend.jvm.lower.RepeatedAnnotationLowering
import org.jetbrains.kotlin.backend.jvm.lower.ReplaceKFunctionInvokeWithFunctionInvoke
import org.jetbrains.kotlin.backend.jvm.lower.ReplaceNumberToCharCallSitesLowering
import org.jetbrains.kotlin.backend.jvm.lower.ResolveInlineCalls
import org.jetbrains.kotlin.backend.jvm.lower.RestoreInlineLambda
import org.jetbrains.kotlin.backend.jvm.lower.SerializeIrPhase
import org.jetbrains.kotlin.backend.jvm.lower.SingletonOrConstantDelegationLowering
import org.jetbrains.kotlin.backend.jvm.lower.SingletonReferencesLowering
import org.jetbrains.kotlin.backend.jvm.lower.SpecialAccessLowering
import org.jetbrains.kotlin.backend.jvm.lower.StaticCallableReferenceLowering
import org.jetbrains.kotlin.backend.jvm.lower.StaticDefaultFunctionLowering
import org.jetbrains.kotlin.backend.jvm.lower.StaticInitializersLowering
import org.jetbrains.kotlin.backend.jvm.lower.SuspendLambdaLowering
import org.jetbrains.kotlin.backend.jvm.lower.SyntheticAccessorLowering
import org.jetbrains.kotlin.backend.jvm.lower.TailCallOptimizationLowering
import org.jetbrains.kotlin.backend.jvm.lower.ToArrayLowering
import org.jetbrains.kotlin.backend.jvm.lower.TypeAliasAnnotationMethodsLowering
import org.jetbrains.kotlin.backend.jvm.lower.TypeOperatorLowering
import org.jetbrains.kotlin.backend.jvm.lower.UniqueLoopLabelsLowering
import org.jetbrains.kotlin.backend.jvm.lower.VarargLowering
import org.jetbrains.kotlin.config.phaser.SameTypeNamedCompilerPhase

private val jvmFilePhases = createFilePhases<JvmBackendContext>(
  ::TypeAliasAnnotationMethodsLowering,
  ::ProvisionalFunctionExpressionLowering,

  ::JvmOverloadsAnnotationLowering,
  ::MainMethodGenerationLowering,

  ::AnnotationLowering,
  ::JvmAnnotationImplementationLowering,
  ::PolymorphicSignatureLowering,
  ::VarargLowering,

  ::JvmLateinitLowering,
  ::JvmInventNamesForLocalClasses,

  ::JvmInlineCallableReferenceToLambdaPhase,
  ::DirectInvokeLowering,
  ::FunctionReferenceLowering,

  ::SuspendLambdaLowering,
  ::PropertyReferenceDelegationLowering,
  ::SingletonOrConstantDelegationLowering,
  ::PropertyReferenceLowering,
  ::ArrayConstructorLowering,

  // TODO: merge the next three phases together, as visitors behave incorrectly between them
  //  (backing fields moved out of companion objects are reachable by two paths):
  ::MoveOrCopyCompanionObjectFieldsLowering,
  ::JvmPropertiesLowering,
  ::RemapObjectFieldAccesses,

  ::AnonymousObjectSuperConstructorLowering,
  ::JvmBuiltInsLowering,

  ::RangeContainsLowering,
  ::ForLoopsLowering,
  ::CollectionStubMethodLowering,
  ::JvmSingleAbstractMethodLowering,
  ::JvmMultiFieldValueClassLowering,
  ::JvmInlineClassLowering,
  ::JvmTailrecLowering,

  ::MappedEnumWhenLowering,

  ::AssertionLowering,
  ::JvmReturnableBlockLowering,
  ::SingletonReferencesLowering,
  ::SharedVariablesLowering,
  ::JvmLocalDeclarationsLowering,

  ::RemoveDuplicatedInlinedLocalClassesLowering,

  ::JvmLocalClassPopupLowering,
  ::StaticCallableReferenceLowering,

  ::JvmDefaultConstructorLowering,

  ::FlattenStringConcatenationLowering,
  ::JvmStringConcatenationLowering,

  ::JvmDefaultArgumentStubGenerator,
  ::JvmDefaultParameterInjector,
  ::JvmDefaultParameterCleaner,

  ::FragmentLocalFunctionPatchLowering,

  ::InterfaceLowering,
  ::InheritedDefaultMethodsOnClassesLowering,
  ::GenerateJvmDefaultCompatibilityBridges,
  ::InterfaceSuperCallsLowering,
  ::InterfaceDefaultCallsLowering,
  ::InterfaceObjectCallsLowering,

  ::TailCallOptimizationLowering,
  ::AddContinuationLowering,

  ::JvmInnerClassesLowering,
  ::JvmInnerClassesMemberBodyLowering,
  ::JvmInnerClassConstructorCallsLowering,

  ::EnumClassLowering,
  ::EnumExternalEntriesLowering,
  ::ObjectClassLowering,
  ::StaticInitializersLowering,
  ::UniqueLoopLabelsLowering,
  ::JvmInitializersLowering,
  ::JvmInitializersCleanupLowering,
  ::FunctionNVarargBridgeLowering,
  ::JvmStaticInCompanionLowering,
  ::StaticDefaultFunctionLowering,
  ::BridgeLowering,
  ::SyntheticAccessorLowering,

  ::JvmArgumentNullabilityAssertionsLowering,
  ::ToArrayLowering,
  ::JvmSafeCallChainFoldingLowering,
  ::JvmOptimizationLowering,
  ::AdditionalClassAnnotationLowering,
  ::RecordEnclosingMethodsLowering,
  ::TypeOperatorLowering,
  ::ReplaceKFunctionInvokeWithFunctionInvoke,
  ::JvmKotlinNothingValueExceptionLowering,
  ::MakePropertyDelegateMethodsStaticLowering,
  ::ReplaceNumberToCharCallSitesLowering,

  ::RenameFieldsLowering,
  ::FakeLocalVariablesForBytecodeInlinerLowering,
  ::FakeLocalVariablesForIrInlinerLowering,

  ::SpecialAccessLowering,
)

val jvmLoweringPhases = SameTypeNamedCompilerPhase(
  name = "IrLowering",
  nlevels = 1,
  actions = DEFAULT_IR_ACTIONS,
  lower = buildModuleLoweringsPhase(
    ::ExternalPackageParentPatcherLowering,
    ::FragmentSharedVariablesLowering,
    ::JvmIrValidationBeforeLoweringPhase,
    ::ProcessOptionalAnnotations,
    ::JvmExpectDeclarationRemover,
    ::ConstEvaluationLowering,
    ::SerializeIrPhase,
    ::FileClassLowering,
    ::JvmStaticInObjectLowering,
    ::RepeatedAnnotationLowering,
    ::JvmInlineCallableReferenceToLambdaWithDefaultsPhase,
    ::JvmIrInliner,
    ::ApiVersionIsAtLeastEvaluationLowering,
    ::CreateSeparateCallForInlinedLambdasLowering,
    ::MarkNecessaryInlinedClassesAsRegeneratedLowering,
    ::InlinedClassReferencesBoxingLowering,
    ::RestoreInlineLambda,
  ).then(
    performByIrFile("PerformByIrFile", jvmFilePhases)
  ) then buildModuleLoweringsPhase(
    ::GenerateMultifileFacades,
    ::ResolveInlineCalls,
    ::JvmIrValidationAfterLoweringPhase
  )
)
