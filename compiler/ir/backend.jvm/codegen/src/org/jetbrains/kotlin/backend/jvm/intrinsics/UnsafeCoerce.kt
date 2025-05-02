/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.intrinsics

import org.jetbrains.kotlin.backend.jvm.codegen.BlockInfo
import org.jetbrains.kotlin.backend.jvm.codegen.ExpressionCodegen
import org.jetbrains.kotlin.backend.jvm.codegen.PromisedValue
import org.jetbrains.kotlin.backend.jvm.codegen.materializeAt
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.org.objectweb.asm.Type

/**
 * Implicit coercion between IrTypes with the same underlying representation.
 *
 * A call of the form `coerce<A,B>(x)` allows us to coerce the value of `x` to type `A`
 * but treat the result as if it had IrType `B`. This is useful for inline classes,
 * whose coercion behavior depends on the IrType in addition to the underlying asmType.
 */
// coerce<A,B>(x) 형식의 호출을 사용하면 x 값을 유형 A로 강제하지만 결과는
// IrType B가 있는 것처럼 처리할 수 있습니다. 이는 강제 동작이 기본 asmType 외에
// IrType에 따라 달라지는 인라인 클래스에 유용합니다.
//
// ========================================================================================
// ========================================================================================
// ========================================================================================
//
// 아래 코드는 Kotlin 컴파일러의 JVM 백엔드에서 “unsafe coercion” (안전 장치 없이 강제 형변환)
// 기능을 구현한 IntrinsicMethod입니다. 주석과 함께 주요 흐름을 단계별로 풀어서 설명하면
// 다음과 같습니다.
//
// 1. 객체 선언
//
//  object UnsafeCoerce : IntrinsicMethod() { … }
//
// UnsafeCoerce는 IntrinsicMethod를 상속받아, 컴파일러 IR(중간 표현) 단계에서 호출되는
// 특수 메서드를 정의합니다. 이름 그대로 “안전 검증 없이” 타입을 강제 변환하는 역할을 합니다.
//
// 2. invoke() 오버라이드
//
//  override fun invoke(
//    expression: IrFunctionAccessExpression,
//    codegen: ExpressionCodegen,
//    data: BlockInfo
//  ): PromisedValue { … }
//
// IR에서 coerce<A, B>(x) 형태의 함수를 만났을 때 컴파일러가 실행하는 진입점입니다.
// - expression.typeArguments[0] → 원래 값의 IR 타입 A
// - expression.typeArguments[1] → 강제 변환할 목표 IR 타입 B
//
// 3. 언더라이잉 JVM 타입 매핑 및 검증
//
//   val fromType = codegen.typeMapper.mapType(from)
//   val toType   = codegen.typeMapper.mapType(to)
//   require(fromType == toType) {
//     "Inline class types should have the same representation: $fromType != $toType"
//   }
//
// - mapType()를 통해 각각 JVM 바이트코드 수준의 Type 객체로 변환
// - 두 타입이 같은 JVM 표현(바이트코드 시그니처)을 가져야만 강제 변환이 가능하므로,
//   같지 않으면 예외 발생
//
// 4. 인자 값(codegen) 처리
//
//   val arg    = expression.getValueArgument(0)!!
//   val result = arg.accept(codegen, data)
//
// - x(강제 변환할 실제 값)를 IR 표현에서 꺼내
// - ExpressionCodegen을 통해 JVM 바이트코드 생성 단계로 넘겨 처리한 결과(PromisedValue)를
//   받습니다.
//
// 5. 새로운 PromisedValue 반환
//
//   return object : PromisedValue(codegen, toType, to) {
//     override fun materializeAt(target: Type, irTarget: IrType, castForReified: Boolean) {
//       result.materializeAt(fromType, from)
//       super.materializeAt(target, irTarget, castForReified)
//     }
//     override fun discard() {
//       result.discard()
//     }
//   }
//
// - 내부적으로 원래 result를 먼저 원하는 위치(fromType)에 배치(materialize)한 뒤,
//   슈퍼클래스 로직을 통해 목표 타입(toType)으로 처리
// - discard()도 그대로 위임하여, 값이 실제로 사용되지 않을 때 JVM 스택에서 안전하게 제거
//
// ⸻
//
// 정리하자면, 이 Intrinsic은
// - 인라인 클래스나 다른 IR 타입 간에 JVM 바이트코드 수준에서는 동일한 표현을 가지지만
//   IR 상 타입만 다른 경우,
// - “unsafe”하게 강제 변환을 수행하도록 돕는 역할을 합니다.
// - 내부적으로는 실제 값(PromisedValue)을 먼저 원래 타입으로 materialize하고, 이후 목표
//   타입으로 간주하여 바이트코드를 생성합니다.
object UnsafeCoerce : IntrinsicMethod() {
  override fun invoke(
    expression: IrFunctionAccessExpression,
    codegen: ExpressionCodegen,
    data: BlockInfo,
  ): PromisedValue {
    val from = expression.typeArguments[0]!!
    val to = expression.typeArguments[1]!!
    val fromType = codegen.typeMapper.mapType(from)
    val toType = codegen.typeMapper.mapType(to)
    require(fromType == toType) {
      "Inline class types should have the same representation: $fromType != $toType"
    }
    val arg = expression.getValueArgument(0)!!
    val result = arg.accept(codegen, data)
    return object : PromisedValue(codegen = codegen, type = toType, irType = to) {
      override fun materializeAt(target: Type, irTarget: IrType, castForReified: Boolean) {
        result.materializeAt(target = fromType, irTarget = from)
        super.materializeAt(target, irTarget, castForReified)
      }

      override fun discard() {
        result.discard()
      }
    }
  }
}
