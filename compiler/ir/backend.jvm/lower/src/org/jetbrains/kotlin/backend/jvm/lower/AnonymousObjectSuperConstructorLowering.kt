/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlock
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.transformInPlace

/**
 * Moves evaluation of anonymous object super constructor arguments to call site. Specifically, transforms code like this:
 *
 *      object : SuperType(complexExpression) {}
 *
 * which looks like this in the IR:
 *
 *      run {
 *          class _anonymous : SuperType(complexExpression) {}
 *          _anonymous()
 *      }
 *
 * into this:
 *
 *      run {
 *          class _anonymous(arg: T) : SuperType(arg) {}
 *          _anonymous(complexExpression)
 *      }
 *
 * The reason for doing such a transformation is the inliner: if the object is declared
 * in an inline function, `complexExpression` may be a call to a lambda, which will be
 * inlined into regenerated copies of the object. Unfortunately, if that lambda captures
 * some values, the inliner does not notice that `this` is not yet initialized, and
 * attempts to read them from fields, causing a bytecode validation error.
 *
 * (TODO fix the inliner instead. Then keep this code for one more version for backwards compatibility.)
 */
@PhaseDescription(name = "AnonymousObjectSuperConstructor")
internal class AnonymousObjectSuperConstructorLowering(val context: JvmBackendContext) : IrElementTransformerVoidWithContext(),
  FileLoweringPass {
  override fun lower(irFile: IrFile) {
    irFile.transformChildrenVoid()
  }

  override fun visitBlock(expression: IrBlock): IrExpression {
    if (expression.origin != IrStatementOrigin.OBJECT_LITERAL)
      return super.visitBlock(expression)

    val objectConstructorCall = expression.statements.last() as? IrConstructorCall
      ?: throw AssertionError("object literal does not end in a constructor call")
    val objectConstructor = objectConstructorCall.symbol.owner
    val objectConstructorBody = objectConstructor.body as? IrBlockBody
      ?: throw AssertionError("object literal constructor body is not a block")

    val newArguments = mutableListOf<IrExpression>()
    fun addArgument(value: IrExpression): IrValueParameter {
      newArguments.add(value)
      return objectConstructor.addValueParameter(
        "\$super_call_param\$${newArguments.size}", value.type, JvmLoweredDeclarationOrigin.OBJECT_SUPER_CONSTRUCTOR_PARAMETER
      )
    }

    fun IrExpression.transform(remapping: Map<IrVariable, IrValueParameter>): IrExpression =
      when (this) {
        is IrConst -> this
        is IrGetValue -> IrGetValueImpl(startOffset, endOffset, remapping[symbol.owner]?.symbol ?: symbol)
        is IrTypeOperatorCall ->
          IrTypeOperatorCallImpl(startOffset, endOffset, type, operator, typeOperand, argument.transform(remapping))
        else -> IrGetValueImpl(startOffset, endOffset, addArgument(this).symbol)
      }

    fun IrDelegatingConstructorCall.transform(lift: List<IrVariable>) = apply {
      val remapping = lift.associateWith { addArgument(it.initializer!!) }
      for (i in symbol.owner.valueParameters.indices) {
        putValueArgument(i, getValueArgument(i)?.transform(remapping))
      }
    }

    objectConstructorBody.statements.transformInPlace {
      when {
        it is IrDelegatingConstructorCall -> it.transform(listOf())
        it is IrBlock && it.origin == IrStatementOrigin.ARGUMENTS_REORDERING_FOR_CALL && it.statements.last() is IrDelegatingConstructorCall ->
          // If named arguments are used, the order of evaluation may not match the order of arguments,
          // in which case IR like this is generated:
          //
          //     val _tmp1 = complexExpression1
          //     val _tmp2 = complexExpression2
          //     SuperType(_tmp2, _tmp1)
          //
          // So we lift all temporary variables to parameters.
          (it.statements.last() as IrDelegatingConstructorCall).transform(it.statements.filterIsInstance<IrVariable>())
        else -> it
      }
    }

    val classTypeParametersCount = objectConstructorCall.typeArguments.size - objectConstructorCall.symbol.owner.typeParameters.size
    context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).run {
      expression.statements[expression.statements.size - 1] = irBlock(objectConstructorCall) {
        +IrConstructorCallImpl.fromSymbolOwner(
          objectConstructorCall.startOffset, objectConstructorCall.endOffset, objectConstructorCall.type,
          objectConstructorCall.symbol, classTypeParametersCount, objectConstructorCall.origin
        ).apply {
          for (i in 0 until objectConstructorCall.valueArgumentsCount)
            putValueArgument(i, objectConstructorCall.getValueArgument(i))
          // Avoid complex expressions between `new` and `<init>`, as the inliner gets confused if
          // an argument to `<init>` is an anonymous object. Put them in variables instead.
          // See KT-21781 for an example; in short, it looks like `object : S({ ... })` in an inline function.
          for ((i, argument) in newArguments.withIndex()) {
            argument.patchDeclarationParents(currentDeclarationParent)
            putValueArgument(i + objectConstructorCall.valueArgumentsCount, irGet(irTemporary(argument)))
          }
        }
      }
    }
    return super.visitBlock(expression)
  }
}
