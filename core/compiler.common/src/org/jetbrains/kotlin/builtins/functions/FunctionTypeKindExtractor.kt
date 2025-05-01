/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.builtins.functions

import org.jetbrains.kotlin.name.FqName

@RequiresOptIn
annotation class AllowedToUsedOnlyInK1

class FunctionTypeKindExtractor(private val kinds: List<FunctionTypeKind>) {
  companion object {
    /**
     * This instance should be used only in:
     *  - FE 1.0, since it does not support custom functional kinds from plugins
     *  - places in FIR where session is not accessible by design (like in renderer of FIR elements)
     */
    @JvmStatic
    @AllowedToUsedOnlyInK1
    val Default = FunctionTypeKindExtractor(
      listOf(
        FunctionTypeKind.Function,
        FunctionTypeKind.SuspendFunction,
        FunctionTypeKind.KFunction,
        FunctionTypeKind.KSuspendFunction,
      )
    )
  }

  private val knownKindsByPackageFqName = kinds.groupBy { it.packageFqName }

  fun getFunctionalClassKind(packageFqName: FqName, className: String): FunctionTypeKind? {
    return getFunctionalClassKindWithArity(packageFqName, className)?.kind
  }

  fun getFunctionalClassKindWithArity(packageFqName: FqName, className: String): KindWithArity? {
    val kinds = knownKindsByPackageFqName[packageFqName] ?: return null
    for (kind in kinds) {
      if (!className.startsWith(kind.classNamePrefix)) continue
      val arity = toInt(className.substring(kind.classNamePrefix.length)) ?: continue
      return KindWithArity(kind, arity)
    }
    return null
  }

  fun hasKindWithSpecificPackage(packageFqName: FqName): Boolean {
    return packageFqName in knownKindsByPackageFqName
  }

  fun getFunctionKindPackageNames(): Set<FqName> = knownKindsByPackageFqName.keys

  fun hasExtensionKinds(): Boolean = kinds.any { !it.isBuiltin }

  data class KindWithArity(val kind: FunctionTypeKind, val arity: Int)

