/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.generators

import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.DescriptorMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeAliasSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.createIrClassFromDescriptor
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPureElement
import org.jetbrains.kotlin.psi.psiUtil.pureEndOffset
import org.jetbrains.kotlin.psi.psiUtil.pureStartOffset
import org.jetbrains.kotlin.psi2ir.endOffsetOrUndefined
import org.jetbrains.kotlin.psi2ir.startOffsetOrUndefined
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyExternal
import org.jetbrains.kotlin.resolve.descriptorUtil.propertyIfAccessor
import org.jetbrains.kotlin.types.KotlinType

internal class StandaloneDeclarationGenerator(private val context: GeneratorContext) {
  private val typeTranslator = context.typeTranslator
  private val symbolTable = context.symbolTable
  private val irFactory = context.irFactory

  // TODO: use this generator in psi2ir too

  fun KotlinType.toIrType() = typeTranslator.translateType(this)

  protected fun generateGlobalTypeParametersDeclarations(
    irTypeParametersOwner: IrTypeParametersContainer,
    from: List<TypeParameterDescriptor>,
  ) {
    generateTypeParameterDeclarations(irTypeParametersOwner, from) { startOffset, endOffset, typeParameterDescriptor ->
      symbolTable.descriptorExtension.declareGlobalTypeParameter(startOffset, endOffset, IrDeclarationOrigin.DEFINED, typeParameterDescriptor)
    }
  }

  fun generateScopedTypeParameterDeclarations(
    irTypeParametersOwner: IrTypeParametersContainer,
    from: List<TypeParameterDescriptor>,
  ) {
    generateTypeParameterDeclarations(irTypeParametersOwner, from) { startOffset, endOffset, typeParameterDescriptor ->
      symbolTable.descriptorExtension.declareScopedTypeParameter(startOffset, endOffset, IrDeclarationOrigin.DEFINED, typeParameterDescriptor)
    }
  }

  private fun generateTypeParameterDeclarations(
    irTypeParametersOwner: IrTypeParametersContainer,
    from: List<TypeParameterDescriptor>,
    declareTypeParameter: (Int, Int, TypeParameterDescriptor) -> IrTypeParameter,
  ) {
    irTypeParametersOwner.typeParameters += from.map { typeParameterDescriptor ->
      val ktTypeParameterDeclaration = DescriptorToSourceUtils.getSourceFromDescriptor(typeParameterDescriptor)
      val startOffset = ktTypeParameterDeclaration.startOffsetOrUndefined
      val endOffset = ktTypeParameterDeclaration.endOffsetOrUndefined
      declareTypeParameter(
        startOffset,
        endOffset,
        typeParameterDescriptor
      ).also {
        it.parent = irTypeParametersOwner
      }
    }

    for (irTypeParameter in irTypeParametersOwner.typeParameters) {
      irTypeParameter.superTypes = irTypeParameter.descriptor.upperBounds.map {
        it.toIrType()
      }
    }
  }

  fun generateClass(
    startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin, descriptor: ClassDescriptor, symbol: IrClassSymbol,
  ): IrClass {
    val irClass = irFactory.createIrClassFromDescriptor(startOffset, endOffset, origin, symbol, descriptor)

    symbolTable.withScope(irClass) {
      irClass.metadata = DescriptorMetadataSource.Class(descriptor)

      generateGlobalTypeParametersDeclarations(irClass, descriptor.declaredTypeParameters)
      irClass.superTypes = descriptor.typeConstructor.supertypes.map {
        it.toIrType()
      }

      irClass.setThisReceiverParameter(context)

      irClass.valueClassRepresentation = descriptor.valueClassRepresentation?.mapUnderlyingType { it.toIrType() as IrSimpleType }
    }

    return irClass
  }

  fun generateEnumEntry(
    startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin, descriptor: ClassDescriptor, symbol: IrEnumEntrySymbol,
  ): IrEnumEntry {
    // TODO: corresponging class?
    val irEntry = irFactory.createEnumEntry(startOffset, endOffset, origin, descriptor.name, symbol)

    return irEntry
  }

  fun generateTypeAlias(
    startOffset: Int,
    endOffset: Int,
    origin: IrDeclarationOrigin,
    descriptor: TypeAliasDescriptor,
    symbol: IrTypeAliasSymbol,
  ): IrTypeAlias = with(descriptor) {
    irFactory.createTypeAlias(
      startOffset = startOffset,
      endOffset = endOffset,
      origin = origin,
      name = name,
      visibility = visibility,
      symbol = symbol,
      isActual = isActual,
      expandedType = expandedType.toIrType()
    ).also {
      generateGlobalTypeParametersDeclarations(it, declaredTypeParameters)
    }
  }

