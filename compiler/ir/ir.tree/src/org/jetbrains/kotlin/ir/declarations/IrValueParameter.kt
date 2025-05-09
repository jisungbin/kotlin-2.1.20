/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.CompilerVersionOfApiDeprecation
import org.jetbrains.kotlin.DeprecatedCompilerApi
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor

// This class is not autogenerated to for the sake refactoring IR parameters - see KT-68003.
// However, it must be kept in sync with [org.jetbrains.kotlin.ir.generator.IrTree.valueParameter].
abstract class IrValueParameter : IrDeclarationBase(), IrValueDeclaration {
  @ObsoleteDescriptorBasedAPI
  abstract override val descriptor: ParameterDescriptor

  abstract val isAssignable: Boolean

  abstract override val symbol: IrValueParameterSymbol


  /**
   * Severely deprecated, kept only for source compatibility.
   *
   * Please replace with [indexInOldValueParameters],
   * or migrate to new parameter API with [indexInParameters].
   */
  @DeprecatedForRemovalCompilerApi(CompilerVersionOfApiDeprecation._2_1_20)
  var index: Int by ::indexInOldValueParameters
    @DelicateIrParameterIndexSetter
    set

  /**
   * Index of this parameter in [IrFunction.valueParameters] list, or -1 if it is not present in that list
   * (which often means it is either [IrFunction.dispatchReceiverParameter] or [IrFunction.extensionReceiverParameter]).
   *
   * It is automatically updated when adding or removing from [IrFunction.parameters].
   * Only in rare cases it may need to be set manually.
   *
   * ##### This is a deprecated API
   * Instead, use [IrFunction.parameters] together with [indexInParameters].
   *
   * Details on the API migration: KT-68003
   */
  @DeprecatedCompilerApi(CompilerVersionOfApiDeprecation._2_1_20)
  var indexInOldValueParameters: Int = -1
    @DelicateIrParameterIndexSetter
    set

  /**
   * Index of this parameter in [IrFunction.parameters] list, or -1 if not present in that list.
   *
   * It is automatically updated when adding or removing from [IrFunction.parameters].
   * Only in rare cases it may need to be set manually.
   *
   * Note: after removal of old parameter API (KT-68003), once `index` property is removed, this property
   * is going to be renamed to back to `index`.
   */
  var indexInParameters: Int = -1
    @DelicateIrParameterIndexSetter
    set

  // When using old parameter API, kind is assigned automatically when adding a parameter
  // to a function. However, up until that moment it is `null`.
  // When using new API (IrFunction.parameters), `kind` must be set explicitly before adding
  // a parameter to a function, such as by filling IrFactory.createValueParameter(kind = ...).
  // It is expected that after migration, all parameters will have a proper kind set upon creation,
  // and the nullable `_kind` will be dropped.
  // Before that happens, please use `_kind` in lower level code, that might see a not-yet-attached parameter,
  // and non-nullable `kind` otherwise, which e.g. enables exhaustive when.
  internal var _kind: IrParameterKind? = null
    set(value) {
      if (field === value) return
      field = value

      // When a parameter is already in a function, changing its kind e.g. from regular parameter to
      // a receiver will make it not appear in valueParameters anymore, so it and subsequent parameters
      // will have different index in that list. We try to update it.
      // This only affects old-API index, new API is alright.
      (_parent as? IrFunction)?.reindexValueParameters()
    }
  var kind: IrParameterKind
    get() = _kind!!
    set(value) {
      _kind = value
    }

  abstract var varargElementType: IrType?

  abstract var isCrossinline: Boolean

  abstract var isNoinline: Boolean

  /**
   * If `true`, the value parameter does not participate in [IdSignature] computation.
   *
   * This is a workaround that is needed for better support of compiler plugins.
   * Suppose you have the following code and some IR plugin that adds a value parameter to functions
   * marked with the `@PluginMarker` annotation.
   * ```kotlin
   * @PluginMarker
   * fun foo(defined: Int) { /* ... */ }
   * ```
   *
   * Suppose that after applying the plugin the function is changed to:
   * ```kotlin
   * @PluginMarker
   * fun foo(defined: Int, $extra: String) { /* ... */ }
   * ```
   *
   * If a compiler plugin adds parameters to an [IrFunction],
   * the representations of the function in the frontend and in the backend may diverge, potentially causing signature mismatch and
   * linkage errors (see [KT-40980](https://youtrack.jetbrains.com/issue/KT-40980)).
   * We wouldn't want IR plugins to affect the frontend representation, since in an IDE you'd want to be able to see those
   * declarations in their original form (without the `$extra` parameter).
   *
   * To fix this problem, [isHidden] was introduced.
   *
   * TODO: consider dropping [isHidden] if it isn't used by any known plugin.
   */
  abstract var isHidden: Boolean

  abstract var defaultValue: IrExpressionBody?

  override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R =
    visitor.visitValueParameter(this, data)

  override fun <D> transform(transformer: IrElementTransformer<D>, data: D): IrValueParameter =
    accept(transformer, data) as IrValueParameter

  override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
    defaultValue?.accept(visitor, data)
  }

  override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
    defaultValue = defaultValue?.transform(transformer, data)
  }
}