  // 이 과정을 더 자세히 설명해 드리겠습니다.
  //
  // 코드에서 `val d = c - '0'` 부분이 문자를 숫자로 변환하는 핵심 로직입니다.
  // 이것이 어떻게 작동하는지 자세히 살펴보겠습니다:
  //
  // ### 문자에서 숫자로 변환하는 원리
  //
  // 1. **문자의 유니코드 값**: 컴퓨터에서 모든 문자는 내부적으로 숫자 코드(유니코드)로
  //    표현됩니다. ASCII 코드표에서 숫자 문자들은 순서대로 배치되어 있습니다:
  //   - '0'의 유니코드 값: 48
  //   - '1'의 유니코드 값: 49
  //   - '2'의 유니코드 값: 50
  //   - '3'의 유니코드 값: 51
  //   - ...
  //   - '9'의 유니코드 값: 57
  //
  // 2. **문자 간의 뺄셈**: Kotlin이나 Java와 같은 언어에서 문자(Char)끼리 뺄셈을 하면,
  //    실제로는 그 문자들의 유니코드 값끼리 뺄셈을 수행합니다.
  //
  // 3. **숫자 문자를 실제 숫자로 변환**: 숫자 문자에서 '0' 문자를 빼면 그 차이가
  //    실제 숫자 값이 됩니다:
  //   - '3' - '0' = 51 - 48 = 3
  //   - '7' - '0' = 55 - 48 = 7
  //   - '0' - '0' = 48 - 48 = 0
  //   - '9' - '0' = 57 - 48 = 9
  //
  // ### 구체적인 예시
  //
  // 예를 들어 문자열 "123"을 정수로 변환하는 과정을 단계별로 보면:
  //
  // 1. 초기 `result` = 0
  // 2. 첫 번째 문자 '1' 처리:
  //   - `d` = '1' - '0' = 49 - 48 = 1
  //   - `result` = 0 * 10 + 1 = 1
  // 3. 두 번째 문자 '2' 처리:
  //   - `d` = '2' - '0' = 50 - 48 = 2
  //   - `result` = 1 * 10 + 2 = 12
  // 4. 세 번째 문자 '3' 처리:
  //   - `d` = '3' - '0' = 51 - 48 = 3
  //   - `result` = 12 * 10 + 3 = 123
  // 5. 모든 문자 처리 완료 → `result` = 123 반환
  //
  // ### 유효성 검사
  //
  // 코드에서 `if (d !in 0..9) return null` 부분은 이 계산 후에 나온 값이 0에서 9 사이인지
  // 확인합니다. 만약 입력된 문자가 숫자 문자('0'~'9')가 아니라면, 계산 결과가 이 범위를
  // 벗어나게 되어 함수는 `null`을 반환합니다.
  //
  // 예를 들어:
  //  - 'A' - '0' = 65 - 48 = 17 (0~9 범위를 벗어남 → `null` 반환)
  //  - '+' - '0' = 43 - 48 = -5 (0~9 범위를 벗어남 → `null` 반환)
  //
  // 이 방식은 문자열에서 정수로 변환하는 간단하면서도 효율적인 방법입니다.
  //
  // ===================================================================================
  // ===================================================================================
  // ===================================================================================
  // ===================================================================================
  //
  // 숫자 변환 과정에서 `result = result * 10 + d` 부분에 대해 자세히 설명해 드리겠습니다.
  //
  // ### 10진수 자릿수 변환 원리
  //
  // 이 코드는 10진수 숫자를 왼쪽에서 오른쪽으로 한 자리씩 처리하면서 정수 값을 구축하는
  // 과정입니다. 10을 곱하는 것은 현재 자릿수를 한 자리 높이는 효과가 있습니다.
  //
  // ### 수학적 원리
  //
  // 10진수 숫자 시스템에서 각 자릿수는 10의 거듭제곱을 의미합니다.
  // 예를 들어, 숫자 123은 다음과 같이 표현할 수 있습니다:
  // - 1×10² + 2×10¹ + 3×10⁰
  // - 1×100 + 2×10 + 3×1
  // - 100 + 20 + 3
  // - 123
  //
  // ### 코드 동작 방식
  //
  // 문자열 "123"을 처리하는 과정을 더 자세히 보겠습니다:
  //
  // 1. 초기값: `result = 0`
  //
  // 2. 첫 번째 문자 '1' 처리:
  //   - `d = '1' - '0' = 1`
  //   - `result = 0 * 10 + 1 = 1`
  //   - 이제 첫 번째 자리인 1을 얻었습니다.
  //
  // 3. 두 번째 문자 '2' 처리:
  //   - `result = 1 * 10 + 2 = 12`
  //   - 기존 결과(1)에 10을 곱하면 10이 됩니다. 이는 1을 십의 자리로 이동시킵니다.
  //   - 그리고 새로운 숫자 2를 일의 자리에 추가합니다.
  //
  // 4. 세 번째 문자 '3' 처리:
  //   - `result = 12 * 10 + 3 = 123`
  //   - 기존 결과(12)에 10을 곱하면 120이 됩니다. 이는 1을 백의 자리로, 2를 십의 자리로 이동시킵니다.
  //   - 그리고 새로운 숫자 3을 일의 자리에 추가합니다.
  //
  // ### 더 복잡한 예시: "4567"
  //
  // 숫자가 더 많은 경우에도 같은 원리가 적용됩니다:
  //
  // 1. 초기값: `result = 0`
  // 2. '4' 처리: `result = 0 * 10 + 4 = 4`
  // 3. '5' 처리: `result = 4 * 10 + 5 = 45`
  // 4. '6' 처리: `result = 45 * 10 + 6 = 456`
  // 5. '7' 처리: `result = 456 * 10 + 7 = 4567`
  //
  // ### 알고리즘의 중요성
  //
  // 이 알고리즘은 다음과 같은 장점이 있습니다:
  //
  // 1. **효율성**: 문자열을 한 번만 순회하면서 변환을 완료합니다 (O(n) 시간 복잡도).
  // 2. **메모리 사용**: 추가 메모리 공간을 거의 사용하지 않습니다 (O(1) 공간 복잡도).
  // 3. **자연스러운 처리**: 숫자를 읽는 자연스러운 방향(왼쪽→오른쪽)으로 처리합니다.
  //
  // 이 방식은 단순한 것처럼 보이지만, 숫자 시스템의 기본 원리를 효율적으로 활용한
  // 우아한 알고리즘입니다. 실제로 많은 프로그래밍 언어의 문자열→숫자 변환 함수들도
  // 내부적으로 이와 유사한 로직을 사용합니다.
  private fun toInt(s: String): Int? {
    if (s.isEmpty()) return null

    var result = 0
    for (c in s) {
      val d = c - '0'
      if (d !in 0..9) return null
      result = result * 10 + d
    }
    return result
  }
}