  protected fun declareParameter(descriptor: ParameterDescriptor, ktElement: KtPureElement?, irOwnerElement: IrElement): IrValueParameter {
    return symbolTable.descriptorExtension.declareValueParameter(
      ktElement?.pureStartOffset ?: irOwnerElement.startOffset,
      ktElement?.pureEndOffset ?: irOwnerElement.endOffset,
      IrDeclarationOrigin.DEFINED,
      descriptor, descriptor.type.toIrType(),
      (descriptor as? ValueParameterDescriptor)?.varargElementType?.toIrType()
    )
  }

  protected fun generateValueParameterDeclarations(
    irFunction: IrFunction,
    functionDescriptor: FunctionDescriptor,
    defaultArgumentFactory: IrFunction.(IrValueParameter) -> IrExpressionBody?,
  ) {

    // TODO: KtElements

    irFunction.dispatchReceiverParameter = functionDescriptor.dispatchReceiverParameter?.let {
      declareParameter(it, null, irFunction)
    }

    irFunction.extensionReceiverParameter = functionDescriptor.extensionReceiverParameter?.let {
      declareParameter(it, null, irFunction)
    }

    // Declare all the value parameters up first.
    irFunction.valueParameters = functionDescriptor.valueParameters.map { valueParameterDescriptor ->
      val ktParameter = DescriptorToSourceUtils.getSourceFromDescriptor(valueParameterDescriptor) as? KtParameter
      declareParameter(valueParameterDescriptor, ktParameter, irFunction).also {
        it.defaultValue = irFunction.defaultArgumentFactory(it)
      }
    }
  }

  fun generateConstructor(
    startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin, descriptor: ClassConstructorDescriptor, symbol: IrConstructorSymbol,
    defaultArgumentFactory: IrFunction.(IrValueParameter) -> IrExpressionBody? = { null },
  ): IrConstructor {
    val irConstructor = with(descriptor) {
      irFactory.createConstructor(
        startOffset = startOffset,
        endOffset = endOffset,
        origin = origin,
        name = name,
        visibility = visibility,
        isInline = isInline,
        isExpect = isExpect,
        returnType = descriptor.returnType.toIrType(),
        symbol = symbol,
        isPrimary = isPrimary,
        isExternal = isEffectivelyExternal(),
      )
    }
    irConstructor.metadata = DescriptorMetadataSource.Function(descriptor)

    symbolTable.withScope(irConstructor) {
      val ctorTypeParameters = descriptor.typeParameters.filter { it.containingDeclaration === descriptor }
      generateScopedTypeParameterDeclarations(irConstructor, ctorTypeParameters)
      generateValueParameterDeclarations(irConstructor, descriptor, defaultArgumentFactory)
    }

    return irConstructor
  }

  protected fun generateOverridenSymbols(irFunction: IrSimpleFunction, overridens: Collection<FunctionDescriptor>) {
    irFunction.overriddenSymbols = overridens.map { symbolTable.descriptorExtension.referenceSimpleFunction(it.original) }
  }

  fun generateSimpleFunction(
    startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin, descriptor: FunctionDescriptor, symbol: IrSimpleFunctionSymbol,
    defaultArgumentFactory: IrFunction.(IrValueParameter) -> IrExpressionBody? = { null },
  ): IrSimpleFunction {
    val irFunction = with(descriptor) {
      irFactory.createSimpleFunction(
        startOffset = startOffset,
        endOffset = endOffset,
        origin = origin,
        name = name,
        visibility = visibility,
        isInline = isInline,
        isExpect = isExpect,
        returnType = null,
        modality = modality,
        symbol = symbol,
        isTailrec = isTailrec,
        isSuspend = isSuspend,
        isOperator = isOperator,
        isInfix = isInfix,
        isExternal = isEffectivelyExternal(),
      )
    }
    irFunction.metadata = DescriptorMetadataSource.Function(descriptor)

    symbolTable.withScope(irFunction) {
      generateOverridenSymbols(irFunction, descriptor.overriddenDescriptors)
      generateScopedTypeParameterDeclarations(irFunction, descriptor.propertyIfAccessor.typeParameters)
      generateValueParameterDeclarations(irFunction, descriptor, defaultArgumentFactory)
      irFunction.returnType = descriptor.returnType?.toIrType() ?: error("Expected return type $descriptor")
    }

    return irFunction
  }

  fun generateProperty(
    startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin, descriptor: PropertyDescriptor, symbol: IrPropertySymbol,
  ): IrProperty {
    val irProperty = irFactory.createProperty(
      startOffset = startOffset,
      endOffset = endOffset,
      origin = origin,
      name = descriptor.name,
      visibility = descriptor.visibility,
      modality = descriptor.modality,
      symbol = symbol,
      isVar = descriptor.isVar,
      isConst = descriptor.isConst,
      isLateinit = descriptor.isLateInit,
      isDelegated = false,
      isExternal = descriptor.isEffectivelyExternal(),
      isExpect = descriptor.isExpect,
    )

    irProperty.metadata = DescriptorMetadataSource.Property(descriptor)

    return irProperty
  }
}
