/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.buildStatement
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isOverridableOrOverrides
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * This lowering erases dispatch receiver types of virtual functions down to Any.
 *
 * WebAssembly function types are contravariant on their parameter types.
 * But since child classes are not supertypes of parents, in order for virtual method
 * reference to share the same v-table slot as parent's method, it's dispatch receiver type
 * has to be erased at least down to type of the parent class.
 *
 * Current implementation is rather conservative:
 *  - Always erases parameter type down to Any
 *  - Inserts casts back to original type in every usage of dispatch receiver.
 *
 * Possible optimisations:
 *   - Instead of erasing type to Any, erase type down to least concrete supertype containing virtual method
 *   - Don't erase type at all if bridge will be needed anyway
 *     Cast receiver in bridge. This would keep precise type for direct calls
 *   - Cast `this` and assign it to local variable if dispatch receiver is used often
 *   - Don't cast if usages of `this` don't require precise type
 *   - Always use bridge + Wasm tail call
 *
 *  Related issue: [https://github.com/WebAssembly/gc/issues/29]
 */
class EraseVirtualDispatchReceiverParametersTypes(val context: CommonBackendContext) : FileLoweringPass {
  override fun lower(irFile: IrFile) {
    irFile.acceptChildrenVoid(object : IrElementVisitorVoid {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitFunction(declaration: IrFunction) {
        lower(declaration)
        super.visitFunction(declaration)
      }
    })
  }

  fun lower(irFunction: IrFunction) {
    // Lower only functions that override other functions
    if (irFunction !is IrSimpleFunction) return
    if (!irFunction.isOverridableOrOverrides) return

    val dispatchReceiver = irFunction.dispatchReceiverParameter!!
    val originalReceiverType = dispatchReceiver.type

    // Interfaces in Wasm are erased to Any, so they already have appropriate type
    if (originalReceiverType.isInterface() || originalReceiverType.isAny()) return

    val builder = context.createIrBuilder(irFunction.symbol)
    dispatchReceiver.type = context.irBuiltIns.anyType

    val blockBody = irFunction.body as? IrBlockBody ?: return
    val classThisReceiverSymbol = irFunction.parentAsClass.thisReceiver?.symbol

    val lazyCastedThis = lazy(LazyThreadSafetyMode.NONE) {
      builder.buildStatement(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
        scope.createTemporaryVariable(
          builder.irImplicitCast(builder.irGet(dispatchReceiver), originalReceiverType),
          dispatchReceiver.name.asString()
        )
      }
    }

    // Cast receiver usages back to original type
    irFunction.transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        if (expression.type.isAny()) return expression
        if (expression.symbol != dispatchReceiver.symbol && expression.symbol != classThisReceiverSymbol) return expression
        return builder.irGet(lazyCastedThis.value)
      }
    })

    if (lazyCastedThis.isInitialized()) {
      blockBody.statements.add(0, lazyCastedThis.value)
    }
  }
}
