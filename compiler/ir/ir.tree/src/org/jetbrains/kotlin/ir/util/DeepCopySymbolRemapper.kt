/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrReplSnippet
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.symbols.IrAnonymousInitializerSymbol
import org.jetbrains.kotlin.ir.symbols.IrBindableSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFileSymbol
import org.jetbrains.kotlin.ir.symbols.IrLocalDelegatedPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrReplSnippetSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnableBlockSymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeAliasSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrConstructorSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrEnumEntrySymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrLocalDelegatedPropertySymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrPropertySymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrReplSnippetSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrReturnableBlockSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrScriptSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeAliasSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

@OptIn(ObsoleteDescriptorBasedAPI::class)
open class DeepCopySymbolRemapper(
  private val descriptorsRemapper: DescriptorsRemapper = NullDescriptorsRemapper,
) : IrElementVisitorVoid, SymbolRemapper {

  protected val classes = hashMapOf<IrClassSymbol, IrClassSymbol>()
  protected val scripts = hashMapOf<IrScriptSymbol, IrScriptSymbol>()
  protected val replSnippets = hashMapOf<IrReplSnippetSymbol, IrReplSnippetSymbol>()
  protected val constructors = hashMapOf<IrConstructorSymbol, IrConstructorSymbol>()
  protected val enumEntries = hashMapOf<IrEnumEntrySymbol, IrEnumEntrySymbol>()
  protected val externalPackageFragments = hashMapOf<IrExternalPackageFragmentSymbol, IrExternalPackageFragmentSymbol>()
  protected val fields = hashMapOf<IrFieldSymbol, IrFieldSymbol>()
  protected val files = hashMapOf<IrFileSymbol, IrFileSymbol>()
  protected val functions = hashMapOf<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>()
  protected val properties = hashMapOf<IrPropertySymbol, IrPropertySymbol>()
  protected val returnableBlocks = hashMapOf<IrReturnableBlockSymbol, IrReturnableBlockSymbol>()
  protected val typeParameters = hashMapOf<IrTypeParameterSymbol, IrTypeParameterSymbol>()
  protected val valueParameters = hashMapOf<IrValueParameterSymbol, IrValueParameterSymbol>()
  protected val variables = hashMapOf<IrVariableSymbol, IrVariableSymbol>()
  protected val localDelegatedProperties = hashMapOf<IrLocalDelegatedPropertySymbol, IrLocalDelegatedPropertySymbol>()
  protected val typeAliases = hashMapOf<IrTypeAliasSymbol, IrTypeAliasSymbol>()

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  protected inline fun <D : DeclarationDescriptor, B : IrSymbolOwner, reified S : IrBindableSymbol<D, B>>
    remapSymbol(map: MutableMap<S, S>, owner: B, createNewSymbol: (S) -> S) {
    val symbol = owner.symbol as S
    map[symbol] = createNewSymbol(symbol)
  }

  override fun visitClass(declaration: IrClass) {
    remapSymbol(classes, declaration) {
      IrClassSymbolImpl(descriptorsRemapper.remapDeclaredClass(it.descriptor))
    }
    super.visitClass(declaration)
  }

  override fun visitScript(declaration: IrScript) {
    remapSymbol(scripts, declaration) {
      IrScriptSymbolImpl(descriptorsRemapper.remapDeclaredScript(it.descriptor))
    }
    super.visitScript(declaration)
  }

  override fun visitReplSnippet(declaration: IrReplSnippet) {
    remapSymbol(replSnippets, declaration) {
      IrReplSnippetSymbolImpl(null)
    }
    super.visitReplSnippet(declaration)
  }

  override fun visitConstructor(declaration: IrConstructor) {
    remapSymbol(constructors, declaration) {
      IrConstructorSymbolImpl(descriptorsRemapper.remapDeclaredConstructor(it.descriptor))
    }
    super.visitConstructor(declaration)
  }

  override fun visitEnumEntry(declaration: IrEnumEntry) {
    remapSymbol(enumEntries, declaration) {
      IrEnumEntrySymbolImpl(descriptorsRemapper.remapDeclaredEnumEntry(it.descriptor))
    }
    super.visitEnumEntry(declaration)
  }

  override fun visitExternalPackageFragment(declaration: IrExternalPackageFragment) {
    remapSymbol(externalPackageFragments, declaration) {
      IrExternalPackageFragmentSymbolImpl(descriptorsRemapper.remapDeclaredExternalPackageFragment(it.descriptor))
    }
    super.visitExternalPackageFragment(declaration)
  }

  override fun visitField(declaration: IrField) {
    remapSymbol(fields, declaration) {
      IrFieldSymbolImpl(descriptorsRemapper.remapDeclaredField(it.descriptor))
    }
    super.visitField(declaration)
  }

  override fun visitFile(declaration: IrFile) {
    remapSymbol(files, declaration) {
      IrFileSymbolImpl(descriptorsRemapper.remapDeclaredFilePackageFragment(it.descriptor))
    }
    super.visitFile(declaration)
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction) {
    remapSymbol(functions, declaration) {
      IrSimpleFunctionSymbolImpl(descriptorsRemapper.remapDeclaredSimpleFunction(it.descriptor))
    }
    super.visitSimpleFunction(declaration)
  }

  override fun visitProperty(declaration: IrProperty) {
    remapSymbol(properties, declaration) {
      IrPropertySymbolImpl(descriptorsRemapper.remapDeclaredProperty(it.descriptor))
    }
    super.visitProperty(declaration)
  }

  override fun visitTypeParameter(declaration: IrTypeParameter) {
    remapSymbol(typeParameters, declaration) {
      IrTypeParameterSymbolImpl(descriptorsRemapper.remapDeclaredTypeParameter(it.descriptor))
    }
    super.visitTypeParameter(declaration)
  }

  override fun visitValueParameter(declaration: IrValueParameter) {
    remapSymbol(valueParameters, declaration) {
      IrValueParameterSymbolImpl(descriptorsRemapper.remapDeclaredValueParameter(it.descriptor))
    }
    super.visitValueParameter(declaration)
  }

  override fun visitVariable(declaration: IrVariable) {
    remapSymbol(variables, declaration) {
      IrVariableSymbolImpl(descriptorsRemapper.remapDeclaredVariable(it.descriptor))
    }
    super.visitVariable(declaration)
  }

  override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
    remapSymbol(localDelegatedProperties, declaration) {
      IrLocalDelegatedPropertySymbolImpl(descriptorsRemapper.remapDeclaredLocalDelegatedProperty(it.descriptor))
    }
    super.visitLocalDelegatedProperty(declaration)
  }

  override fun visitTypeAlias(declaration: IrTypeAlias) {
    remapSymbol(typeAliases, declaration) {
      IrTypeAliasSymbolImpl(descriptorsRemapper.remapDeclaredTypeAlias(it.descriptor))
    }
    super.visitTypeAlias(declaration)
  }

  override fun visitReturnableBlock(expression: IrReturnableBlock) {
    remapSymbol(returnableBlocks, expression) {
      IrReturnableBlockSymbolImpl()
    }
    super.visitReturnableBlock(expression)
  }

  private fun <T : IrSymbol> Map<T, T>.getDeclared(symbol: T) =
    getOrElse(symbol) {
      throw IllegalArgumentException("Non-remapped symbol $symbol")
    }

  private fun <T : IrSymbol> Map<T, T>.getReferenced(symbol: T) =
    getOrElse(symbol) { symbol }

  override fun getDeclaredClass(symbol: IrClassSymbol): IrClassSymbol = classes.getDeclared(symbol)

  override fun getDeclaredAnonymousInitializer(symbol: IrAnonymousInitializerSymbol): IrAnonymousInitializerSymbol =
    IrAnonymousInitializerSymbolImpl(symbol.owner.descriptor)

  override fun getDeclaredScript(symbol: IrScriptSymbol): IrScriptSymbol = scripts.getDeclared(symbol)
  override fun getDeclaredReplSnippet(symbol: IrReplSnippetSymbol): IrReplSnippetSymbol = replSnippets.getDeclared(symbol)

  override fun getDeclaredSimpleFunction(symbol: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol = functions.getDeclared(symbol)
  override fun getDeclaredProperty(symbol: IrPropertySymbol): IrPropertySymbol = properties.getDeclared(symbol)
  override fun getDeclaredField(symbol: IrFieldSymbol): IrFieldSymbol = fields.getDeclared(symbol)
  override fun getDeclaredFile(symbol: IrFileSymbol): IrFileSymbol = files.getDeclared(symbol)
  override fun getDeclaredConstructor(symbol: IrConstructorSymbol): IrConstructorSymbol = constructors.getDeclared(symbol)
  override fun getDeclaredEnumEntry(symbol: IrEnumEntrySymbol): IrEnumEntrySymbol = enumEntries.getDeclared(symbol)
  override fun getDeclaredExternalPackageFragment(symbol: IrExternalPackageFragmentSymbol): IrExternalPackageFragmentSymbol =
    externalPackageFragments.getDeclared(symbol)

  override fun getDeclaredVariable(symbol: IrVariableSymbol): IrVariableSymbol = variables.getDeclared(symbol)
  override fun getDeclaredTypeParameter(symbol: IrTypeParameterSymbol): IrTypeParameterSymbol = typeParameters.getDeclared(symbol)
  override fun getDeclaredValueParameter(symbol: IrValueParameterSymbol): IrValueParameterSymbol = valueParameters.getDeclared(symbol)
  override fun getDeclaredLocalDelegatedProperty(symbol: IrLocalDelegatedPropertySymbol): IrLocalDelegatedPropertySymbol =
    localDelegatedProperties.getDeclared(symbol)

  override fun getDeclaredTypeAlias(symbol: IrTypeAliasSymbol): IrTypeAliasSymbol = typeAliases.getDeclared(symbol)

  override fun getDeclaredReturnableBlock(symbol: IrReturnableBlockSymbol) = returnableBlocks.getDeclared(symbol)

  override fun getReferencedClass(symbol: IrClassSymbol): IrClassSymbol = classes.getReferenced(symbol)
  override fun getReferencedScript(symbol: IrScriptSymbol): IrScriptSymbol = scripts.getReferenced(symbol)
  override fun getReferencedEnumEntry(symbol: IrEnumEntrySymbol): IrEnumEntrySymbol = enumEntries.getReferenced(symbol)
  override fun getReferencedVariable(symbol: IrVariableSymbol): IrVariableSymbol = variables.getReferenced(symbol)
  override fun getReferencedValueParameter(symbol: IrValueParameterSymbol): IrValueSymbol = valueParameters.getReferenced(symbol)
  override fun getReferencedLocalDelegatedProperty(symbol: IrLocalDelegatedPropertySymbol): IrLocalDelegatedPropertySymbol =
    localDelegatedProperties.getReferenced(symbol)

  override fun getReferencedField(symbol: IrFieldSymbol): IrFieldSymbol = fields.getReferenced(symbol)
  override fun getReferencedConstructor(symbol: IrConstructorSymbol): IrConstructorSymbol = constructors.getReferenced(symbol)
  override fun getReferencedSimpleFunction(symbol: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol = functions.getReferenced(symbol)
  override fun getReferencedProperty(symbol: IrPropertySymbol): IrPropertySymbol = properties.getReferenced(symbol)

  override fun getReferencedReturnableBlock(symbol: IrReturnableBlockSymbol): IrReturnTargetSymbol =
    returnableBlocks.getReferenced(symbol)

  override fun getReferencedTypeParameter(symbol: IrTypeParameterSymbol): IrClassifierSymbol = typeParameters.getReferenced(symbol)

  override fun getReferencedTypeAlias(symbol: IrTypeAliasSymbol): IrTypeAliasSymbol = typeAliases.getReferenced(symbol)
}
