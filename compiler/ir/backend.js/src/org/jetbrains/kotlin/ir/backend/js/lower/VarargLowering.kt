/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAllArgumentsWithIr
import org.jetbrains.kotlin.ir.util.getInlineClassBackingField
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class VarargLowering(val context: JsIrBackendContext) : BodyLoweringPass {

  override fun lower(irBody: IrBody, container: IrDeclaration) {
    irBody.transformChildrenVoid(VarargTransformer(context))
  }
}

private class VarargTransformer(
  val context: JsIrBackendContext,
) : IrElementTransformerVoid() {

  var externalVarargs = mutableSetOf<IrVararg>()

  override fun visitVararg(expression: IrVararg): IrExpression {
    expression.transformChildrenVoid(this)

    val currentList = mutableListOf<IrExpression>()
    val segments = mutableListOf<IrExpression>()

    val arrayInfo = InlineClassArrayInfo(context, expression.varargElementType, expression.type)

    for (e in expression.elements) {
      when (e) {
        is IrSpreadElement -> {
          if (currentList.isNotEmpty()) {
            segments.add(arrayInfo.toPrimitiveArrayLiteral(currentList))
            currentList.clear()
          }
          segments.add(arrayInfo.unboxElementIfNeeded(e.expression))
        }

        is IrExpression -> {
          currentList.add(arrayInfo.unboxElementIfNeeded(e))
        }
      }
    }
    if (currentList.isNotEmpty()) {
      segments.add(arrayInfo.toPrimitiveArrayLiteral(currentList))
      currentList.clear()
    }

    // empty vararg => empty array literal
    if (segments.isEmpty()) {
      with(arrayInfo) {
        return boxArrayIfNeeded(toPrimitiveArrayLiteral(emptyList<IrExpression>()))
      }
    }

    // vararg with a single segment => no need to concatenate
    if (segments.size == 1) {
      val segment = segments.first()
      val argument = getArgumentFromSingleSegment(
        expression,
        segment,
        arrayInfo
      )

      return arrayInfo.boxArrayIfNeeded(argument)
    }

    val arrayLiteral =
      segments.toArrayLiteral(
        context,
        IrSimpleTypeImpl(context.intrinsics.array, false, emptyList(), emptyList()), // TODO: Substitution
        context.irBuiltIns.anyType
      )

    val concatFun = if (arrayInfo.primitiveArrayType.classifierOrNull in context.intrinsics.primitiveArrays.keys) {
      context.intrinsics.primitiveArrayConcat
    } else {
      context.intrinsics.arrayConcat
    }

    val res = IrCallImpl(
      expression.startOffset,
      expression.endOffset,
      arrayInfo.primitiveArrayType,
      concatFun,
      typeArgumentsCount = 0
    ).apply {
      arguments[0] = arrayLiteral
    }

    return arrayInfo.boxArrayIfNeeded(res)
  }

  private fun getArgumentFromSingleSegment(
    expression: IrVararg,
    segment: IrExpression,
    arrayInfo: InlineClassArrayInfo,
  ): IrExpression {
    if (expression in externalVarargs) {
      externalVarargs.remove(expression)
      return segment
    }

    return if (expression.elements.any { it is IrSpreadElement }) {
      val elementType = arrayInfo.primitiveElementType
      val copyFunction =
        if (elementType.isChar() || elementType.isBoolean() || elementType.isLong())
          context.intrinsics.taggedArrayCopy
        else
          context.intrinsics.jsArraySlice

      IrCallImpl(
        expression.startOffset,
        expression.endOffset,
        arrayInfo.primitiveArrayType,
        copyFunction,
        typeArgumentsCount = 1
      ).apply {
        typeArguments[0] = arrayInfo.primitiveArrayType
        arguments[0] = segment
      }
    } else segment
  }

  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    expression.transformChildrenVoid()

    if (expression.symbol.owner.isExternal) {
      for (parameter in expression.symbol.owner.parameters) {
        if (parameter.varargElementType != null) {
          (expression.arguments[parameter] as? IrVararg)?.let {
            externalVarargs.add(it)
          }
        }
      }
    }

    for ((parameter, argument) in expression.getAllArgumentsWithIr()) {
      val varargElementType = parameter.varargElementType
      if (argument == null && varargElementType != null) {
        val arrayInfo = InlineClassArrayInfo(context, varargElementType, parameter.type)
        val emptyArray = with(arrayInfo) {
          boxArrayIfNeeded(toPrimitiveArrayLiteral(emptyList()))
        }

        expression.arguments[parameter] = emptyArray
      }
    }

    return expression
  }
}

private fun List<IrExpression>.toArrayLiteral(context: JsIrBackendContext, type: IrType, varargElementType: IrType): IrExpression {

  // TODO: Use symbols when builtins symbol table is fixes
  val primitiveType = context.intrinsics.primitiveArrays.mapKeys { it.key }[type.classifierOrNull]

  val intrinsic =
    if (primitiveType != null)
      context.intrinsics.primitiveToLiteralConstructor.getValue(primitiveType)
    else
      context.intrinsics.arrayLiteral

  val startOffset = firstOrNull()?.startOffset ?: UNDEFINED_OFFSET
  val endOffset = lastOrNull()?.endOffset ?: UNDEFINED_OFFSET

  val irVararg = IrVarargImpl(startOffset, endOffset, type, varargElementType, this)

  return IrCallImpl(
    startOffset, endOffset,
    type, intrinsic,
    typeArgumentsCount = if (intrinsic.owner.typeParameters.isNotEmpty()) 1 else 0
  ).apply {
    if (typeArguments.size == 1) {
      typeArguments[0] = varargElementType
    }
    arguments[0] = irVararg
  }
}

internal class InlineClassArrayInfo(val context: JsIrBackendContext, val elementType: IrType, val arrayType: IrType) {

  private fun IrType.getInlinedClass() = context.inlineClassesUtils.getInlinedClass(this)
  private fun getInlineClassUnderlyingType(irClass: IrClass) = context.inlineClassesUtils.getInlineClassUnderlyingType(irClass)

  val arrayInlineClass = arrayType.getInlinedClass()
  val inlined = arrayInlineClass != null

  val primitiveElementType = when {
    inlined -> getInlineClassUnderlyingType(
      elementType.getInlinedClass() ?: compilationException(
        "Could not get inlined class",
        elementType
      )
    )
    else -> elementType
  }

  val primitiveArrayType = when {
    inlined -> getInlineClassUnderlyingType(arrayInlineClass!!)
    else -> arrayType
  }

  fun boxArrayIfNeeded(array: IrExpression) =
    if (arrayInlineClass == null)
      array
    else with(array) {
      IrConstructorCallImpl.fromSymbolOwner(
        startOffset,
        endOffset,
        arrayInlineClass.defaultType,
        arrayInlineClass.constructors.single { it.isPrimary }.symbol,
        arrayInlineClass.typeParameters.size
      ).apply {
        arguments[0] = array
      }
    }

  fun unboxElementIfNeeded(element: IrExpression): IrExpression {
    if (arrayInlineClass == null)
      return element
    else with(element) {
      val inlinedClass = type.getInlinedClass() ?: return element
      val field = getInlineClassBackingField(inlinedClass)
      return IrGetFieldImpl(startOffset, endOffset, field.symbol, field.type, this)
    }
  }

  fun toPrimitiveArrayLiteral(elements: List<IrExpression>) =
    elements.toArrayLiteral(context, primitiveArrayType, primitiveElementType)
}
