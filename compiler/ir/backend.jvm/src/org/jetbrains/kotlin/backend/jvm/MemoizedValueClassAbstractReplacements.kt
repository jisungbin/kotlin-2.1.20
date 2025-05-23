/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.jvm.ir.isCompiledToJvmDefault
import org.jetbrains.kotlin.backend.jvm.ir.isJvmInterface
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.allTypeParameters
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull

private var IrProperty.replacementForValueClasses: IrProperty? by irAttribute(followAttributeOwner = false)

abstract class MemoizedValueClassAbstractReplacements(
  protected val irFactory: IrFactory,
  protected val context: JvmBackendContext,
  protected val storageManager: LockBasedStorageManager,
) {
  /**
   * Get a replacement for a function or a constructor.
   */
  fun getReplacementFunction(function: IrFunction) = getReplacementFunctionImpl(function)

  protected abstract val getReplacementFunctionImpl: (IrFunction) -> IrSimpleFunction?

  protected fun IrFunction.isRemoveAtSpecialBuiltinStub() =
    origin == IrDeclarationOrigin.IR_BUILTINS_STUB &&
      name.asString() == "remove" &&
      hasShape(dispatchReceiver = true, regularParameters = 1, parameterTypes = listOf(null, context.irBuiltIns.intType))

  protected fun IrFunction.isValueClassMemberFakeOverriddenFromJvmDefaultInterfaceMethod(): Boolean {
    if (this !is IrSimpleFunction) return false
    if (!this.isFakeOverride) return false
    val parentClass = parentClassOrNull ?: return false
    require(parentClass.isValue)

    val overridden = resolveFakeOverride() ?: return false
    if (!overridden.parentAsClass.isJvmInterface) return false
    if (overridden.modality == Modality.ABSTRACT) return false

    // We have a non-abstract interface member.
    // It is a JVM default interface method if one of the following conditions are true:
    // - it is a Java method,
    // - it is a Kotlin function compiled to JVM default interface method.
    return overridden.isFromJava() || overridden.isCompiledToJvmDefault(context.config.jvmDefaultMode)
  }

  protected abstract fun createStaticReplacement(function: IrFunction): IrSimpleFunction
  protected abstract fun createMethodReplacement(function: IrFunction): IrSimpleFunction

  protected fun commonBuildReplacementInner(
    function: IrFunction,
    noFakeOverride: Boolean,
    body: IrFunction.() -> Unit,
    builderBody: IrFunctionBuilder.() -> Unit,
  ): IrSimpleFunction = irFactory.buildFun {
    updateFrom(function)
    builderBody()
    if (noFakeOverride) {
      isFakeOverride = false
    }
    returnType = function.returnType
  }.apply {
    parent = function.parent
    annotations = function.annotations
    copyTypeParameters(function.allTypeParameters)
    if (function.metadata != null) {
      metadata = function.metadata
      function.metadata = null
    }

    if (function is IrSimpleFunction) {
      copyAttributes(function)
      val propertySymbol = function.correspondingPropertySymbol
      if (propertySymbol != null) {
        val oldProperty = propertySymbol.owner
        val property = oldProperty::replacementForValueClasses.getOrSetIfNull {
          irFactory.buildProperty {
            name = oldProperty.name
            updateFrom(oldProperty)
          }.apply {
            parent = oldProperty.parent
            copyAttributes(oldProperty)
            annotations = oldProperty.annotations
            // In case this property is declared in an object in another file which is not yet lowered, its backing field will
            // be made static later. We have to handle it here though, because this new property will be saved to the cache
            // and reused when lowering the same call in all subsequent files, which would be incorrect if it was not lowered.
            val newBackingField = context.cachedDeclarations.getStaticBackingField(oldProperty) ?: oldProperty.backingField
            if (newBackingField != null) {
              context.multiFieldValueClassReplacements.getMfvcFieldNode(newBackingField)
              val fieldsToRemove = context.multiFieldValueClassReplacements.getFieldsToRemove(oldProperty.parentAsClass)
              if (newBackingField !in fieldsToRemove) {
                backingField = newBackingField
              }
            }
          }
        }
        correspondingPropertySymbol = property.symbol
        when (function) {
          oldProperty.getter -> property.getter = this
          oldProperty.setter -> property.setter = this
          else -> error("Orphaned property getter/setter: ${function.render()}")
        }
      }

      overriddenSymbols = replaceOverriddenSymbols(function)
    }

    body()
  }

  private val replaceOverriddenSymbolsImpl: (IrSimpleFunction) -> List<IrSimpleFunctionSymbol> =
    storageManager.createMemoizedFunction { irSimpleFunction ->
      irSimpleFunction.overriddenSymbols.map {
        computeOverrideReplacement(it.owner).symbol
      }
    }

  fun replaceOverriddenSymbols(function: IrSimpleFunction): List<IrSimpleFunctionSymbol> =
    if (function.overriddenSymbols.isEmpty()) listOf()
    else replaceOverriddenSymbolsImpl(function)

  abstract fun getReplacementForRegularClassConstructor(constructor: IrConstructor): IrConstructor?

  private fun computeOverrideReplacement(function: IrSimpleFunction): IrSimpleFunction =
    getReplacementFunction(function) ?: function.also {
      function.overriddenSymbols = replaceOverriddenSymbols(function)
    }

  protected fun IrSimpleFunction.overridesOnlyMethodsFromJava(): Boolean = allOverridden().all { it.isFromJava() }
}
