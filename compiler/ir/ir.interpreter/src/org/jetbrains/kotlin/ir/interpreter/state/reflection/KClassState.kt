/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter.state.reflection

import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.interpreter.CallInterceptor
import org.jetbrains.kotlin.ir.interpreter.internalName
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KMutableProperty1Proxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KMutableProperty2Proxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KProperty1Proxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KProperty2Proxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KRegularFunctionProxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KTypeParameterProxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KTypeProxy
import org.jetbrains.kotlin.ir.types.classOrNull

internal class KClassState(val classReference: IrClass, override val irClass: IrClass) : ReflectionState() {
  private var _members: Collection<KCallable<*>>? = null
  private var _constructors: Collection<KFunction<*>>? = null
  private var _typeParameters: List<KTypeParameter>? = null
  private var _supertypes: List<KType>? = null

  constructor(classReference: IrClassReference) : this(classReference.symbol.owner as IrClass, classReference.type.classOrNull!!.owner)

  fun getMembers(callInterceptor: CallInterceptor): Collection<KCallable<*>> {
    if (_members != null) return _members!!
    _members = classReference.declarations
      .filter { it !is IrClass && it !is IrConstructor }
      .map { member ->
        when (member) {
          is IrProperty -> {
            val parameterCount = member.getter!!.parameters.size
            val irClass = callInterceptor.irBuiltIns.getKPropertyClass(member.isVar, parameterCount).owner
            val propertyState = KPropertyState(callInterceptor, member, irClass)
            when (parameterCount) {
              0 -> error("\"Static\" properties are not supported")
              1 -> if (member.isVar) KMutableProperty1Proxy(propertyState, callInterceptor)
              else KProperty1Proxy(propertyState, callInterceptor)
              2 -> if (member.isVar) KMutableProperty2Proxy(propertyState, callInterceptor)
              else KProperty2Proxy(propertyState, callInterceptor)
              else -> TODO("Properties with context parameters are not supported")
            }
          }
          is IrFunction -> {
            val irClass = callInterceptor.irBuiltIns.kFunctionN(member.parameters.size)
            KRegularFunctionProxy(KFunctionState(member, irClass, callInterceptor.environment), callInterceptor)
          }
          else -> TODO()
        }
      }
    return _members!!
  }

  fun getConstructors(callInterceptor: CallInterceptor): Collection<KFunction<*>> {
    if (_constructors != null) return _constructors!!
    _constructors = classReference.declarations
      .filterIsInstance<IrConstructor>()
      .map {
        val irClass = callInterceptor.irBuiltIns.kFunctionN(it.parameters.size)
        KRegularFunctionProxy(KFunctionState(it, irClass, callInterceptor.environment), callInterceptor)
      }
    return _constructors!!
  }

  fun getTypeParameters(callInterceptor: CallInterceptor): List<KTypeParameter> {
    if (_typeParameters != null) return _typeParameters!!
    val kTypeParameterIrClass = callInterceptor.environment.kTypeParameterClass.owner
    _typeParameters =
      classReference.typeParameters.map { KTypeParameterProxy(KTypeParameterState(it, kTypeParameterIrClass), callInterceptor) }
    return _typeParameters!!
  }

  fun getSupertypes(callInterceptor: CallInterceptor): List<KType> {
    if (_supertypes != null) return _supertypes!!
    val kTypeIrClass = callInterceptor.environment.kTypeClass.owner
    _supertypes = (classReference.superTypes.map { it } + callInterceptor.irBuiltIns.anyType).toSet()
      .map { KTypeProxy(KTypeState(it, kTypeIrClass), callInterceptor) }
    return _supertypes!!
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as KClassState

    return classReference == other.classReference
  }

  override fun hashCode(): Int {
    return classReference.hashCode()
  }

  override fun toString(): String {
    return "class ${classReference.internalName()}"
  }
}
