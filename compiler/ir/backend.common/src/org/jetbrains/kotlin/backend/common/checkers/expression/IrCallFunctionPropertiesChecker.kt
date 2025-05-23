/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.checkers.expression

import org.jetbrains.kotlin.backend.common.checkers.checkFunctionProperties
import org.jetbrains.kotlin.backend.common.checkers.context.CheckerContext
import org.jetbrains.kotlin.ir.expressions.IrCall

internal object IrCallFunctionPropertiesChecker : IrCallChecker {
  override fun check(
    expression: IrCall,
    context: CheckerContext,
  ) {
    val function = expression.symbol.owner
    expression.checkFunctionProperties(function, context)
  }
}
