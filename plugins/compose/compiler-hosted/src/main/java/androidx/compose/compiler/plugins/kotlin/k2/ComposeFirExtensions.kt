/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.compiler.plugins.kotlin.k2

import androidx.compose.compiler.plugins.kotlin.ComposeClassIds
import org.jetbrains.kotlin.builtins.functions.FunctionTypeKind
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCallableReferenceAccessChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirPropertyAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirFunctionTypeKindExtension
import org.jetbrains.kotlin.name.FqName

class ComposeFirExtensionRegistrar : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::ComposableFunctionTypeKindExtension
    +::ComposeFirCheckersExtension
  }
}

class ComposableFunctionTypeKindExtension(
  session: FirSession,
) : FirFunctionTypeKindExtension(session) {
  override fun FunctionTypeKindRegistrar.registerKinds() {
    registerKind(ComposableFunction, KComposableFunction)
  }
}

// Serialize composable function types as normal function types with the
// @Composable annotation instead of using K2 specific metadata for custom
// function types. This is to allow the K1 compose compiler plugin
// to understand libraries produced with the K2 compose compiler plugin.
//
// We use the latest value in the LanguageVersion enum to make sure that
// we do not have to hardcode a version here and have control over when
// we start using the K2 only serialization format. We need to wait until
// all compose users consuming K2 produced libraries are also using K2.

// 컴포저블 함수 유형을 사용자 정의 함수 유형에 K2 전용 메타데이터를 사용하는 대신
// @Composable 어노테이션을 사용하여 일반 함수 유형으로 직렬화합니다.
// 이는 K1 컴파일러 플러그인이 K2 컴파일러 플러그인으로 생성된 라이브러리를
// 이해할 수 있도록 하기 위한 것입니다.
//
// 여기서 버전을 하드코딩할 필요가 없고 K2 전용 직렬화 형식을 사용하기 시작하는
// 시기를 제어할 수 있도록 LanguageVersion 열거형에서 최신 값을 사용합니다.
// K2로 제작된 라이브러리를 사용하는 모든 컴포즈 사용자가 K2를 사용할 때까지
// 기다려야 합니다.
private val useLegacyCustomFunctionTypeSerializationUntil: String
  get() {
    require(!LanguageVersion.values().last().isStable) {
      // `언어 버전` 열거형의 마지막 값은 안정적인 버전이 아닐 것으로 예상됩니다.
      "Last value in `LanguageVersion` enum is not expected to be a stable version."
    }
    return LanguageVersion.values().last().versionString
  }

// [FunctionTypeKind]는 다양한 유사한 함수형 유형(예: kotlin.FunctionN)의 제품군을 설명합니다.
// 같은 계열의 모든 유형은 해당되는 형태의 classId를 갖습니다: [packageFqName].[classNamePrefix]N,
// 여기서 `N`은 함수의 아리티입니다.
//
// 함수형 유형 종류는 리플렉트 유형 또는 비리플렉트 유형일 수 있습니다.
// 각 비반사 유형에는 해당하는 반사 유형이 있어야 하며, 그 반대의 경우도 마찬가지입니다.
// (예: `kotlin.FunctionN` 및 `kotlin.reflection.KFunctionN`).
//
// **이러한 함수형 유형에 대한 클래스는 합성이며 컴파일러 자체에서 생성됩니다.** <--!!! ComposableFunction이 Compose Runtime에 없는 이유.
//
// [annotationOnInvokeClassId]는 생성된 함수 인터페이스의 `invoke` 함수에 추가될 어노테이션의
// classId입니다. 이 인수는 비표준(non-standard) 함수형의 경우 필수입니다.
//
// `some.CustomFunctionN` 및 `some.KCustomFunctionN` 패밀리가 있고 [annotationOnInvokeClassId] = some.AnnoClass가
// 있다고 가정하면 해당 종류에 대한 클래스는 다음과 같이 보입니다:
//
// interface CustomFunctionN<P1, P2, ..., PN, R> : kotlin.Function {
//   @some.Anno operator fun invoke(p1: P1, p2: P2, ..., pN: PN): R
// }
//
// interface KCustomFunctionN<P1, P2, ..., PN, R> : kotlin.reflect.KFunction, some.CustomFunctionN<P1, P2, ..., PN, R> {
//   @some.Anno override operator fun invoke(p1: P1, p2: P2, ..., pN: PN): R
// }
//
// [isInlineable] 매개변수는 특정 함수형 종류가 인라인 가능한지 여부를 결정하여 컴파일러가
// `DECLARATION_CANT_BE_INLINED`와 같은 인라인 가능성에 대한 진단을 제대로(가능하다면) 보고할 수 있게 해줍니다.
//
// 새로운 함수형 유형을 제공하는 경우 백엔드에서 [IrGenerationExtension] 구현으로 모든 참조를
// 처리하는 것은 사용자의 책임입니다.
object ComposableFunction : FunctionTypeKind(
  FqName("androidx.compose.runtime.internal"),
  "ComposableFunction",
  ComposeClassIds.Composable,
  isReflectType = false,
  isInlineable = true,
) {
  override val prefixForTypeRender: String
    get() = "@Composable"

  // 사용자 지정 함수 유형을 kotlin 메타데이터로 직렬화할 첫 번째 언어 버전을 지정합니다.
  // 해당 버전 전까지는 사용자 지정 함수 유형이 레거시 체계(사용자 지정 함수 유형에 사용된
  // 어노테이션이 포함된 FunctionN/KFunctionN)로 직렬화됩니다.
  //
  // 버전을 지정하지 않으면 사용자 지정 함수 유형이 kotlin 메타데이터로 직렬화됩니다.
  //
  // 레거시 형식을 사용하여 직렬화하면 사용자 지정 함수 유형을 사용하는 K2 플러그인을
  // 사용하여 K2로 컴파일된 라이브러리를 사용자 지정 함수 유형을 이해하는 K1 컴파일러
  // 플러그인을 사용하여 K1 컴파일러를 사용하는 클라이언트에서 사용할 수 있습니다.
  override val serializeAsFunctionWithAnnotationUntil: String
    get() = useLegacyCustomFunctionTypeSerializationUntil

  override fun reflectKind(): FunctionTypeKind = KComposableFunction
}

object KComposableFunction : FunctionTypeKind(
  FqName("androidx.compose.runtime.internal"),
  "KComposableFunction",
  ComposeClassIds.Composable,
  isReflectType = true,
  isInlineable = false,
) {
  override val serializeAsFunctionWithAnnotationUntil: String
    get() = useLegacyCustomFunctionTypeSerializationUntil

  override fun nonReflectKind(): FunctionTypeKind = ComposableFunction
}

class ComposeFirCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val functionCheckers: Set<FirFunctionChecker> =
      setOf(ComposableFunctionChecker)

    override val propertyCheckers: Set<FirPropertyChecker> =
      setOf(ComposablePropertyChecker)
  }

  override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
    override val functionCallCheckers: Set<FirFunctionCallChecker> =
      setOf(ComposableFunctionCallChecker)

    override val propertyAccessExpressionCheckers: Set<FirPropertyAccessExpressionChecker> =
      setOf(ComposablePropertyAccessExpressionChecker)

    override val callableReferenceAccessCheckers: Set<FirCallableReferenceAccessChecker> =
      setOf(ComposableCallableReferenceChecker)
  }
}
