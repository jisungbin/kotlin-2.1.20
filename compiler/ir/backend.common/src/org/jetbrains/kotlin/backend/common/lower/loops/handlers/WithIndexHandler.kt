/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower.loops.handlers

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.lower.loops.HeaderInfo
import org.jetbrains.kotlin.backend.common.lower.loops.HeaderInfoHandler
import org.jetbrains.kotlin.backend.common.lower.loops.NestedHeaderInfoBuilderForWithIndex
import org.jetbrains.kotlin.backend.common.lower.loops.WithIndexHeaderInfo
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isIterable
import org.jetbrains.kotlin.ir.types.isSequence
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.isUnsignedArray
import org.jetbrains.kotlin.ir.util.kotlinFqName

/** Builds a [HeaderInfo] for calls to `withIndex()`. */
internal class WithIndexHandler(
  private val context: CommonBackendContext,
  private val visitor: NestedHeaderInfoBuilderForWithIndex,
) : HeaderInfoHandler<IrCall, Nothing?> {
  private val supportsUnsignedArrays = context.optimizeLoopsOverUnsignedArrays

  override fun matchIterable(expression: IrCall): Boolean {
    val callee = expression.symbol.owner
    if (!callee.hasShape(extensionReceiver = true) || callee.name.asString() != "withIndex") return false

    val extensionReceiverParameter = callee.parameters[0]
    return when (callee.kotlinFqName.asString()) {
      "kotlin.collections.withIndex" ->
        extensionReceiverParameter.type.run {
          isArray() || isPrimitiveArray() || isIterable() ||
            (supportsUnsignedArrays && isUnsignedArray())
        }
      "kotlin.text.withIndex" ->
        extensionReceiverParameter.type.isSubtypeOfClass(context.ir.symbols.charSequence)
      "kotlin.sequences.withIndex" ->
        extensionReceiverParameter.type.isSequence()
      else -> false
    }
  }

  override fun build(expression: IrCall, data: Nothing?, scopeOwner: IrSymbol): HeaderInfo? {
    // WithIndexHeaderInfo is a composite that contains the HeaderInfo for the underlying iterable (if any).
    val nestedInfo = expression.arguments[0]!!.accept(visitor, null) ?: return null

    // We cannot lower `iterable.withIndex().withIndex()`.
    // NestedHeaderInfoBuilderForWithIndex should not be yielding a WithIndexHeaderInfo, hence the assert.
    assert(nestedInfo !is WithIndexHeaderInfo)

    return WithIndexHeaderInfo(nestedInfo)
  }
}
