/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.ir.builders.IrGeneratorContextBase
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.util.IrBasedDataClassMembersGenerator
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isEquals
import org.jetbrains.kotlin.ir.util.isHashCode
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isToString
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.utils.memoryOptimizedMapNotNull

class MethodsFromAnyGeneratorForLowerings(val context: LoweringContext, val irClass: IrClass, val origin: IrDeclarationOrigin) {
  private fun IrClass.addSyntheticFunction(name: String, returnType: IrType) =
    addFunction(name, returnType, startOffset = SYNTHETIC_OFFSET, endOffset = SYNTHETIC_OFFSET)

  fun createToStringMethodDeclaration(): IrSimpleFunction =
    irClass.addSyntheticFunction("toString", context.irBuiltIns.stringType).apply {
      overriddenSymbols = irClass.collectOverridenSymbols { it.isToString() }
    }

  fun createHashCodeMethodDeclaration(): IrSimpleFunction =
    irClass.addSyntheticFunction("hashCode", context.irBuiltIns.intType).apply {
      overriddenSymbols = irClass.collectOverridenSymbols { it.isHashCode() }
    }

  fun createEqualsMethodDeclaration(): IrSimpleFunction =
    irClass.addSyntheticFunction("equals", context.irBuiltIns.booleanType).apply {
      overriddenSymbols = irClass.collectOverridenSymbols { it.isEquals() }
      addValueParameter("other", context.irBuiltIns.anyNType)
    }

  companion object {
    fun IrClass.collectOverridenSymbols(predicate: (IrFunction) -> Boolean): List<IrSimpleFunctionSymbol> =
      superTypes.memoryOptimizedMapNotNull { it.getClass()?.functions?.singleOrNull(predicate)?.symbol }
  }
}

open class LoweringDataClassMemberGenerator(
  val loweringContext: LoweringContext,
  symbolTable: ReferenceSymbolTable,
  irClass: IrClass,
  origin: IrDeclarationOrigin,
  forbidDirectFieldAccess: Boolean = false,
) :
  IrBasedDataClassMembersGenerator(
    IrGeneratorContextBase(loweringContext.irBuiltIns),
    symbolTable,
    irClass,
    irClass.kotlinFqName,
    origin,
    forbidDirectFieldAccess,
    generateBodies = true,
  ) {

  override fun generateSyntheticFunctionParameterDeclarations(irFunction: IrFunction) {
    // no-op — irFunction from lowering should already have necessary parameters
  }

  override fun getProperty(irValueParameter: IrValueParameter?): IrProperty {
    error("This API shouldn't be used in lowerings")
  }

  override fun getHashCodeFunctionInfo(type: IrType): HashCodeFunctionInfo {
    val symbol = if (type.isArray() || type.isPrimitiveArray()) {
      context.irBuiltIns.dataClassArrayMemberHashCodeSymbol
    } else {
      type.classOrNull?.functions?.singleOrNull { it.owner.isHashCode() }
        ?: context.irBuiltIns.anyClass.functions.single { it.owner.name.asString() == "hashCode" }
    }
    return object : HashCodeFunctionInfo {
      override val symbol: IrSimpleFunctionSymbol = symbol

      override fun commitSubstituted(irMemberAccessExpression: IrMemberAccessExpression<*>) {}
    }
  }
}
