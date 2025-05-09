/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower.coroutines

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.functionWithContinuations
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.suspendFunction
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.makeTypeParameterSubstitutionMap
import org.jetbrains.kotlin.ir.util.previousOffset
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.memoryOptimizedPlus

/**
 * Replaces suspend functions with regular non-suspend functions with additional
 * continuation parameter `$cont` of type [kotlin.coroutines.Continuation].
 *
 * Replaces return type with `Any?` or `Any` (for non-nullable types) to indicate that suspend
 * functions can return special values like [kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED]
 * which might not be a subtype of original return type.
 */
class AddContinuationToNonLocalSuspendFunctionsLowering(val context: CommonBackendContext) : DeclarationTransformer {
  override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? =
    if (declaration is IrSimpleFunction && declaration.isSuspend) {
      listOf(transformSuspendFunction(context, declaration))
    } else {
      null
    }
}

/**
 * Similar to [AddContinuationToNonLocalSuspendFunctionsLowering] but processes local functions.
 * Useful for Kotlin/JS IR backend which keeps local declarations up until code generation.
 */
class AddContinuationToLocalSuspendFunctionsLowering(val context: CommonBackendContext) : BodyLoweringPass {
  override fun lower(irBody: IrBody, container: IrDeclaration) {
    irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        declaration.transformChildrenVoid()
        return if (declaration.isSuspend) {
          transformSuspendFunction(context, declaration)
        } else {
          declaration
        }
      }
    })
  }
}


private fun transformSuspendFunction(context: CommonBackendContext, function: IrSimpleFunction): IrSimpleFunction {
  val newFunctionWithContinuation = function.getOrCreateFunctionWithContinuationStub(context)
  // Using custom mapping because number of parameters doesn't match
  val parameterMapping: Map<IrValueParameter, IrValueParameter> =
    function.parameters.zip(newFunctionWithContinuation.parameters).toMap()
  val newBody = function.moveBodyTo(newFunctionWithContinuation, parameterMapping)
  for ((old, new) in parameterMapping.entries) {
    new.defaultValue = old.defaultValue?.transform(VariableRemapper(parameterMapping), null)
  }

  // Since we are changing return type to Any, function can no longer return unit implicitly.
  if (
    function.returnType == context.irBuiltIns.unitType &&
    newBody is IrBlockBody &&
    newBody.statements.lastOrNull() !is IrReturn
  ) {
    // Adding explicit return of Unit.
    // Set both offsets of the IrReturn to body.endOffset.previousOffset (check the description of the `previousOffset` method)
    // so that a breakpoint set at the closing brace of a lambda expression could be hit.
    newBody.statements += context.createIrBuilder(
      newFunctionWithContinuation.symbol,
      startOffset = newBody.endOffset.previousOffset,
      endOffset = newBody.endOffset.previousOffset
    ).irReturnUnit()
  }

  newFunctionWithContinuation.body = newBody
  return newFunctionWithContinuation
}


fun IrSimpleFunction.getOrCreateFunctionWithContinuationStub(context: CommonBackendContext): IrSimpleFunction {
  return this.functionWithContinuations ?: createSuspendFunctionStub(context).also {
    functionWithContinuations = it
    it.suspendFunction = this
  }
}

private fun IrSimpleFunction.createSuspendFunctionStub(context: CommonBackendContext): IrSimpleFunction {
  require(this.isSuspend) { "$fqNameWhenAvailable should be a suspend function to create version with contunation" }
  return factory.buildFun {
    updateFrom(this@createSuspendFunctionStub)
    isSuspend = false
    name = this@createSuspendFunctionStub.name
    origin = IrDeclarationOrigin.LOWERED_SUSPEND_FUNCTION
    returnType = loweredSuspendFunctionReturnType(this@createSuspendFunctionStub, context.irBuiltIns)
  }.also { function ->
    function.parent = parent

    function.metadata = metadata

    function.copyAnnotationsFrom(this)
    function.copyAttributes(this)
    function.copyTypeParametersFrom(this)
    val substitutionMap = makeTypeParameterSubstitutionMap(this, function)
    function.copyParametersFrom(this, substitutionMap)

    function.overriddenSymbols = function.overriddenSymbols memoryOptimizedPlus overriddenSymbols.map {
      factory.stageController.restrictTo(it.owner) {
        it.owner.getOrCreateFunctionWithContinuationStub(context).symbol
      }
    }

    function.addValueParameter {
      startOffset = function.startOffset
      endOffset = function.endOffset
      origin = IrDeclarationOrigin.CONTINUATION
      name = Name.identifier("\$completion")
      type = continuationType(context).substitute(substitutionMap)
    }
  }
}

private fun IrFunction.continuationType(context: CommonBackendContext): IrType {
  return context.ir.symbols.continuationClass.typeWith(returnType)
}

fun loweredSuspendFunctionReturnType(function: IrFunction, irBuiltIns: IrBuiltIns): IrType =
  if (function.returnType.isNullable()) irBuiltIns.anyNType else irBuiltIns.anyType
