/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.psi2ir.generators

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.ir.util.ConstantValueGenerator
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.ScopedTypeParametersResolver
import org.jetbrains.kotlin.ir.util.StubGeneratorExtensions
import org.jetbrains.kotlin.ir.util.TypeParametersResolver
import org.jetbrains.kotlin.ir.util.TypeTranslator
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.CommonSupertypes
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeApproximator
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.jetbrains.kotlin.types.substituteAlternativesInPublicType

class TypeTranslatorImpl(
  symbolTable: ReferenceSymbolTable,
  languageVersionSettings: LanguageVersionSettings,
  moduleDescriptor: ModuleDescriptor,
  typeParametersResolverBuilder: () -> TypeParametersResolver = { ScopedTypeParametersResolver() },
  enterTableScope: Boolean = false,
  extensions: StubGeneratorExtensions = StubGeneratorExtensions.EMPTY,
  private val ktFile: KtFile? = null,
  allowErrorTypeInAnnotations: Boolean = false,
) : TypeTranslator(symbolTable, languageVersionSettings, typeParametersResolverBuilder, enterTableScope, extensions) {
  override val constantValueGenerator: ConstantValueGenerator =
    ConstantValueGeneratorImpl(moduleDescriptor, symbolTable, this, allowErrorTypeInAnnotations)

  private val typeApproximatorForNI = TypeApproximator(moduleDescriptor.builtIns, languageVersionSettings)

  override fun approximateType(type: KotlinType): KotlinType =
    substituteAlternativesInPublicType(type).let {
      typeApproximatorForNI.approximateToSuperType(it, TypeApproximatorConfiguration.FrontendToBackendTypesApproximation) ?: it
    }

  override fun commonSupertype(types: Collection<KotlinType>): KotlinType =
    CommonSupertypes.commonSupertype(types)

  override fun isTypeAliasAccessibleHere(typeAliasDescriptor: TypeAliasDescriptor): Boolean {
    if (!DescriptorVisibilities.isPrivate(typeAliasDescriptor.visibility)) return true

    val psiFile = typeAliasDescriptor.source.getPsi()?.containingFile ?: return false

    return psiFile == ktFile
  }
}
