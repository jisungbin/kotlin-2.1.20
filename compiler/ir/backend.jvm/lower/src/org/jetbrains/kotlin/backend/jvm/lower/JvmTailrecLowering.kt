/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.lower.TailrecLowering
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.defaultValue
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.types.IrType

@PhaseDescription(name = "Tailrec")
internal class JvmTailrecLowering(context: JvmBackendContext) : TailrecLowering(context) {
  override val useProperComputationOrderOfTailrecDefaultParameters: Boolean =
    context.config.languageVersionSettings.supportsFeature(LanguageFeature.ProperComputationOrderOfTailrecDefaultParameters)

  override fun followFunctionReference(reference: IrFunctionReference): Boolean =
    reference.origin == IrStatementOrigin.INLINE_LAMBDA

  override fun nullConst(startOffset: Int, endOffset: Int, type: IrType): IrExpression =
    type.defaultValue(startOffset, endOffset, context as JvmBackendContext)
}
