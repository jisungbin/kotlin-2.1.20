/*
 * Copyright 2020 The Android Open Source Project
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

import androidx.compose.compiler.plugins.kotlin.hasComposableAnnotation
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator.IMPLICIT_CAST
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator.SAM_CONVERSION
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isLambda
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class ComposableFunInterfaceLowering(private val context: IrPluginContext) :
  IrElementTransformerVoidWithContext(),
  ModuleLoweringPass {

  override fun lower(irModule: IrModuleFragment) {
    irModule.transformChildrenVoid(this)
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    val functionExpr = expression.findSamFunctionExpr()
    if (functionExpr != null && expression.typeOperand.isComposableFunInterface()) {
      val argument = functionExpr.transform(this, null) as IrFunctionExpression
      val superType = expression.typeOperand
      val superClass = superType.classOrNull ?: error("Expected non-null class")
      return FunctionReferenceClassBuilder(
        functionExpression = argument,
        functionSuperClass = superClass,
        superType = superType,
        currentDeclarationParent = currentDeclarationParent!!,
        context = context,
        currentScopeOwnerSymbol = currentScope!!.scope.scopeOwnerSymbol,
        typeSystemContext = IrTypeSystemContextImpl(context.irBuiltIns)
      )
        .buildClassCall()
    }
    return super.visitTypeOperator(expression)
  }

  private fun IrType.isComposableFunInterface(): Boolean =
    classOrNull
      ?.functions
      ?.single { it.owner.modality == Modality.ABSTRACT }
      ?.owner
      ?.hasComposableAnnotation() == true
}

internal fun IrTypeOperatorCall.findSamFunctionExpr(): IrFunctionExpression? {
  val argument = argument
  val operator = operator
  val type = typeOperand
  val typeClass = type.classOrNull

  val isFunInterfaceConversion =
    operator == SAM_CONVERSION && typeClass != null && typeClass.owner.isFun

  return if (isFunInterfaceConversion) {
    // if you modify this logic, make sure to update wrapping of type operators
    // in ComposerLambdaMemoization.kt
    @Suppress("IntroduceWhenSubject") // 코틀린 채신기술!
    when {
      argument is IrFunctionExpression && argument.origin.isLambda -> argument

      // some expressions are wrapped with additional implicit cast operator
      // unwrapping that allows to avoid SAM conversion that capture FunctionN and box params.
      //
      // 일부 표현식은 추가적인 암시적 캐스트 연산자로 감싸져 있습니다.
      // 이 감싸진 것을 풀면 FunctionN을 캡처하고 파라미터를 박싱하는 SAM 변환을 피할 수 있습니다.
      //
      // ========================================================
      // ========================================================
      // ========================================================
      // (SAM 변환이 코틀린 코드로 어떻게 구현되는지를 모르니 이해가 어렵다..)
      //
      // Chat GPT 해석:
      //
      // 코드의 주석과 번역은 컴파일러 최적화에 관한 내용입니다.
      //
      // 간단히 말해, 코틀린 컴파일러는 때때로 람다나 함수 표현식을 특정 함수 타입 인터페이스로
      // 자동 변환(암시적 캐스트)합니다. 이 과정에서 SAM(Single Abstract Method) 변환이 일어나는데,
      // 이때 `FunctionN`이라는 내부 클래스 객체가 생성되고, 람다가 사용하는 외부 변수를 캡처하며,
      // 기본 타입(primitive type) 파라미터가 객체 타입(wrapper type)으로 박싱(boxing)될 수 있습니다.
      // 이런 작업들은 약간의 성능 저하를 유발합니다.
      //
      // 주석의 의미는 다음과 같습니다:
      //
      // 1. "일부 표현식은 추가적인 암시적 캐스트 연산자로 감싸져 있습니다.": 어떤 함수 표현식(`functionExpr`)이
      //    컴파일러에 의해 자동으로 `IMPLICIT_CAST`로 감싸져 있을 수 있다는 뜻입니다. (`argument`가 이 감싸진 상태)
      //
      // 2. "이 감싸진 것을 풀면 (`unwrapping that allows to avoid...`)": 코드에서 `argument.argument`를
      //     통해 원래의 함수 표현식(`functionExpr`)을 직접 얻어내는 것을 의미합니다.
      //
      // 3. "...FunctionN을 캡처하고 파라미터를 박싱하는 SAM 변환을 피할 수 있습니다.": 이렇게 원래
      //     함수 표현식을 직접 사용하면, 불필요한 `FunctionN` 객체 생성, 변수 캡처, 파라미터 박싱 같은
      //     오버헤드가 발생하는 표준 SAM 변환 과정을 건너뛰고 더 효율적으로 처리할 수 있다는 의미입니다.
      //
      // 결론적으로, 해당 코드는 암시적 캐스트로 래핑된 함수 표현식을 감지하고, 그 래핑을 제거하여
      // 잠재적인 성능 오버헤드를 줄이려는 최적화 시도 중 하나입니다.
      argument is IrTypeOperatorCall && argument.operator == IMPLICIT_CAST -> {
        val functionExpr = argument.argument
        functionExpr as? IrFunctionExpression
      }

      else -> null
    }
  } else {
    null
  }
}
