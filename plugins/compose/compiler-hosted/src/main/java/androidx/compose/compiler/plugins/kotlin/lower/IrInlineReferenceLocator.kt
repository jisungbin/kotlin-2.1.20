/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.ComposeFqNames
import androidx.compose.compiler.plugins.kotlin.ComposeFqNames.InternalPackage
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isLambda
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class ComposeInlineLambdaLocator(private val context: IrPluginContext) {
  private val inlineLambdaToValueParameter = mutableMapOf<IrFunctionSymbol, IrValueParameter>()
  private val inlineFunctionExpressions = mutableSetOf<IrExpression>()

  fun isInlineLambda(function: IrFunction): Boolean =
    function.symbol in inlineLambdaToValueParameter.keys

  fun isCrossinlineLambda(function: IrFunction): Boolean =
    inlineLambdaToValueParameter[function.symbol]?.isCrossinline == true

  fun isInlineFunctionExpression(expression: IrExpression): Boolean =
    expression in inlineFunctionExpressions

  // preserve: 보존하다, 지키다
  fun preservesComposableScope(function: IrFunction): Boolean =
    inlineLambdaToValueParameter[function.symbol]?.let { lambdaValueParameter ->
      // STUDY 넌로컬 리턴을 허용해야 함. 왜?
      !lambdaValueParameter.isCrossinline && // crossinline: 넌로컬 리턴 비허용
        !lambdaValueParameter.type.hasAnnotation(ComposeFqNames.DisallowComposableCalls)
    } ?: false

  // Locate all inline lambdas in the scope of the given IrElement.
  //
  // locate(동사): ...의 정확한 위치를 찾아내다
  //
  // 주어진 IrElement의 범위에서 모든 인라인 람다를 찾습니다.
  fun scan(element: IrElement) {
    element.acceptVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitValueParameter(declaration: IrValueParameter) {
        declaration.acceptChildrenVoid(this)

        val parent = declaration.parent as? IrFunction
        if (parent?.isInlineFunctionCall(context) == true && declaration.isInlinedFunction()) {
          declaration.defaultValue?.expression?.unwrapLambda()?.let { lambda ->
            inlineLambdaToValueParameter[lambda] = declaration
          }
        }
      }

      override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
        expression.acceptChildrenVoid(this)

        val function = expression.symbol.owner
        if (function.isInlineFunctionCall(context)) {
          for (parameter in function.valueParameters) {
            if (parameter.isInlinedFunction()) {
              expression.getValueArgument(parameter.indexInOldValueParameters)
                ?.also { inlineFunctionExpressions += it }
                ?.unwrapLambda()
                ?.let { inlineLambdaToValueParameter[it] = parameter }
            }
          }
        }
      }
    })
  }
}

// TODO: There is a Kotlin command line option to disable inlining (-Xno-inline). The code
//       should check for this option.
private fun IrFunction.isInlineFunctionCall(context: IrPluginContext) =
  isInline || isInlineArrayConstructor(context)

// Constructors can't be marked as inline in metadata, hence this hack.
// 생성자는 메타데이터에서 인라인으로 표시할 수 없으므로 이 해킹이 발생했습니다.
private fun IrFunction.isInlineArrayConstructor(context: IrPluginContext): Boolean =
  this is IrConstructor &&
    valueParameters.size == 2 &&
    constructedClass.symbol.let {
      it == context.irBuiltIns.arrayClass ||
        it in context.irBuiltIns.primitiveArraysToPrimitiveTypes
    }

fun IrExpression.unwrapLambda(): IrFunctionSymbol? =
  @Suppress("IntroduceWhenSubject")
  when {
    this is IrBlock && origin.isLambdaBlockOrigin ->
      (statements.lastOrNull() as? IrFunctionReference)?.symbol

    this is IrFunctionExpression -> function.symbol

    else -> null
  }

private val IrStatementOrigin?.isLambdaBlockOrigin: Boolean
  get() =
    isLambda ||
      this == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE ||
      this == IrStatementOrigin.SUSPEND_CONVERSION

// This is copied from JvmIrInlineUtils.kt in the Kotlin compiler, since we
// need to check for synthetic composable functions.
private fun IrValueParameter.isInlinedFunction(): Boolean =
  indexInOldValueParameters >= 0 &&
    !isNoinline &&
    (
      type.isFunction() ||
        type.isSuspendFunction() ||
        type.isSyntheticComposableFunction()
      ) &&
    // Parameters with default values are always nullable, so check the expression too.
    // Note that the frontend has a diagnostic for nullable inline parameters, so actually
    // making this return `false` requires using `@Suppress`.
    //
    // 기본값이 있는 매개변수는 항상 null이 될 수 있으므로 표현식도 확인하세요.
    // 프론트엔드에는 null 가능한 인라인 매개변수에 대한 진단 기능이 있으므로 실제로
    // 이 반환값을 `false`로 만들려면 `@Suppress`를 사용해야 합니다.
    (
      !type.isNullable() ||
        defaultValue?.expression?.type?.isNullable() == false
      )

fun IrType.isSyntheticComposableFunction() =
  classOrNull?.owner?.let {
    // FIR에서 만드는 타입
    it.name.asString().startsWith("ComposableFunction") &&
      it.packageFqName == InternalPackage
  } ?: false
