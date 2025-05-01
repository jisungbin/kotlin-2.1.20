/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.compiler.plugins.kotlin.analysis

import kotlin.coroutines.EmptyCoroutineContext

/**
 * This is a registry of APIs that are defined outside of Compose that we know to be stable but
 * cannot annotate with `Stable` or `Immutable`.
 *
 * For all of the functions and types listed in these collections, we associate them with a bitmask.
 * This mask corresponds to the bitmask returned by
 * [androidx.compose.compiler.plugins.kotlin.analysis.stabilityInferredArgumentBitmask] for general classes.
 * It defines how the generic parameters of a class/function influence its stability.
 *
 * A bit set to `1` in this mask indicates that the construct cannot be considered stable unless the
 * generic type at that position is also a stable type. If a bit is set to `0`, it indicates that
 * its corresponding generic type has no influence on whether the construct is stable.
 *
 * The bit at index 0 in this mask corresponds to the first generic type, and each subsequently
 * higher bit moves one generic type further to the right as they're defined. If the construct
 * doesn't have any generic types, it will have a mask of `0`.
 */
// 이것은 Compose 외부에 정의되어 안정적이라고 알려져 있지만 Stable 또는 Immutable로 주석을
// 달 수 없는 API의 레지스트리입니다.
//
// 이 컬렉션에 나열된 모든 함수와 유형에 대해 비트마스크와 연관시킵니다. 이 마스크는 일반
// 클래스에 대해 androidx.compose.compiler.plugins.kotlin.analysis.stabilityInferredArgumentBitmask에서
// 반환하는 비트마스크에 해당합니다. 클래스/함수의 (일반) 매개 변수가 안정성에 영향을 미치는
// 방식을 정의합니다.
//
// 이 마스크에서 비트가 1로 설정되면 해당 위치의 제네릭 유형도 안정된 유형이 아니면 구조체가
// 안정된 것으로 간주할 수 없음을 나타냅니다. 비트가 0으로 설정되어 있으면 해당 제네릭 유형이
// 구조체의 안정성 여부에 영향을 미치지 않음을 나타냅니다.
//
// 이 마스크의 인덱스 0에 있는 비트는 첫 번째 제네릭 유형에 해당하며, 이후 각 상위 비트는
// 정의된 대로 한 제네릭 유형을 오른쪽으로 더 이동합니다. 구조체에 제네릭 타입이 없는 경우
// 마스크는 0이 됩니다.
object KnownStableConstructs {

  val stableTypes = mapOf(
    Pair::class.qualifiedName!! to 0b11,
    Triple::class.qualifiedName!! to 0b111,
    Comparator::class.qualifiedName!! to 0,
    Result::class.qualifiedName!! to 0b1,
    ClosedRange::class.qualifiedName!! to 0b1,
    ClosedFloatingPointRange::class.qualifiedName!! to 0b1,
    // Guava
    "com.google.common.collect.ImmutableList" to 0b1,
    "com.google.common.collect.ImmutableEnumMap" to 0b11,
    "com.google.common.collect.ImmutableMap" to 0b11,
    "com.google.common.collect.ImmutableEnumSet" to 0b1,
    "com.google.common.collect.ImmutableSet" to 0b1,
    // Kotlinx immutable
    "kotlinx.collections.immutable.ImmutableCollection" to 0b1,
    "kotlinx.collections.immutable.ImmutableList" to 0b1,
    "kotlinx.collections.immutable.ImmutableSet" to 0b1,
    "kotlinx.collections.immutable.ImmutableMap" to 0b11,
    "kotlinx.collections.immutable.PersistentCollection" to 0b1,
    "kotlinx.collections.immutable.PersistentList" to 0b1,
    "kotlinx.collections.immutable.PersistentSet" to 0b1,
    "kotlinx.collections.immutable.PersistentMap" to 0b11,
    // Dagger
    "dagger.Lazy" to 0b1,
    // Coroutines
    EmptyCoroutineContext::class.qualifiedName!! to 0,
  )

  // TODO: buildList, buildMap, buildSet, etc.
  val stableFunctions = mapOf(
    "kotlin.collections.emptyList" to 0,
    "kotlin.collections.listOf" to 0b1,
    "kotlin.collections.listOfNotNull" to 0b1,
    "kotlin.collections.mapOf" to 0b11,
    "kotlin.collections.emptyMap" to 0,
    "kotlin.collections.setOf" to 0b1,
    "kotlin.collections.emptySet" to 0,
    "kotlin.to" to 0b11,
    // Kotlinx immutable
    "kotlinx.collections.immutable.immutableListOf" to 0b1,
    "kotlinx.collections.immutable.immutableSetOf" to 0b1,
    "kotlinx.collections.immutable.immutableMapOf" to 0b11,
    "kotlinx.collections.immutable.persistentListOf" to 0b1,
    "kotlinx.collections.immutable.persistentSetOf" to 0b1,
    "kotlinx.collections.immutable.persistentMapOf" to 0b11,
  )
}
