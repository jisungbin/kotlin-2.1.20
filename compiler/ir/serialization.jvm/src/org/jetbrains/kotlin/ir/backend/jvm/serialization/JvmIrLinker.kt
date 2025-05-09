/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.jvm.serialization

import org.jetbrains.kotlin.backend.common.linkage.partial.PartialLinkageSupportForLinker
import org.jetbrains.kotlin.backend.common.overrides.IrLinkerFakeOverrideProvider
import org.jetbrains.kotlin.backend.common.serialization.BasicIrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.CurrentModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.DescriptorByIdSignatureFinderImpl
import org.jetbrains.kotlin.backend.common.serialization.DeserializationStrategy
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializerKind
import org.jetbrains.kotlin.backend.common.serialization.KotlinIrLinker
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeserializedDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.util.DeclarationStubGenerator
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.library.IrLibrary
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.metadata.KlibModuleOrigin
import org.jetbrains.kotlin.load.java.descriptors.JavaCallableMemberDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaPackageFragment
import org.jetbrains.kotlin.name.Name

@OptIn(ObsoleteDescriptorBasedAPI::class)
class JvmIrLinker(
  currentModule: ModuleDescriptor?,
  messageCollector: MessageCollector,
  typeSystem: IrTypeSystemContext,
  symbolTable: SymbolTable,
  private val stubGenerator: DeclarationStubGenerator,
  private val manglerDesc: JvmDescriptorMangler,
  private val enableIdSignatures: Boolean,
) : KotlinIrLinker(currentModule, messageCollector, typeSystem.irBuiltIns, symbolTable, emptyList()) {

  override val fakeOverrideBuilder = IrLinkerFakeOverrideProvider(
    linker = this,
    symbolTable = symbolTable,
    mangler = JvmIrMangler,
    typeSystem = typeSystem,
    friendModules = emptyMap(), // TODO: provide friend modules
    partialLinkageSupport = PartialLinkageSupportForLinker.DISABLED
  )

  private val javaName = Name.identifier("java")

  override fun isBuiltInModule(moduleDescriptor: ModuleDescriptor): Boolean =
    moduleDescriptor.name.asString().startsWith("<dependencies of ")

  // TODO: implement special Java deserializer
  override fun createModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: KotlinLibrary?, strategyResolver: (String) -> DeserializationStrategy): IrModuleDeserializer {
    if (klib != null) {
      assert(moduleDescriptor.getCapability(KlibModuleOrigin.CAPABILITY) != null)
      return JvmModuleDeserializer(moduleDescriptor, klib, klib.versions.abiVersion ?: KotlinAbiVersion.CURRENT, strategyResolver)
    }

    return MetadataJVMModuleDeserializer(moduleDescriptor, emptyList())
  }

  private inner class JvmModuleDeserializer(moduleDescriptor: ModuleDescriptor, klib: IrLibrary, libraryAbiVersion: KotlinAbiVersion, strategyResolver: (String) -> DeserializationStrategy) :
    BasicIrModuleDeserializer(this, moduleDescriptor, klib, strategyResolver, libraryAbiVersion)

  private fun DeclarationDescriptor.isJavaDescriptor(): Boolean {
    if (this is PackageFragmentDescriptor) {
      return this is LazyJavaPackageFragment || fqName.startsWith(javaName)
    }

    return this is JavaClassDescriptor || this is JavaCallableMemberDescriptor || (containingDeclaration?.isJavaDescriptor() == true)
  }

  private fun DeclarationDescriptor.isCleanDescriptor(): Boolean {
    if (this is PropertyAccessorDescriptor) return correspondingProperty.isCleanDescriptor()
    return this is DeserializedDescriptor
  }

  override fun platformSpecificSymbol(symbol: IrSymbol): Boolean {
    return symbol.descriptor.isJavaDescriptor()
  }

  private fun declareJavaFieldStub(symbol: IrFieldSymbol): IrField {
    return with(stubGenerator) {
      val old = stubGenerator.unboundSymbolGeneration
      try {
        stubGenerator.unboundSymbolGeneration = true
        generateFieldStub(symbol.descriptor)
      } finally {
        stubGenerator.unboundSymbolGeneration = old
      }
    }
  }

  override fun getDeclaration(symbol: IrSymbol): IrDeclaration? =
    deserializeOrResolveDeclaration(symbol, !enableIdSignatures)

  override fun createCurrentModuleDeserializer(moduleFragment: IrModuleFragment, dependencies: Collection<IrModuleDeserializer>): IrModuleDeserializer =
    JvmCurrentModuleDeserializer(moduleFragment, dependencies)

  private inner class JvmCurrentModuleDeserializer(moduleFragment: IrModuleFragment, dependencies: Collection<IrModuleDeserializer>) :
    CurrentModuleDeserializer(moduleFragment, dependencies) {
    override fun declareIrSymbol(symbol: IrSymbol) {
      val descriptor = symbol.descriptor

      if (descriptor.isJavaDescriptor()) {
        // Wrap java declaration with lazy ir
        if (symbol is IrFieldSymbol) {
          declareJavaFieldStub(symbol)
        } else {
          stubGenerator.generateMemberStub(descriptor)
        }
        return
      }

      if (descriptor.isCleanDescriptor()) {
        stubGenerator.generateMemberStub(descriptor)
        return
      }

      super.declareIrSymbol(symbol)
    }
  }

  private inner class MetadataJVMModuleDeserializer(moduleDescriptor: ModuleDescriptor, dependencies: List<IrModuleDeserializer>) :
    IrModuleDeserializer(moduleDescriptor, KotlinAbiVersion.CURRENT) {

    // TODO: implement proper check whether `idSig` belongs to this module
    override fun contains(idSig: IdSignature): Boolean = true

    private val descriptorFinder = DescriptorByIdSignatureFinderImpl(
      moduleDescriptor, manglerDesc,
      DescriptorByIdSignatureFinderImpl.LookupMode.MODULE_ONLY
    )

    private fun resolveDescriptor(idSig: IdSignature): DeclarationDescriptor? = descriptorFinder.findDescriptorBySignature(idSig)

    override fun tryDeserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol? {
      val descriptor = resolveDescriptor(idSig) ?: return null

      val declaration = stubGenerator.run {
        when (symbolKind) {
          BinarySymbolData.SymbolKind.CLASS_SYMBOL -> generateClassStub(descriptor as ClassDescriptor)
          BinarySymbolData.SymbolKind.PROPERTY_SYMBOL -> generatePropertyStub(descriptor as PropertyDescriptor)
          BinarySymbolData.SymbolKind.FUNCTION_SYMBOL -> generateFunctionStub(descriptor as FunctionDescriptor)
          BinarySymbolData.SymbolKind.CONSTRUCTOR_SYMBOL -> generateConstructorStub(descriptor as ClassConstructorDescriptor)
          BinarySymbolData.SymbolKind.ENUM_ENTRY_SYMBOL -> generateEnumEntryStub(descriptor as ClassDescriptor)
          BinarySymbolData.SymbolKind.TYPEALIAS_SYMBOL -> generateTypeAliasStub(descriptor as TypeAliasDescriptor)
          else -> error("Unexpected type $symbolKind for sig $idSig")
        }
      }

      return declaration.symbol
    }

    override fun deserializedSymbolNotFound(idSig: IdSignature): Nothing = error("No descriptor found for $idSig")

    override fun declareIrSymbol(symbol: IrSymbol) {
      if (symbol is IrFieldSymbol) {
        declareJavaFieldStub(symbol)
      } else {
        stubGenerator.generateMemberStub(symbol.descriptor)
      }
    }

    override val moduleFragment: IrModuleFragment = IrModuleFragmentImpl(moduleDescriptor)
    override val moduleDependencies: Collection<IrModuleDeserializer> = dependencies

    override val kind get() = IrModuleDeserializerKind.SYNTHETIC
  }
}
