/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter.proxy.reflection

import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVisibility
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.interpreter.CallInterceptor
import org.jetbrains.kotlin.ir.interpreter.state.hasTheSameFieldsWith
import org.jetbrains.kotlin.ir.interpreter.state.reflection.KFunctionState
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.statements

internal abstract class KFunctionProxy<R>(
  final override val state: KFunctionState,
  final override val callInterceptor: CallInterceptor,
) : ReflectionProxy, KFunction<R> {
  val arity: Int = state.getArity() ?: BuiltInFunctionArity.BIG_ARITY

  override val isInline: Boolean
    get() = state.irFunction.isInline
  override val isExternal: Boolean
    get() = state.irFunction.isExternal
  override val isOperator: Boolean
    get() = state.irFunction is IrSimpleFunction && state.irFunction.isOperator
  override val isInfix: Boolean
    get() = state.irFunction is IrSimpleFunction && state.irFunction.isInfix
  override val name: String
    get() = state.irFunction.name.asString()


  override val annotations: List<Annotation>
    get() = TODO("Not yet implemented")
  override val parameters: List<KParameter>
    get() = state.getParameters(callInterceptor)
  override val returnType: KType
    get() = state.getReturnType(callInterceptor)
  override val typeParameters: List<KTypeParameter>
    get() = state.getTypeParameters(callInterceptor)

  override fun call(vararg args: Any?): R {
    // TODO check arity
    val invokeFunction = state.invokeSymbol.owner
    val valueArguments = listOf(state) + invokeFunction.nonDispatchParameters.mapIndexed { argIndex, parameter ->
      environment.convertToState(args[argIndex], parameter.type)
    }
    @Suppress("UNCHECKED_CAST")
    return callInterceptor.interceptProxy(invokeFunction, valueArguments) as R
  }

  override fun callBy(args: Map<KParameter, Any?>): R {
    TODO("Not yet implemented")
  }

  override val visibility: KVisibility?
    get() = state.irFunction.visibility.toKVisibility()
  override val isFinal: Boolean
    get() = state.irFunction is IrSimpleFunction && state.irFunction.modality == Modality.FINAL
  override val isOpen: Boolean
    get() = state.irFunction is IrSimpleFunction && state.irFunction.modality == Modality.OPEN
  override val isAbstract: Boolean
    get() = state.irFunction is IrSimpleFunction && state.irFunction.modality == Modality.ABSTRACT
  override val isSuspend: Boolean
    get() = state.irFunction.isSuspend

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KFunctionProxy<*>) return false
    if (arity != other.arity || isSuspend != other.isSuspend) return false
    // SAM wrappers for Java do not implement equals
    if (this.state.funInterface?.classOrNull?.owner?.origin == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB) return this.state === other.state
    if (!state.hasTheSameFieldsWith(other.state)) return false

    return when {
      state.irFunction.isAdapter() && other.state.irFunction.isAdapter() -> state.irFunction.equalsByAdapteeCall(other.state.irFunction)
      else -> state.irFunction == other.state.irFunction
    }
  }

  override fun hashCode(): Int {
    return when {
      state.irFunction.isAdapter() -> state.irFunction.getAdapteeCallSymbol()!!.hashCode()
      else -> state.irFunction.hashCode()
    }
  }

  override fun toString(): String {
    return state.toString()
  }

  private fun IrFunction.isAdapter() = this.origin == IrDeclarationOrigin.ADAPTER_FOR_CALLABLE_REFERENCE

  private fun IrFunction.getAdapteeCallSymbol(): IrFunctionSymbol? {
    if (!this.isAdapter()) return null

    val call = when (val statement = this.body!!.statements.single()) {
      is IrTypeOperatorCall -> statement.argument
      is IrReturn -> statement.value
      else -> statement
    }
    return (call as? IrFunctionAccessExpression)?.symbol
  }

  private fun IrFunction.equalsByAdapteeCall(other: IrFunction): Boolean {
    if (!this.isAdapter() || !other.isAdapter()) return false

    val statement = this.body!!.statements.single()
    val otherStatement = other.body!!.statements.single()

    val (thisArg, otherArg) = when (statement) {
      is IrTypeOperatorCall -> {
        if (otherStatement !is IrTypeOperatorCall) return false
        Pair(statement.argument, otherStatement.argument)
      }
      is IrReturn -> {
        if (otherStatement !is IrReturn) return false
        Pair(statement.value, otherStatement.value)
      }
      else -> Pair(statement, otherStatement)
    }

    if (thisArg !is IrFunctionAccessExpression || otherArg !is IrFunctionAccessExpression) return false
    if (thisArg.symbol != otherArg.symbol) return false

    return true
  }
}

internal class KRegularFunctionProxy(
  state: KFunctionState, callInterceptor: CallInterceptor,
) : KFunctionProxy<Any?>(state, callInterceptor), FunctionWithAllInvokes
