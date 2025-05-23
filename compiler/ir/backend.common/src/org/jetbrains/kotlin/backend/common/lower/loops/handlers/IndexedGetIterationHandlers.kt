/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower.loops.handlers

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.loops.HeaderInfo
import org.jetbrains.kotlin.backend.common.lower.loops.HeaderInfoHandler
import org.jetbrains.kotlin.backend.common.lower.loops.IndexedGetHeaderInfo
import org.jetbrains.kotlin.ir.builders.createTmpVariable
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isCharSequence
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.isUnsignedArray
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions

/** Builds a [HeaderInfo] for iteration over iterables using the `get / []` operator and an index. */
abstract class IndexedGetIterationHandler(
  protected val context: CommonBackendContext,
  private val canCacheLast: Boolean,
) : HeaderInfoHandler<IrExpression, Nothing?> {
  override fun build(expression: IrExpression, data: Nothing?, scopeOwner: IrSymbol): HeaderInfo? =
    with(context.createIrBuilder(scopeOwner, expression.startOffset, expression.endOffset)) {
      // Consider the case like:
      //
      //   for (elem in A) { f(elem) }`
      //
      // If we lower it to:
      //
      //   for (i in A.indices) { f(A[i]) }
      //
      // ...then we will break program behaviour if `A` is an expression with side-effect. Instead, we lower it to:
      //
      //   val a = A
      //   for (i in a.indices) { f(a[i]) }
      //
      // This also ensures that the semantics of re-assignment of array variables used in the loop is consistent with the semantics
      // proposed in https://youtrack.jetbrains.com/issue/KT-21354.
      val objectVariable = scope.createTmpVariable(
        expression, nameHint = "indexedObject"
      )

      val last = irCall(expression.type.sizePropertyGetter).apply {
        dispatchReceiver = irGet(objectVariable)
      }

      IndexedGetHeaderInfo(
        this@IndexedGetIterationHandler.context.ir.symbols,
        first = irInt(0),
        last = last,
        step = irInt(1),
        canCacheLast = canCacheLast,
        objectVariable = objectVariable,
        expressionHandler = this@IndexedGetIterationHandler
      )
    }

  abstract val IrType.sizePropertyGetter: IrSimpleFunction

  abstract val IrType.getFunction: IrSimpleFunction
}

/** Builds a [HeaderInfo] for arrays. */
internal class ArrayIterationHandler(context: CommonBackendContext) : IndexedGetIterationHandler(context, canCacheLast = true) {
  private val supportsUnsignedArrays = context.optimizeLoopsOverUnsignedArrays

  override fun matchIterable(expression: IrExpression): Boolean {
    if (expression.type.isArrayType()) return true

    val callee = (expression as? IrCall)?.symbol?.owner ?: return false
    return callee.hasShape(extensionReceiver = true) &&
      callee.parameters[0].type.let { it.isArray() || it.isPrimitiveArray() } &&
      callee.kotlinFqName == FqName("kotlin.collections.reversed")
  }

  override val IrType.sizePropertyGetter
    get() = getClass()!!.getPropertyGetter("size")!!.owner

  private fun IrType.isArrayType() =
    isArray() || isPrimitiveArray() || (supportsUnsignedArrays && isUnsignedArray())

  private val IrType.getFunctionName: Name
    get() = context.ir.symbols.getWithoutBoundCheckName.let {
      if (isArrayType() && it != null) {
        it
      } else {
        OperatorNameConventions.GET
      }
    }

  override val IrType.getFunction
    get() = getClass()!!.functions.single {
      it.name == getFunctionName &&
        it.hasShape(
          dispatchReceiver = true,
          regularParameters = 1,
          parameterTypes = listOf(null, context.irBuiltIns.intType)
        )
    }
}

/**
 * Builds a [HeaderInfo] for iteration over characters in a [CharSequence].
 *
 * Note: The value for "last" can NOT be cached (i.e., stored in a variable) because the size/length can change within the loop. This means
 * that "last" is re-evaluated with each iteration of the loop.
 */
internal open class CharSequenceIterationHandler(
  context: CommonBackendContext,
  canCacheLast: Boolean = false,
) : IndexedGetIterationHandler(context, canCacheLast) {
  override fun matchIterable(expression: IrExpression): Boolean =
    expression.type.isSubtypeOfClass(context.ir.symbols.charSequence)

  // We only want to handle the known extension function for CharSequence in the standard library (top level `kotlin.text.iterator`).
  // The behavior of this iterator is well-defined and can be lowered. CharSequences can have their own iterators, either as a member or
  // extension function, and the behavior of those custom iterators is unknown.
  override fun matchIteratorCall(call: IrCall): Boolean {
    val callee = call.symbol.owner
    return callee.hasShape(extensionReceiver = true) &&
      callee.parameters[0].type.isCharSequence() &&
      callee.kotlinFqName == FqName("kotlin.text.${OperatorNameConventions.ITERATOR}")
  }

  override val IrType.sizePropertyGetter: IrSimpleFunction
    get() = context.ir.symbols.charSequence.getPropertyGetter("length")!!.owner

  override val IrType.getFunction: IrSimpleFunction
    get() = context.ir.symbols.charSequence.getSimpleFunction(OperatorNameConventions.GET.asString())!!.owner
}

/**
 * Builds a [HeaderInfo] for iteration over characters in a [String].
 *
 * Note: The value for "last" CAN be cached for Strings as they are immutable and the size/length cannot change.
 */
internal class StringIterationHandler(context: CommonBackendContext) : CharSequenceIterationHandler(context, canCacheLast = true) {
  override fun matchIterable(expression: IrExpression): Boolean =
    expression.type.isString()

  override val IrType.sizePropertyGetter: IrSimpleFunction
    get() = context.ir.symbols.string.getPropertyGetter("length")!!.owner

  override val IrType.getFunction: IrSimpleFunction
    get() = context.ir.symbols.string.getSimpleFunction(OperatorNameConventions.GET.asString())!!.owner
}
