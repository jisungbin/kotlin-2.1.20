/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.ir

import org.jetbrains.kotlin.backend.common.ir.isReifiable
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrInlinedFunctionBlock
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.originalBeforeInline
import org.jetbrains.kotlin.ir.util.JvmIrInlineExperimental
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.extractRelatedDeclaration
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.inlinedElement
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isInlineArrayConstructor
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.resolve.inline.INLINE_ONLY_ANNOTATION_FQ_NAME

fun IrValueParameter.isInlineLambda(): Boolean =
  indexInOldValueParameters >= 0 &&
    !isNoinline &&
    (type.isFunction() || type.isSuspendFunction()) &&
    // Parameters with default values are always nullable, so check the expression too.
    // Note that the frontend has a diagnostic for nullable inline parameters, so actually
    // making this return `false` requires using `@Suppress`.
    //
    // 기본값이 있는 파라미터는 항상 nullable이므로, expression도 함께 확인해야 합니다.
    // 또한 프론트엔드에서는 nullable inline 파라미터에 대한 진단을 제공하므로,
    // 이 값이 실제로 false를 반환하도록 하려면 @Suppress를 사용해야 합니다.
    (!type.isNullable() || defaultValue?.expression?.type?.isNullable() == false)

// Declarations in the scope of an externally visible inline function are implicitly part of the
// public ABI of a Kotlin module. This function returns the visibility of a containing inline function
// (determined *before* lowering), or null if the given declaration is not in the scope of an inline
// function.
//
// Currently, we mark all declarations in the scope of a public inline function as public, even if
// they are contained in a nested private inline function. This is an over approximation, since private
// declarations inside of a public inline function can still escape if they are used without being
// regenerated. See `plugins/jvm-abi-gen/testData/compile/inlineNoRegeneration` for an example.
//
//
// 외부에 공개되는 인라인 함수의 스코프 안에 있는 선언들은 암묵적으로 Kotlin 모듈의 public ABI 일부가
// 됩니다. 이 함수는 포함하고 있는 인라인 함수의 가시성을 반환하며(lowering 이전에 결정됨), 주어진 선언이
// 인라인 함수의 스코프에 속하지 않는 경우 null을 반환합니다.
//
// 현재는 public 인라인 함수 스코프 안에 있는 모든 선언을 public으로 표시합니다. 이는 중첩된 private
// 인라인 함수 안에 포함된 선언까지도 포함하는 과잉 처리입니다. 그러나 public 인라인 함수 내부의 private
// 선언도 재생성 없이 사용되면 외부로 노출될 수 있기 때문입니다. 예시는
// plugins/jvm-abi-gen/testData/compile/inlineNoRegeneration에서 확인할 수 있습니다.
val IrDeclaration.inlineScopeVisibility: DescriptorVisibility?
  get() {
    var owner: IrDeclaration? = original
    var result: DescriptorVisibility? = null

    while (owner != null) {
      if (owner is IrFunction && owner.isInline) {
        result = if (!DescriptorVisibilities.isPrivate(owner.visibility)) {
          if (owner.parentClassOrNull?.visibility?.let(DescriptorVisibilities::isPrivate) == true)
          // 나는 private가 아니지만, 날 담는 부모가 private인 경우
            DescriptorVisibilities.PRIVATE
          else
          // 나는 private가 아니고, 날 담는 부모도 private가 아닌 경우
            return owner.visibility
        }

        // owner.visibility is private
        else {
          owner.visibility
        }
      }

      owner = (owner.parent as? IrDeclaration)?.original
    }

    return result
  }

// True for declarations which are in the scope of an externally visible inline function.
// 외부에 노출되는 인라인 함수 스코프 안에 있는 선언에는 true입니다.
val IrDeclaration.isInPublicInlineScope: Boolean
  get() = inlineScopeVisibility?.let(DescriptorVisibilities::isPrivate) == false

// Map declarations to original declarations before lowering.
// 선언들을 lowering 이전의 원래 선언에 매핑합니다.
private val IrDeclaration.original: IrDeclaration
  get() = (attributeOwnerId as? IrDeclaration) ?: this

fun IrStatement.unwrapInlineLambda(): IrFunctionReference? =
  when (this) {
    is IrBlock -> statements.lastOrNull()?.unwrapInlineLambda()
    is IrFunctionReference -> takeIf { it.origin == IrStatementOrigin.INLINE_LAMBDA }
    else -> null
  }

fun IrFunction.isInlineFunctionCall(context: JvmBackendContext): Boolean =
  (!context.config.isInlineDisabled || typeParameters.any(IrTypeParameter::isReified)) &&
    (isInline || isInlineArrayConstructor())

fun IrDeclaration.isInlineOnly(): Boolean =
  this is IrFunction && (
    (isInline && hasAnnotation(INLINE_ONLY_ANNOTATION_FQ_NAME)) ||
      (
        this is IrSimpleFunction &&
          correspondingPropertySymbol?.owner?.hasAnnotation(INLINE_ONLY_ANNOTATION_FQ_NAME) == true
        )
    )

fun IrDeclarationWithVisibility.isEffectivelyInlineOnly(): Boolean =
  this is IrFunction &&
    (isReifiable() || isInlineOnly() || isPrivateInlineSuspend())

fun IrFunction.isPrivateInlineSuspend(): Boolean =
  isSuspend &&
    isInline &&
    visibility == DescriptorVisibilities.PRIVATE

private fun IrElement.getDeclarationBeforeInline(): IrDeclaration? {
  val original = originalBeforeInline ?: return null
  return original.extractRelatedDeclaration()
}

fun IrElement.getAttributeOwnerBeforeInline(): IrElement? {
  if (originalBeforeInline == null) return null
  return generateSequence(this) { it.originalBeforeInline }.last()
}

val IrDeclaration.fileParentBeforeInline: IrFile
  get() {
    val original =
      getDeclarationBeforeInline()
        ?: parentClassOrNull?.getDeclarationBeforeInline()
        ?: this

    return original.fileParent
  }

@OptIn(JvmIrInlineExperimental::class)
val IrInlinedFunctionBlock.inlineDeclaration: IrDeclaration
  get() = when (val element = inlinedElement) {
    is IrFunction -> element
    is IrFunctionExpression -> element.function
    is IrFunctionReference -> element.symbol.owner
    is IrPropertyReference -> element.symbol.owner
    else -> throw AssertionError("Not supported ir element for inlining ${element?.dump()}")
  }
