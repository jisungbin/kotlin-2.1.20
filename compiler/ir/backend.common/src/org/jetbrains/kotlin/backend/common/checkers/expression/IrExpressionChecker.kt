/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.checkers.expression

import org.jetbrains.kotlin.backend.common.checkers.context.CheckerContext
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrDeclarationReference
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrValueAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol

internal interface IrExpressionChecker<in E : IrExpression> {
  fun check(expression: E, context: CheckerContext)
}

internal fun <E : IrExpression> List<IrExpressionChecker<E>>.check(expression: E, context: CheckerContext) {
  for (checker in this) {
    checker.check(expression, context)
  }
}

internal typealias IrVarargChecker = IrExpressionChecker<IrVararg>
internal typealias IrFieldAccessChecker = IrExpressionChecker<IrFieldAccessExpression>
internal typealias IrDeclarationReferenceChecker = IrExpressionChecker<IrDeclarationReference>
internal typealias IrValueAccessChecker = IrExpressionChecker<IrValueAccessExpression>
internal typealias IrFunctionAccessChecker = IrExpressionChecker<IrFunctionAccessExpression>
internal typealias IrFunctionReferenceChecker = IrExpressionChecker<IrFunctionReference>
internal typealias IrMemberAccessChecker = IrExpressionChecker<IrMemberAccessExpression<IrFunctionSymbol>>
internal typealias IrStringConcatenationChecker = IrExpressionChecker<IrStringConcatenation>
internal typealias IrGetObjectValueChecker = IrExpressionChecker<IrGetObjectValue>
internal typealias IrGetValueChecker = IrExpressionChecker<IrGetValue>
internal typealias IrSetValueChecker = IrExpressionChecker<IrSetValue>
internal typealias IrGetFieldChecker = IrExpressionChecker<IrGetField>
internal typealias IrSetFieldChecker = IrExpressionChecker<IrSetField>
internal typealias IrCallChecker = IrExpressionChecker<IrCall>
internal typealias IrInstanceInitializerCallChecker = IrExpressionChecker<IrInstanceInitializerCall>
internal typealias IrTypeOperatorChecker = IrExpressionChecker<IrTypeOperatorCall>
internal typealias IrReturnChecker = IrExpressionChecker<IrReturn>
internal typealias IrPropertyReferenceChecker = IrExpressionChecker<IrPropertyReference>
internal typealias IrLocalDelegatedPropertyReferenceChecker = IrExpressionChecker<IrLocalDelegatedPropertyReference>
internal typealias IrConstChecker = IrExpressionChecker<IrConst>
internal typealias IrDelegatingConstructorCallChecker = IrExpressionChecker<IrDelegatingConstructorCall>
internal typealias IrLoopChecker = IrExpressionChecker<IrLoop>
internal typealias IrBreakContinueChecker = IrExpressionChecker<IrBreakContinue>
internal typealias IrThrowChecker = IrExpressionChecker<IrThrow>
