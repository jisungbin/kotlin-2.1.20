/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter.state.reflection

import kotlin.reflect.KParameter
import kotlin.reflect.KType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.interpreter.CallInterceptor
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KTypeProxy
import org.jetbrains.kotlin.ir.interpreter.state.State
import org.jetbrains.kotlin.ir.types.classOrNull

internal class KPropertyState(
  callInterceptor: CallInterceptor,
  val property: IrProperty,
  override val irClass: IrClass,
  /**
   * Non-null values in [boundValues] are always passed as arguments to both getter and setter.
   * Other arguments (including `value`, in case of setter) have to be provided at call-site when invoking the accessor.
   */
  private val boundValues: List<State?> = emptyList(),
) : ReflectionState() {
  constructor(
    callInterceptor: CallInterceptor,
    propertyReference: IrPropertyReference,
    boundValues: List<State?>,
  ) : this(
    callInterceptor,
    propertyReference.symbol.owner,
    propertyReference.type.classOrNull!!.owner,
    boundValues
  )

  private var _returnType: KType? = null

  val getterState = property.getter?.let { createAccessorState(callInterceptor, it) }
  val setterState = property.setter?.let { createAccessorState(callInterceptor, it) }

  private fun createAccessorState(callInterceptor: CallInterceptor, accessor: IrSimpleFunction): KFunctionState {
    val irClass = callInterceptor.irBuiltIns.kFunctionN(accessor.parameters.size)
    return KFunctionState(
      accessor,
      irClass,
      callInterceptor.environment,
      boundValues,
    )
  }

  fun getParameters(callInterceptor: CallInterceptor): List<KParameter> {
    return getterState!!.getParameters(callInterceptor)
  }

  fun getReturnType(callInterceptor: CallInterceptor): KType {
    if (_returnType != null) return _returnType!!
    val kTypeIrClass = callInterceptor.environment.kTypeClass.owner
    _returnType = KTypeProxy(KTypeState(property.getter!!.returnType, kTypeIrClass), callInterceptor)
    return _returnType!!
  }

  fun isKProperty0(): Boolean = irClass.name.asString() == "KProperty0"

  fun isKProperty1(): Boolean = irClass.name.asString() == "KProperty1"

  fun isKProperty2(): Boolean = irClass.name.asString() == "KProperty2"

  fun isKMutableProperty0(): Boolean = irClass.name.asString() == "KMutableProperty0"

  fun isKMutableProperty1(): Boolean = irClass.name.asString() == "KMutableProperty1"

  fun isKMutableProperty2(): Boolean = irClass.name.asString() == "KMutableProperty2"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as KPropertyState

    if (property != other.property) return false
    if (boundValues != other.boundValues) return false

    return true
  }

  override fun hashCode(): Int {
    var result = property.hashCode()
    result = 31 * result + boundValues.hashCode()
    return result
  }

  override fun toString(): String {
    return renderProperty(property)
  }
}
