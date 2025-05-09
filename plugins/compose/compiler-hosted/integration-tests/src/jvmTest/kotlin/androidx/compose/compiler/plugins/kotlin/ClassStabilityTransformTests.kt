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

package androidx.compose.compiler.plugins.kotlin

import androidx.compose.compiler.plugins.StabilityTestProtos
import androidx.compose.compiler.plugins.kotlin.analysis.FqNameMatcher
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.facade.SourceFile
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.statements
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassStabilityTransformTests(useFir: Boolean) : AbstractIrTransformTest(useFir) {
  @Test
  fun testEmptyClassIsStable() = assertStabilityOfClass(
    "class Foo",
    "Stable"
  )

  @Test
  fun testSingleValPrimitivePropIsStable() = assertStabilityOfClass(
    "class Foo(val value: Int)",
    "Stable"
  )

  @Test
  fun testSingleVarPrimitivePropIsUnstable() = assertStabilityOfClass(
    "class Foo(var value: Int)",
    "Unstable"
  )

  @Test
  fun testSingleValTypeParamIsStableIfParamIs() = assertStabilityOfClass(
    "class Foo<T>(val value: T)",
    "Parameter(T)"
  )


  @Test
  fun testValTypeParam33Types() = assertStabilityOfClass(
    """
            class Foo<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, T23, T24, T25, T26, T27, T28, T29, T30, T31, T32, T33>(
                val t1: T1,
                val t2: T2,
                val t3: T3,
                val t4: T4,
                val t5: T5,
                val t6: T6,
                val t7: T7,
                val t8: T8,
                val t9: T9,
                val t10: T10,
                val t11: T11,
                val t12: T12,
                val t13: T13,
                val t14: T14,
                val t15: T15,
                val t16: T16,
                val t17: T17,
                val t18: T18,
                val t19: T19,
                val t20: T20,
                val t21: T21,
                val t22: T22,
                val t23: T23,
                val t24: T24,
                val t25: T25,
                val t26: T26,
                val t27: T27,
                val t28: T28,
                val t29: T29,
                val t30: T30,
                val t31: T31,
                val t32: T32,
                val t33: T33,
            )
        """,
    """
            Parameter(T1),Parameter(T2),Parameter(T3),Parameter(T4),Parameter(T5),Parameter(T6),Parameter(T7),Parameter(T8),Parameter(T9),Parameter(T10),Parameter(T11),Parameter(T12),Parameter(T13),Parameter(T14),Parameter(T15),Parameter(T16),Parameter(T17),Parameter(T18),Parameter(T19),Parameter(T20),Parameter(T21),Parameter(T22),Parameter(T23),Parameter(T24),Parameter(T25),Parameter(T26),Parameter(T27),Parameter(T28),Parameter(T29),Parameter(T30),Parameter(T31),Parameter(T32),Parameter(T33)
        """.trimIndent()
  )

  @Test
  fun testSingleVarTypeParamIsUnstable() = assertStabilityOfClass(
    "class Foo<T>(var value: T)",
    "Unstable"
  )

  @Test
  fun testNonCtorVarIsUnstable() = assertStabilityOfClass(
    "class Foo(value: Int) { var value: Int = value }",
    "Unstable"
  )

  @Test
  fun testNonCtorValIsStable() = assertStabilityOfClass(
    "class Foo(value: Int) { val value: Int = value }",
    "Stable"
  )

  @Test
  fun testMutableStateDelegateVarIsStable() = assertStabilityOfClass(
    "class Foo(value: Int) { var value by mutableStateOf(value) }",
    "Stable"
  )

  @Test
  fun testLazyValIsUnstable() = assertStabilityOfClass(
    "class Foo(value: Int) { val square by lazy { value * value } }",
    "Unstable"
  )

  @Test
  fun testNonFieldCtorParamDoesNotImpactStability() = assertStabilityOfClass(
    "class Foo<T>(val a: Int, b: T) { val c: Int = b.hashCode() }",
    "Stable"
  )

  @Test
  fun testNonBackingFieldUnstableVarIsStable() = assertStabilityOfClass(
    """
            class Foo {
                var p1: Unstable
                    get() { TODO() }
                    set(value) { }
            }
        """,
    "Stable"
  )

  @Test
  fun testNonBackingFieldUnstableValIsStable() = assertStabilityOfClass(
    """
            class Foo {
                val p1: Unstable
                    get() { return Unstable() }
            }
        """,
    "Stable"
  )

  @Test
  fun testTypeParameterWithNonExactBackingFieldType() = assertStabilityOfClass(
    "class Foo<T>(val a: List<T>)",
    "Unstable"
  )

  @Test
  fun testTypeParamNonExactCtorParamExactBackingFields() = assertStabilityOfClass(
    "class Foo<T>(a: List<T>) { val b = a.first(); val c = a.last() }",
    "Parameter(T),Parameter(T)"
  )

  @Test
  fun testInterfacesAreUncertain() = assertStabilityOfClass(
    "interface Foo",
    "Uncertain(Foo)"
  )

  @Test
  fun testInterfacesAreUncertainOnIncrementalCompilation() {
    assertStabilityOfClass(
      classDefSrc = """
                interface Foo
            """,
      transform = {
        it.origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
      },
      stability = "Uncertain(Foo)"
    )
  }

  @Test
  fun testInterfaceWithStableValAreUncertain() = assertStabilityOfClass(
    "interface Foo { val value: Int }",
    "Uncertain(Foo)"
  )

  @Test
  fun testCrossModuleTypesResultInRuntimeStability() = assertStabilityOfClass(
    externalSrc = """
                class A
                class B
                class C
            """,
    classDefSrc = "class D(val a: A, val v: B, val C: C)",
    stability = "Runtime(A),Runtime(B),Runtime(C)"
  )

  @Test
  fun testStable17() = assertStabilityOfClass(
    """
            class Counter {
                private var count: Int = 0
                fun getCount(): Int = count
                fun increment() { count++ }
            }
        """,
    "Unstable"
  )

  @Test
  fun testValueClassIsStableIfItsValueIsStable() = assertStabilityOfClass(
    """
            @JvmInline value class Px(val pixels: Int)
        """,
    "Stable"
  )

  @Test
  fun testValueClassIsUnstableIfItsValueIsUnstable() = assertStabilityOfClass(
    """
            @JvmInline value class UnstableWrapper(val backingValue: Unstable)
        """,
    "Unstable"
  )

  @Test
  fun testValueClassIsStableIfAnnotatedAsStableRegardlessOfWrappedValue() = assertStabilityOfClass(
    """
            @Stable @JvmInline value class StableWrapper(val backingValue: Unstable)
        """,
    "Stable"
  )

  @Test
  fun testGenericValueClassIsStableIfTypeIsStable() = assertStabilityOfClass(
    """
            @JvmInline value class PairWrapper<T, U>(val pair: Pair<T, U>)
        """,
    "Parameter(T),Parameter(U)"
  )

  @Test
  fun testDeeplyNestedValueClassIsTreatedAsStable() = assertStabilityOfClass(
    externalSrc = """
                @Stable @JvmInline value class UnsafeStableList(val list: MutableList<Int>)
    
                @JvmInline value class StableWrapper(val backingValue: UnsafeStableList)
            """,
    classDefSrc = """
                @JvmInline value class InferredStable(val backingValue: StableWrapper)
            """,
    stability = "Stable"
  )

  @Test
  fun testProtobufLiteTypesAreStable() = assertStabilityOfClass(
    """
            class Foo(val x: androidx.compose.compiler.plugins.StabilityTestProtos.SampleProto)
        """,
    "Stable"
  )

  @Test
  fun testPairIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T, V>(val x: Pair<T, V>)
        """,
    "Parameter(T),Parameter(V)"
  )

  @Test
  fun testPairOfCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    externalSrc = """
                class A
                class B
            """,
    classDefSrc = "class Foo(val x: Pair<A, B>)",
    stability = "Runtime(A),Runtime(B)"
  )

  @Test
  fun testGuavaImmutableListIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: com.google.common.collect.ImmutableList<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testGuavaImmutableListCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: com.google.common.collect.ImmutableList<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testGuavaImmutableSetIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: com.google.common.collect.ImmutableSet<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testGuavaImmutableSetCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: com.google.common.collect.ImmutableSet<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testGuavaImmutableMapIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<K, V>(val x: com.google.common.collect.ImmutableMap<K, V>)
        """,
    "Parameter(K),Parameter(V)"
  )

  @Test
  fun testGuavaImmutableMapCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
            class B
        """,
    """
            class Foo(val x: com.google.common.collect.ImmutableMap<A, B>)
        """,
    "Runtime(A),Runtime(B)"
  )

  @Test
  fun testKotlinxImmutableCollectionIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: kotlinx.collections.immutable.ImmutableCollection<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testKotlinxImmutableCollectionCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.ImmutableCollection<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testKotlinxImmutableListIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: kotlinx.collections.immutable.ImmutableList<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testKotlinxImmutableListCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.ImmutableList<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testKotlinxImmutableSetIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: kotlinx.collections.immutable.ImmutableSet<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testKotlinxImmutableSetCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.ImmutableSet<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testKotlinxImmutableMapIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<K, V>(val x: kotlinx.collections.immutable.ImmutableMap<K, V>)
        """,
    "Parameter(K),Parameter(V)"
  )

  @Test
  fun testKotlinxImmutableMapCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
            class B
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.ImmutableMap<A, B>)
        """,
    "Runtime(A),Runtime(B)"
  )

  @Test
  fun testKotlinxPersistentCollectionIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: kotlinx.collections.immutable.PersistentCollection<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testKotlinxPersistentCollectionCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.PersistentCollection<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testKotlinxPersistentListIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: kotlinx.collections.immutable.PersistentList<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testKotlinxPersistentListCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.PersistentList<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testKotlinxPersistentSetIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<T>(val x: kotlinx.collections.immutable.PersistentSet<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testKotlinxPersistentSetCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.PersistentSet<A>)
        """,
    "Runtime(A)"
  )

  @Test
  fun testKotlinxPersistentMapIsStableIfItsTypesAre() = assertStabilityOfClass(
    """
            class Foo<K, V>(val x: kotlinx.collections.immutable.PersistentMap<K, V>)""",
    "Parameter(K),Parameter(V)"
  )

  @Test
  fun testKotlinxPersistentMapCrossModuleTypesAreRuntimeStable() = assertStabilityOfClass(
    """
            class A
            class B
        """,
    """
            class Foo(val x: kotlinx.collections.immutable.PersistentMap<A, B>)""",
    "Runtime(A),Runtime(B)"
  )

  @Test
  fun testDaggerLazyIsStableIfItsTypeIs() = assertStabilityOfClass(
    """
            class Foo<T>(val x: dagger.Lazy<T>)
        """,
    "Parameter(T)"
  )

  @Test
  fun testDaggerLazyOfCrossModuleTypeIsRuntimeStable() = assertStabilityOfClass(
    """
            class A
        """,
    "class Foo(val x: dagger.Lazy<A>)",
    "Runtime(A)"
  )

  @Test
  fun testVarPropDelegateWithCrossModuleStableDelegateTypeIsStable() = assertStabilityOfClass(
    """
            @Stable
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
        """,
    "class Foo { var p1 by StableDelegate() }",
    "Stable"
  )

  @Test
  fun testVarPropDelegateWithStableDelegateTypeIsStable() = assertStabilityOfClass(
    """
        @Stable
        class StableDelegate {
            operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
            }
            operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                return 10
            }
        }
        class Foo { var p1 by StableDelegate() }
        """,
    "Stable"
  )

  @Test
  fun testVarPropDelegateOfInferredStableDelegate() = assertStabilityOfClass(
    """
        class StableDelegate {
            operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
            }
            operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                return 10
            }
        }
        class Foo { var p1 by StableDelegate() }
        """,
    "Stable"
  )

  @Test
  fun testVarPropDelegateOfCrossModuleInferredStableDelegate() = assertStabilityOfClass(
    """
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
        """,
    "class Foo { var p1 by StableDelegate() }",
    "Runtime(StableDelegate)"
  )

  @Test
  fun testStableDelegateWithTypeParamButNoBackingFieldDoesntDependOnReturnType() =
    assertStabilityOfClass(
      """
                class StableDelegate<T> {
                    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: T) {
                    }
                    operator fun getValue(thisObj: Any?, property: KProperty<*>): T {
                        error("")
                    }
                }
                class Bar
            """,
      "class Foo { var p1 by StableDelegate<Bar>() }",
      "Runtime(StableDelegate)"
    )

  @Test
  fun testStable26() = assertStabilityOfClass(
    """
            class A
            class B
            class C
        """,
    """
            class Foo(a: A, b: B, c: C) {
                var a by mutableStateOf(a)
                var b by mutableStateOf(b)
                var c by mutableStateOf(c)
            }
        """,
    "Stable"
  )

  @Test
  fun testExplicitlyMarkedStableTypesAreStable() = assertStabilityOfClass(
    """
            @Stable
            class A
        """,
    """
            class Foo(val a: A)
        """,
    "Stable"
  )

  @Test
  fun testExternalStableTypesFieldsAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A 
        """,
    classDefSrc = """
            class Foo(val a: A)
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.A")
  )

  @Test
  fun testClassesExtendingExternalStableInterfacesAreStable() = assertStabilityOfClass(
    externalSrc = """
            interface A 
        """,
    classDefSrc = """
            class Foo : A
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.A")
  )

  @Test
  fun testExternalWildcardTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A
        """,
    classDefSrc = """
            class Foo(val a: A)
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.*")
  )

  @Test
  fun testExternalOnlySingleWildcardTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A

            class B {
                class C
            }
        """,
    classDefSrc = """
            class Foo(val a: A, val b: B, val c: B.C)
        """,
    stability = "Runtime(B)",
    externalTypes = setOf("dependency.A", "dependency.B.*")
  )

  @Test
  fun testExternalDoubleWildcardTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
    classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.**")
  )

  @Test
  fun testExternalDoubleWildcardInMiddleTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
    classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
    stability = "Runtime(A),Runtime(B)",
    externalTypes = setOf("dependency.**.C")
  )

  @Test
  fun testExternalDoubleWildcardWithPrefixInMiddleTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A {
                class Ba {
                    class C
                }
                class Bb {
                    class C
                }
            }
        """,
    classDefSrc = """
            class Foo(val a: A, val ba: A.Ba, val bb: A.Bb, val ca: A.Ba.C, val cb: A.Bb.C)
        """,
    stability = "Runtime(A)",
    externalTypes = setOf("dependency.A.B**")
  )

  @Test
  fun testExternalMixedWildcardsTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
    classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
    stability = "Runtime(A)",
    externalTypes = setOf("dependency.**.*")
  )

  @Test
  fun testExternalMultiWildcardFirstTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
    classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
    stability = "Stable",
    externalTypes = setOf("**")
  )

  @Test
  fun testExternalWildcardFirstTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A
        """,
    classDefSrc = """
            class Foo(val a: A)
        """,
    stability = "Stable",
    externalTypes = setOf("*.A")
  )

  @Test
  fun testExternalMultipleSingleWildcardsTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A {
                class B {
                    class C
                }
                class D {
                    class E
                }
            }
        """,
    classDefSrc = """
            class Foo(val c: A.B.C, val e: A.D.E)
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.*.B.*", "dependency.A.D.E")
  )

  @Test
  fun testExternalGenericTypesAreParameterDependent() = assertStabilityOfClass(
    externalSrc = """
            class Foo<T>(val x: T)
        """,
    classDefSrc = """
            class Test<T>(val foo: Foo<T>)
        """,
    stability = "Parameter(T)",
    externalTypes = setOf("dependency.Foo")
  )

  @Test
  fun testExternalGenericTypesAreCanIgnoreParameters() = assertStabilityOfClass(
    externalSrc = """
            class Foo<X, Y>(val x: X, val y: Y)
        """,
    classDefSrc = """
            class Test<X, Y>(val foo: Foo<X, Y>)
        """,
    stability = "Parameter(X)",
    externalTypes = setOf("dependency.Foo<*,_>")
  )

  @Test
  fun testExternalGenericTypesAreCanBeRuntimeStable() = assertStabilityOfClass(
    externalSrc = """
            class A
            class B
            class Foo<X, Y>(val x: X, val y: Y)
        """,
    classDefSrc = """
            class Test(val foo: Foo<A, B>)
        """,
    stability = "Runtime(B)",
    externalTypes = setOf("dependency.Foo<_,*>")
  )

  @Test
  fun testExternalGenericDefinedTypesAreStable() = assertStabilityOfClass(
    externalSrc = """
            class A
            class Foo<T>(val x: T)
        """,
    classDefSrc = """
            class Test(val foo: Foo<A>)
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.Foo", "dependency.A")
  )

  @Test
  fun testExternalDeepPackageNameIsStable() = assertStabilityOfClass(
    externalSrc = """
            class A
        """,
    classDefSrc = """
            class Test(val foo: A)
        """,
    stability = "Stable",
    externalTypes = setOf("dependency.b.c.d.A"),
    packageName = "dependency.b.c.d"
  )

  @Test
  fun testGenericLoop() = assertStabilityOfClass(
    """
            class B<T>(val a: A<T>)
            class A<T>(val b: B<T>, val c: T)
        """,
    "class Foo(val a: A<String>)",
    "Runtime(A)"
  )

  @Test
  fun testListOfCallWithPrimitiveTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "listOf(1)",
    stability = "Stable"
  )

  @Test
  fun testListOfCallWithLocalInferredStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "class Foo",
    expression = "listOf(Foo())",
    stability = "Stable"
  )

  @Test
  fun testListOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = "class Foo",
    localSrc = "",
    expression = "listOf(Foo())",
    stability = "Runtime(Foo)"
  )

  @Test
  fun testMapOfCallWithPrimitiveTypesIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "mapOf(1 to 1)",
    stability = "Stable,Stable"
  )

  @Test
  fun testMapOfCallWithStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                class A
                class B
            """,
    expression = "mapOf(A() to B())",
    stability = "Stable,Stable"
  )

  @Test
  fun testMapOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = """
                class A
                class B
            """,
    localSrc = "",
    expression = "mapOf(A() to B())",
    stability = "Runtime(A),Runtime(B)"
  )

  @Test
  fun testSetOfCallWithPrimitiveTypesIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "setOf(1)",
    stability = "Stable"
  )

  @Test
  fun testSetOfCallWithStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                class A
            """,
    expression = "setOf(A())",
    stability = "Stable"
  )

  @Test
  fun testSetOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = """
                class A
            """,
    localSrc = "",
    expression = "setOf(A())",
    stability = "Runtime(A)"
  )

  @Test
  fun testImmutableListOfCallWithPrimitiveTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "kotlinx.collections.immutable.immutableListOf(1)",
    stability = "Stable"
  )

  @Test
  fun testImmutableListOfCallWithLocalInferredStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "class Foo",
    expression = "kotlinx.collections.immutable.immutableListOf(Foo())",
    stability = "Stable"
  )

  @Test
  fun testImmutableListOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = "class Foo",
    localSrc = "",
    expression = "kotlinx.collections.immutable.immutableListOf(Foo())",
    stability = "Runtime(Foo)"
  )

  @Test
  fun testImmutableMapOfCallWithPrimitiveTypesIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "kotlinx.collections.immutable.immutableMapOf(1 to 1)",
    stability = "Stable,Stable"
  )

  @Test
  fun testImmutableMapOfCallWithStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                class A
                class B
            """,
    expression = "kotlinx.collections.immutable.immutableMapOf(A() to B())",
    stability = "Stable,Stable"
  )

  @Test
  fun testImmutableMapOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = """
                class A
                class B
            """,
    localSrc = "",
    expression = "kotlinx.collections.immutable.immutableMapOf(A() to B())",
    stability = "Runtime(A),Runtime(B)"
  )

  @Test
  fun testImmutableSetOfCallWithPrimitiveTypesIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "kotlinx.collections.immutable.immutableSetOf(1)",
    stability = "Stable"
  )

  @Test
  fun testImmutableSetOfCallWithStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                class A
            """,
    expression = "kotlinx.collections.immutable.immutableSetOf(A())",
    stability = "Stable"
  )

  @Test
  fun testImmutableSetOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = """
                class A
            """,
    localSrc = "",
    expression = "kotlinx.collections.immutable.immutableSetOf(A())",
    stability = "Runtime(A)"
  )

  @Test
  fun testPersistentListOfCallWithPrimitiveTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "kotlinx.collections.immutable.persistentListOf(1)",
    stability = "Stable"
  )

  @Test
  fun testPersistentListOfCallWithLocalInferredStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "class Foo",
    expression = "kotlinx.collections.immutable.persistentListOf(Foo())",
    stability = "Stable"
  )

  @Test
  fun testPersistentListOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = "class Foo",
    localSrc = "",
    expression = "kotlinx.collections.immutable.persistentListOf(Foo())",
    stability = "Runtime(Foo)"
  )

  @Test
  fun testPersistentMapOfCallWithPrimitiveTypesIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "kotlinx.collections.immutable.persistentMapOf(1 to 1)",
    stability = "Stable,Stable"
  )

  @Test
  fun testChildOfUnstableClass() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                open class Parent {
                    var age: Int = 0
                }
    
                class Child : Parent()
            """,
    expression = "Child()",
    stability = "Unstable"
  )

  @Test
  fun testPersistentMapOfCallWithStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                class A
                class B
            """,
    expression = "kotlinx.collections.immutable.persistentMapOf(A() to B())",
    stability = "Stable,Stable"
  )

  @Test
  fun testPersistentMapOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = """
                class A
                class B
            """,
    localSrc = "",
    expression = "kotlinx.collections.immutable.persistentMapOf(A() to B())",
    stability = "Runtime(A),Runtime(B)"
  )

  @Test
  fun testPersistentSetOfCallWithPrimitiveTypesIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = "",
    expression = "kotlinx.collections.immutable.persistentSetOf(1)",
    stability = "Stable"
  )

  @Test
  fun testPersistentSetOfCallWithStableTypeIsStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
                class A
            """,
    expression = "kotlinx.collections.immutable.persistentSetOf(A())",
    stability = "Stable"
  )

  @Test
  fun testPersistentSetOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStabilityOfExpression(
    externalSrc = """
                class A
            """,
    localSrc = "",
    expression = "kotlinx.collections.immutable.persistentSetOf(A())",
    stability = "Runtime(A)"
  )

  // b/327643787
  @Test
  fun testNestedExternalTypesAreStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
            data class B(val list: List<Int>)
            data class A(val list: List<B>)
        """.trimIndent(),
    expression = "A(listOf())",
    externalTypes = setOf("kotlin.collections.List"),
    stability = "Stable"
  )

  @Test
  fun testNestedGenericsAreRuntimeStable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
            class A(val child: List<A>?)
        """.trimIndent(),
    expression = "A(null)",
    externalTypes = setOf("kotlin.collections.List"),
    stability = "Unstable"
  )

  @Test
  fun testNestedEqualTypesAreUnstable() = assertStabilityOfExpression(
    externalSrc = "",
    localSrc = """
            class A(val child: A?)
        """.trimIndent(),
    expression = "A(A(null))",
    stability = "Unstable"
  )

  @Test
  fun testEmptyClass() = assertTransform(
    """
            class Foo
        """
  )

  @Test
  fun testStabilityTransformOfVariousTypes() = assertTransform(
    """
            import androidx.compose.runtime.Stable
            import kotlin.reflect.KProperty

            @Stable
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }

            class UnstableDelegate {
                var value: Int = 0
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                    this.value = value
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
            class Unstable { var value: Int = 0 }
            class EmptyClass
            class SingleStableVal(val p1: Int)
            class SingleParamProp<T>(val p1: T)
            class SingleParamNonProp<T>(p1: T) { val p2 = p1.hashCode() }
            class DoubleParamSingleProp<T, V>(val p1: T, p2: V) { val p3 = p2.hashCode() }
            class X<T>(val p1: List<T>)
            class NonBackingFieldUnstableProp {
                val p1: Unstable get() { TODO() }
            }
            class NonBackingFieldUnstableVarProp {
                var p1: Unstable
                    get() { TODO() }
                    set(value) { }
            }
            class StableDelegateProp {
                var p1 by StableDelegate()
            }
            class UnstableDelegateProp {
                var p1 by UnstableDelegate()
            }
        """
  )

  @Test
  fun testStabilityPropagationOfVariousTypes() = verifyGoldenCrossModuleComposeIrTransform(
    dependencySource = """
                package a
                import androidx.compose.runtime.Stable
                import kotlin.reflect.KProperty
    
                @Stable
                class StableDelegate {
                    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                    }
                    operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                        return 10
                    }
                }
    
                class UnstableDelegate {
                    var value: Int = 0
                    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                        this.value = value
                    }
                    operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                        return 10
                    }
                }
                class UnstableClass {
                    var value: Int = 0
                }
                class StableClass
    
                class EmptyClass
                class SingleStableValInt(val p1: Int)
                class SingleStableVal(val p1: StableClass)
                class `SingleParamProp`<T>(val p1: T)
                class SingleParamNonProp<T>(p1: T) { val p2 = p1.hashCode() }
                class DoubleParamSingleProp<T, V>(val p1: T, p2: V) { val p3 = p2.hashCode() }
                class X<T>(val p1: List<T>)
                class NonBackingFieldUnstableVal {
                    val p1: UnstableClass get() { TODO() }
                }
                class NonBackingFieldUnstableVar {
                    var p1: UnstableClass
                        get() { TODO() }
                        set(value) { }
                }
                class StableDelegateProp {
                    var p1 by StableDelegate()
                }
                class UnstableDelegateProp {
                    var p1 by UnstableDelegate()
                }
                fun used(x: Any?) {}
            """,
    source = """
                import a.*
                import androidx.compose.runtime.Composable
    
                @Composable fun A(y: Any? = null) {
                    used(y)
                    A()
                    A(EmptyClass())
                    A(SingleStableValInt(123))
                    A(SingleStableVal(StableClass()))
                    A(SingleParamProp(StableClass()))
                    A(SingleParamProp(UnstableClass()))
                    A(SingleParamNonProp(StableClass()))
                    A(SingleParamNonProp(UnstableClass()))
                    A(DoubleParamSingleProp(StableClass(), StableClass()))
                    A(DoubleParamSingleProp(UnstableClass(), StableClass()))
                    A(DoubleParamSingleProp(StableClass(), UnstableClass()))
                    A(DoubleParamSingleProp(UnstableClass(), UnstableClass()))
                    A(X(listOf(StableClass())))
                    A(X(listOf(StableClass())))
                    A(NonBackingFieldUnstableVal())
                    A(NonBackingFieldUnstableVar())
                    A(StableDelegateProp())
                    A(UnstableDelegateProp())
                }
            """
  )

  @Test
  fun testStabilityPropagationTooManyTypeParams() = verifyGoldenCrossModuleComposeIrTransform(
    dependencySource = """
                package a
    
                class Foo<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, T23, T24, T25, T26, T27, T28, T29, T30, T31, T32, T33>(
                    val t1: T1,
                    val t2: T2,
                    val t3: T3,
                    val t4: T4,
                    val t5: T5,
                    val t6: T6,
                    val t7: T7,
                    val t8: T8,
                    val t9: T9,
                    val t10: T10,
                    val t11: T11,
                    val t12: T12,
                    val t13: T13,
                    val t14: T14,
                    val t15: T15,
                    val t16: T16,
                    val t17: T17,
                    val t18: T18,
                    val t19: T19,
                    val t20: T20,
                    val t21: T21,
                    val t22: T22,
                    val t23: T23,
                    val t24: T24,
                    val t25: T25,
                    val t26: T26,
                    val t27: T27,
                    val t28: T28,
                    val t29: T29,
                    val t30: T30,
                    val t31: T31,
                    val t32: T32,
                    val t33: T33,
                )
                fun used(any: Any? = null) {}
            """,
    source = """
                import a.*
                import androidx.compose.runtime.Composable
    
                @Composable fun A(y: Any? = null) {
                    used(y)
    
                    A(
                        Foo(
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                        )
                    )
                }
            """
  )

  @Test
  fun testStabilityPropagationTooManyTypeParamsSameModule() = verifyGoldenComposeIrTransform(
    source = """
                package a
    
                import androidx.compose.runtime.Composable
    
                class Foo<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, T23, T24, T25, T26, T27, T28, T29, T30, T31, T32, T33>(
                    val t1: T1,
                    val t2: T2,
                    val t3: T3,
                    val t4: T4,
                    val t5: T5,
                    val t6: T6,
                    val t7: T7,
                    val t8: T8,
                    val t9: T9,
                    val t10: T10,
                    val t11: T11,
                    val t12: T12,
                    val t13: T13,
                    val t14: T14,
                    val t15: T15,
                    val t16: T16,
                    val t17: T17,
                    val t18: T18,
                    val t19: T19,
                    val t20: T20,
                    val t21: T21,
                    val t22: T22,
                    val t23: T23,
                    val t24: T24,
                    val t25: T25,
                    val t26: T26,
                    val t27: T27,
                    val t28: T28,
                    val t29: T29,
                    val t30: T30,
                    val t31: T31,
                    val t32: T32,
                    val t33: T33,
                )
                fun used(any: Any? = null) {}
    
                @Composable fun A(y: Any? = null) {
                    used(y)
    
                    A(
                        Foo(
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                        )
                    )
                }
            """
  )

  @Test
  fun testStabilityPropagationOfVariousTypesInSameModule() = verifyGoldenCrossModuleComposeIrTransform(
    dependencySource = """
                  package a
                  import androidx.compose.runtime.Stable
                  import kotlin.reflect.KProperty
      
                  @Stable
                  class StableDelegate {
                      operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                      }
                      operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                          return 10
                      }
                  }
      
                  class UnstableDelegate {
                      var value: Int = 0
                      operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                          this.value = value
                      }
                      operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                          return 10
                      }
                  }
                  class UnstableClass {
                      var value: Int = 0
                  }
                  class StableClass
      
                  class EmptyClass
                  class SingleStableValInt(val p1: Int)
                  class SingleStableVal(val p1: StableClass)
                  class SingleParamProp<T>(val p1: T)
                  class SingleParamNonProp<T>(p1: T) { val p2 = p1.hashCode() }
                  class DoubleParamSingleProp<T, V>(val p1: T, p2: V) { val p3 = p2.hashCode() }
                  class NonBackingFieldUnstableVal {
                      val p1: UnstableClass get() { TODO() }
                  }
                  class NonBackingFieldUnstableVar {
                      var p1: UnstableClass
                          get() { TODO() }
                          set(value) { }
                  }
                  fun used(x: Any?) {}
              """,
    source = """
                  import a.*
                  import androidx.compose.runtime.Composable
      
                  class X<T>(val p1: List<T>)
                  class StableDelegateProp {
                      var p1 by StableDelegate()
                  }
                  class UnstableDelegateProp {
                      var p1 by UnstableDelegate()
                  }
                  @Composable fun A(y: Any) {
                      used(y)
                      A(X(listOf(StableClass())))
                      A(StableDelegateProp())
                      A(UnstableDelegateProp())
                      A(SingleParamProp(0))
                      A(SingleParamNonProp(0))
                      A(SingleParamProp(Any()))
                  }
              """
  )

  @Test
  fun testEmptyClassAcrossModules() = verifyGoldenCrossModuleComposeIrTransform(
    dependencySource = """
                package a
                class Wrapper<T>(value: T) {
                  fun make(): T = error("")
                }
                class Foo
                fun used(x: Any?) {}
            """,
    source = """
                import a.*
                import androidx.compose.runtime.Composable
    
                @Composable fun A(y: Any) {
                    used(y)
                    A(Wrapper(Foo()))
                }
            """
  )

  @Test
  fun testLocalParameterBasedTypeParameterSubstitution() = verifyGoldenCrossModuleComposeIrTransform(
    dependencySource = """
                package a
                import androidx.compose.runtime.Composable
                class Wrapper<T>(val value: T)
                @Composable fun A(y: Any) {}
            """,
    source = """
                import a.*
                import androidx.compose.runtime.Composable
    
                @Composable fun <V> B(value: V) {
                    A(Wrapper(value))
                }
                @Composable fun <T> X(items: List<T>, itemContent: @Composable (T) -> Unit) {
                    for (item in items) itemContent(item)
                }
                @Composable fun C(items: List<String>) {
                    X(items) { item ->
                        A(item)
                        A(Wrapper(item))
                    }
                }
            """
  )

  @Test
  fun testSingleVarVersusValProperty() = assertTransform(
    """
            class Stable(val bar: Int)
            class Unstable(var bar: Int)
        """
  )

  @Test
  fun testComposableCall() = assertTransform(
    """
            import androidx.compose.runtime.Composable

            class Foo
            @Composable fun A(y: Int, x: Any) {
                used(y)
                B(x)
            }
            @Composable fun B(x: Any) {
                used(x)
            }
        """
  )

  @Test
  fun testComposableCallWithUnstableFinalClassInSameModule() = assertTransform(
    """
            import androidx.compose.runtime.Composable

            class Foo(var bar: Int = 0)
            @Composable fun A(y: Int, x: Foo) {
                used(y)
                B(x)
            }
            @Composable fun B(x: Any) {
                used(x)
            }
        """
  )

  @Test
  fun testTransformInternalClasses() {
    assertTransform(
      """
                internal class SomeFoo(val value: Int)
                internal class ParameterizedFoo<K>(val value: K)
                internal class MultipleFoo<K, T>(val value: K, val param: T)
            """
    )
  }

  @Test
  fun testTransformCombinedClassWithUnstableParametrizedClass() {
    verifyCrossModuleComposeIrTransform(
      dependencySource = """
                class SomeFoo(val value: Int)
                class ParametrizedFoo<K>(var value: K)
            """,
      source = """
                class CombinedUnstable<T>(val first: T, val second: ParametrizedFoo<SomeFoo>)
            """,
      expectedTransformed = """
                @StabilityInferred(parameters = 1)
                class CombinedUnstable<T> (val first: T, val second: ParametrizedFoo<SomeFoo>) {
                  static val %stable: Int = ParametrizedFoo.%stable
                }
            """
    )
  }

  @Test
  fun testTransformCombinedClassWithRuntimeStableParametrizedClass() {
    verifyCrossModuleComposeIrTransform(
      dependencySource = """
            class SomeFoo(val value: Int)
            class ParametrizedFoo<K>(val value: K)
        """,
      source = """
            class CombinedStable<T>(val first: T, val second: ParametrizedFoo<SomeFoo>)
        """,
      expectedTransformed = """
            @StabilityInferred(parameters = 1)
            class CombinedStable<T> (val first: T, val second: ParametrizedFoo<SomeFoo>) {
              static val %stable: Int = SomeFoo.%stable or ParametrizedFoo.%stable
            }
        """
    )
  }

  @Test
  fun testTransformCombinedClassWithMultiplyTypeParameters() {
    verifyCrossModuleComposeIrTransform(
      dependencySource = """
            class SomeFoo(val value: Int)
            class ParametrizedFoo<K>(val value: K)
        """,
      source = """
            class CombinedStable<T, K>(val first: T, val second: ParametrizedFoo<K>)
        """,
      expectedTransformed = """
            @StabilityInferred(parameters = 3)
            class CombinedStable<T, K> (val first: T, val second: ParametrizedFoo<K>) {
              static val %stable: Int = ParametrizedFoo.%stable
            }
        """
    )
  }

  private fun assertStabilityOfClass(
    @Language("kotlin")
    classDefSrc: String,
    stability: String,
    externalTypes: Set<String> = emptySet(),
    transform: (IrClass) -> Unit = {},
  ) {
    val source = """
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.setValue
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.State
            import kotlin.reflect.KProperty

            $classDefSrc

            class Unstable { var value: Int = 0 }
        """.trimIndent()

    val files = listOf(SourceFile("Test.kt", source))
    val irModule = compileToIr(files, additionalPaths, registerExtensions = {
      it.put(ComposeConfiguration.TEST_STABILITY_CONFIG_KEY, externalTypes)
    })
    val irClass = (irModule.files.last().declarations.first() as IrClass).apply(transform)
    val externalTypeMatchers = externalTypes.map { FqNameMatcher(it) }.toSet()
    val stabilityInferencer = StabilityInferencer(irModule.descriptor, externalTypeMatchers)
    val classStability = stabilityInferencer.stabilityOfType(irClass.defaultType as IrType)

    assertEquals(
      stability,
      classStability.toString()
    )
  }

  private fun assertStabilityOfClass(
    @Language("kotlin")
    externalSrc: String,
    @Language("kotlin")
    classDefSrc: String,
    stability: String,
    dumpClasses: Boolean = false,
    externalTypes: Set<String> = emptySet(),
    packageName: String = "dependency",
  ) {
    val irModule = buildModule(
      externalSrc = externalSrc,
      localSrc = classDefSrc,
      dumpClasses = dumpClasses,
      packageName = packageName,
      externalTypes = externalTypes
    )
    val irClass = irModule.files.last().declarations.first() as IrClass
    val externalTypeMatchers = externalTypes.map { FqNameMatcher(it) }.toSet()
    val classStability =
      StabilityInferencer(irModule.descriptor, externalTypeMatchers).stabilityOfType(irClass.defaultType as IrType)

    assertEquals(
      stability,
      classStability.toString()
    )
  }

  private fun assertStabilityOfExpression(
    externalSrc: String,
    localSrc: String,
    expression: String,
    stability: String,
    dumpClasses: Boolean = false,
    externalTypes: Set<String> = emptySet(),
  ) {
    val irModule = buildModule(
      externalSrc,
      """
                $localSrc

                fun TestFunction() = $expression
            """.trimIndent(),
      dumpClasses,
      externalTypes = externalTypes
    )
    val irTestFn = irModule
      .files
      .last()
      .declarations
      .filterIsInstance<IrSimpleFunction>()
      .first { it.name.asString() == "TestFunction" }

    val lastStatement = irTestFn.body!!.statements.last()
    val irExpr = when (lastStatement) {
      is IrReturn -> lastStatement.value
      is IrExpression -> lastStatement
      else -> error("unexpected last statement: $lastStatement")
    }
    val externalTypeMatchers = externalTypes.map { FqNameMatcher(it) }.toSet()
    val exprStability =
      StabilityInferencer(irModule.descriptor, externalTypeMatchers).stabilityOfExpression(irExpr)

    assertEquals(
      stability,
      exprStability.toString()
    )
  }

  private fun buildModule(
    @Language("kotlin")
    externalSrc: String,
    @Language("kotlin")
    localSrc: String,
    dumpClasses: Boolean = false,
    packageName: String = "dependency",
    externalTypes: Set<String>,
  ): IrModuleFragment {
    val dependencyFileName = "Test_REPLACEME_${uniqueNumber++}"
    val dependencySrc = """
            package $packageName
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.setValue
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.State
            import kotlin.reflect.KProperty

            class UnusedClassToEnsurePackageGetsGenerated

            $externalSrc
        """.trimIndent()

    classLoader(dependencySrc, dependencyFileName, dumpClasses, additionalPaths)
      .allGeneratedFiles
      .also {
        // Write the files to the class directory so they can be used by the next module
        // and the application
        it.writeToDir(classesDirectory.root)
      }

    val source = """
            import $packageName.*
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.setValue
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.State
            import kotlin.reflect.KProperty

            $localSrc
        """.trimIndent()

    val files = listOf(SourceFile("Test.kt", source))
    return compileToIr(files, additionalPaths + classesDirectory.root, registerExtensions = {
      it.put(ComposeConfiguration.TEST_STABILITY_CONFIG_KEY, externalTypes)
      it.updateConfiguration()
    })
  }

  private fun assertTransform(
    @Language("kotlin")
    checked: String,
    unchecked: String = "",
    dumpTree: Boolean = false,
  ) = verifyGoldenComposeIrTransform(
    checked,
    """
            $unchecked
            fun used(x: Any?) {}
        """,
    dumpTree = dumpTree
  )

  companion object {
    val additionalPaths = listOf(
      Classpath.jarFor<kotlinx.collections.immutable.ImmutableSet<*>>(), // kotlinx-collections
      Classpath.jarFor<com.google.common.collect.ImmutableSet<*>>(), // guava
      Classpath.jarFor<dagger.Lazy<*>>(), // dagger
      Classpath.jarFor<StabilityTestProtos>() // protobuf-test-classes
    )
  }
}
