/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.overrides

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.util.parentClassOrNull

val IrDeclarationWithVisibility.isNonPrivate: Boolean
  get() = visibility == DescriptorVisibilities.PUBLIC
    || visibility == DescriptorVisibilities.PROTECTED
    || visibility == DescriptorVisibilities.INTERNAL

fun IrDeclarationWithVisibility.isEffectivelyPrivate(): Boolean {
  return when {
    isNonPrivate -> parentClassOrNull?.isEffectivelyPrivate() ?: false

    visibility == DescriptorVisibilities.INVISIBLE_FAKE -> {
      val overridesOnlyPrivateDeclarations = (this as? IrOverridableDeclaration<*>)
        ?.overriddenSymbols
        ?.all { (it.owner as? IrDeclarationWithVisibility)?.isEffectivelyPrivate() == true }
        ?: false

      overridesOnlyPrivateDeclarations || (parentClassOrNull?.isEffectivelyPrivate() ?: false)
    }

    else -> true
  }
}
