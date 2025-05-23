/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.export

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.lower.isBuiltInClass
import org.jetbrains.kotlin.ir.backend.js.lower.isStdLibClass
import org.jetbrains.kotlin.ir.backend.js.utils.isJsImplicitExport
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.irError
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.util.superTypes

private typealias SubstitutionMap = Map<IrTypeParameterSymbol, IrType>

class TransitiveExportCollector(val context: JsIrBackendContext) {
  private val typesCaches = hashMapOf<ClassWithAppliedArguments, Set<IrType>>()

  fun collectSuperTypesTransitiveHierarchyFor(type: IrSimpleType): Set<IrType> {
    val classSymbol = type.classOrNull ?: return emptySet()

    return typesCaches.getOrPut(ClassWithAppliedArguments(classSymbol, type.arguments)) {
      type.collectSuperTypesTransitiveHierarchy(type.calculateTypeSubstitutionMap(emptyMap()))
    }
  }

  private fun IrSimpleType.collectSuperTypesTransitiveHierarchy(typeSubstitutionMap: SubstitutionMap): Set<IrType> {
    val classifier = classOrNull ?: return emptySet()

    return classifier.superTypes()
      .flatMap { (it as? IrSimpleType)?.collectTransitiveHierarchy(typeSubstitutionMap) ?: emptySet() }
      .toSet()
  }

  private fun IrSimpleType.findNearestExportedClass(typeSubstitutionMap: SubstitutionMap): IrSimpleType? {
    val classifier = classifier as? IrClassSymbol ?: return null
    if (classifier.owner.isExported(context)) return substitute(typeSubstitutionMap) as IrSimpleType

    return classifier.superTypes()
      .firstNotNullOfOrNull { (it as? IrSimpleType)?.findNearestExportedClass(typeSubstitutionMap) }
  }

  private fun IrSimpleType.collectTransitiveHierarchy(typeSubstitutionMap: SubstitutionMap): Set<IrType> {
    val owner = classifier.owner as? IrClass ?: return emptySet()
    val substitutionMap = calculateTypeSubstitutionMap(typeSubstitutionMap)

    return when {
      isBuiltInClass(owner) || isStdLibClass(owner) -> emptySet()
      owner.isExported(context) -> setOf(substitute(substitutionMap))
      owner.isJsImplicitExport() -> setOfNotNull(
        substitute(typeSubstitutionMap),
        takeIf { !owner.isInterface }?.findNearestExportedClass(substitutionMap)
      )

      else -> collectSuperTypesTransitiveHierarchy(substitutionMap)
    }
  }

  private fun IrTypeArgument.getSubstitution(typeSubstitutionMap: SubstitutionMap): IrType {
    return when (this) {
      is IrType -> substitute(typeSubstitutionMap)
      is IrTypeProjection -> type.substitute(typeSubstitutionMap)
      is IrStarProjection -> context.irBuiltIns.anyNType
    }
  }

  private fun IrSimpleType.calculateTypeSubstitutionMap(typeSubstitutionMap: SubstitutionMap): SubstitutionMap {
    val classifier = classOrNull
      ?: irError("Unexpected classifier for collecting transitive hierarchy") {
        withIrEntry("classifier.owner", classifier.owner)
      }

    return typeSubstitutionMap + classifier.owner.typeParameters.zip(arguments).associate {
      it.first.symbol to it.second.getSubstitution(typeSubstitutionMap)
    }
  }

  private data class ClassWithAppliedArguments(val classSymbol: IrClassSymbol, val appliedArguments: List<IrTypeArgument>)
}
