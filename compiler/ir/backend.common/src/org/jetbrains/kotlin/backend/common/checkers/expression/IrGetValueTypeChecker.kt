/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.checkers.expression

import org.jetbrains.kotlin.backend.common.checkers.context.CheckerContext
import org.jetbrains.kotlin.backend.common.checkers.ensureTypeIs
import org.jetbrains.kotlin.ir.expressions.IrGetValue

internal object IrGetValueTypeChecker : IrGetValueChecker {
  override fun check(
    expression: IrGetValue,
    context: CheckerContext,
  ) {
    expression.ensureTypeIs(expression.symbol.owner.type, context)
  }
}
