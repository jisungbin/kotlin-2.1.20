/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.utils

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.hasEqualFqName
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object JsAnnotations {
  val jsModuleFqn = FqName("kotlin.js.JsModule")
  val jsNonModuleFqn = FqName("kotlin.js.JsNonModule")
  val jsNameFqn = FqName("kotlin.js.JsName")
  val jsFileNameFqn = FqName("kotlin.js.JsFileName")
  val jsQualifierFqn = FqName("kotlin.js.JsQualifier")
  val jsExportFqn = FqName("kotlin.js.JsExport")
  val jsImplicitExportFqn = FqName("kotlin.js.JsImplicitExport")
  val jsExportIgnoreFqn = FqName("kotlin.js.JsExport.Ignore")
  val jsNativeGetter = FqName("kotlin.js.nativeGetter")
  val jsNativeSetter = FqName("kotlin.js.nativeSetter")
  val jsNativeInvoke = FqName("kotlin.js.nativeInvoke")
  val JsPolyfillFqn = FqName("kotlin.js.JsPolyfill")
  val jsGeneratorFqn = FqName("kotlin.js.JsGenerator")
}

fun IrConstructorCall.getSingleConstStringArgument() =
  (arguments[0] as IrConst).value as String

fun IrConstructorCall.getSingleConstBooleanArgument() =
  (arguments[0] as IrConst).value as Boolean

fun IrAnnotationContainer.getJsModule(): String? =
  getAnnotation(JsAnnotations.jsModuleFqn)?.getSingleConstStringArgument()

fun IrAnnotationContainer.isJsNonModule(): Boolean =
  hasAnnotation(JsAnnotations.jsNonModuleFqn)

fun IrAnnotationContainer.getJsQualifier(): String? =
  getAnnotation(JsAnnotations.jsQualifierFqn)?.getSingleConstStringArgument()

fun IrFile.getJsFileName(): String? =
  getAnnotation(JsAnnotations.jsFileNameFqn)?.getSingleConstStringArgument()

fun IrAnnotationContainer.getJsName(): String? =
  getAnnotation(JsAnnotations.jsNameFqn)?.getSingleConstStringArgument()

fun IrAnnotationContainer.getDeprecated(): String? =
  getAnnotation(StandardNames.FqNames.deprecated)?.getSingleConstStringArgument()

fun IrAnnotationContainer.hasJsPolyfill(): Boolean =
  hasAnnotation(JsAnnotations.JsPolyfillFqn)

fun IrAnnotationContainer.isJsExport(): Boolean =
  hasAnnotation(JsAnnotations.jsExportFqn)

fun IrAnnotationContainer.isJsImplicitExport(): Boolean =
  hasAnnotation(JsAnnotations.jsImplicitExportFqn)

fun IrAnnotationContainer.couldBeConvertedToExplicitExport(): Boolean? =
  getAnnotation(JsAnnotations.jsImplicitExportFqn)?.getSingleConstBooleanArgument()

fun IrAnnotationContainer.isJsExportIgnore(): Boolean =
  annotations.any {
    // Using `IrSymbol.hasEqualFqName(FqName)` instead of a usual `hasAnnotation` call, because `JsExport.Ignore` is a nested class,
    // whose FQ name cannot be computed by traversing IR tree parents because it lacks `JsExport` for some reason.
    it.symbol.owner.parentAsClass.symbol.hasEqualFqName(JsAnnotations.jsExportIgnoreFqn)
  }

fun IrAnnotationContainer.isJsNativeGetter(): Boolean = hasAnnotation(JsAnnotations.jsNativeGetter)

fun IrAnnotationContainer.isJsNativeSetter(): Boolean = hasAnnotation(JsAnnotations.jsNativeSetter)

fun IrAnnotationContainer.isJsNativeInvoke(): Boolean = hasAnnotation(JsAnnotations.jsNativeInvoke)

private fun IrOverridableDeclaration<*>.dfsOverridableJsNameOrNull(): String? {
  for (overriddenSymbol in overriddenSymbols) {
    val symbolOwner = overriddenSymbol.owner
    if (symbolOwner is IrAnnotationContainer) {
      symbolOwner.getJsName()?.let { return it }
    }
    if (symbolOwner is IrOverridableDeclaration<*>) {
      symbolOwner.dfsOverridableJsNameOrNull()?.let { return it }
    }
  }
  return null
}

fun IrDeclarationWithName.getJsNameForOverriddenDeclaration(): String? {
  val jsName = getJsName()

  return when {
    jsName != null -> jsName
    this is IrOverridableDeclaration<*> -> dfsOverridableJsNameOrNull()
    else -> null
  }
}

fun IrDeclarationWithName.getJsNameOrKotlinName(): Name =
  when (val jsName = getJsNameForOverriddenDeclaration()) {
    null -> name
    else -> Name.identifier(jsName)
  }

private val associatedObjectKeyAnnotationFqName = FqName("kotlin.reflect.AssociatedObjectKey")

val IrClass.isAssociatedObjectAnnotatedAnnotation: Boolean
  get() = isAnnotationClass && annotations.any { it.symbol.owner.constructedClass.fqNameWhenAvailable == associatedObjectKeyAnnotationFqName }

fun IrConstructorCall.associatedObject(): IrClass? {
  if (!symbol.owner.constructedClass.isAssociatedObjectAnnotatedAnnotation) return null
  val klass = ((arguments[0] as? IrClassReference)?.symbol as? IrClassSymbol)?.owner ?: return null
  return if (klass.isObject) klass else null
}
