/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter.state.reflection

import kotlin.reflect.KClassifier
import kotlin.reflect.KTypeProjection
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.interpreter.CallInterceptor
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KClassProxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KTypeParameterProxy
import org.jetbrains.kotlin.ir.interpreter.proxy.reflection.KTypeProxy
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.types.Variance

internal class KTypeState(val irType: IrType, override val irClass: IrClass) : ReflectionState() {
  private var _classifier: KClassifier? = null
  private var _arguments: List<KTypeProjection>? = null

  fun getClassifier(callInterceptor: CallInterceptor): KClassifier? {
    if (_classifier != null) return _classifier!!
    _classifier = when (val classifier = irType.classifierOrFail.owner) {
      is IrClass -> KClassProxy(KClassState(classifier, callInterceptor.irBuiltIns.kClassClass.owner), callInterceptor)
      is IrTypeParameter -> {
        val kTypeParameterIrClass = callInterceptor.environment.kTypeParameterClass.owner
        KTypeParameterProxy(KTypeParameterState(classifier, kTypeParameterIrClass), callInterceptor)
      }
      else -> TODO()
    }
    return _classifier!!
  }

  fun getArguments(callInterceptor: CallInterceptor): List<KTypeProjection> {
    if (_arguments != null) return _arguments!!
    callInterceptor.environment.javaClassToIrClass += KTypeProjection::class.java to callInterceptor.environment.kTypeProjectionClass.owner
    _arguments = (irType as IrSimpleType).arguments
      .map {
        when (it.getVariance()) {
          Variance.INVARIANT -> KTypeProjection.invariant(KTypeProxy(KTypeState(it.typeOrNull!!, irClass), callInterceptor))
          Variance.IN_VARIANCE -> KTypeProjection.contravariant(KTypeProxy(KTypeState(it.typeOrNull!!, irClass), callInterceptor))
          Variance.OUT_VARIANCE -> KTypeProjection.covariant(KTypeProxy(KTypeState(it.typeOrNull!!, irClass), callInterceptor))
          null -> KTypeProjection.STAR
        }
      }
    return _arguments!!
  }

  private fun IrTypeArgument.getVariance(): Variance? {
    return when (this) {
      is IrSimpleType -> Variance.INVARIANT
      is IrTypeProjection -> this.variance
      is IrStarProjection -> null
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as KTypeState

    return irType == other.irType
  }

  override fun hashCode(): Int {
    return irType.hashCode()
  }

  override fun toString(): String {
    return irType.renderType()
  }
}
