/*
 * Copyright 2019 The Android Open Source Project
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

import androidx.compose.compiler.plugins.kotlin.ComposeClassIds
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.ComposeWritableSlices.DURABLE_FUNCTION_KEY
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.irTrace
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class KeyInfo(
  val name: String,
  val startOffset: Int,
  val endOffset: Int,
) {
  var used: Boolean = false
  val key: Int get() = name.hashCode()
}

/**
 * This transform will generate a "durable" and mostly unique key for every function in the module.
 * In this case "durable" means that when the code is edited over time, a function with the same
 * semantic identity will usually have the same key each time it is compiled. This is important so
 * that new code can be recompiled and the key that the function gets after that recompile ought to
 * be the same as before, so one could inject this new code and signal to the runtime that
 * composable functions with that key should be considered invalid.
 *
 * This transform runs early on in the lowering pipeline, and stores the keys for every function in
 * the file in the BindingTrace for each function. These keys are then retrieved later on by other
 * lowerings and marked as used. After all lowerings have completed, one can use the
 * [realizeKeyMetaAnnotations] method to generate additional annotations
 * with the keys of each function and their source locations for tooling to utilize.
 *
 * For example, this transform will run on code like the following:
 *
 *     @Composable fun Example() {
 *       Box {
 *          Text("Hello World")
 *       }
 *     }
 *
 * And produce code like the following:
 *
 *     @FunctionKeyMeta(key=123, startOffset=24, endOffset=56)
 *     @Composable fun Example() {
 *       startGroup(123)
 *       Box {
 *         startGroup(345)
 *         Text("Hello World")
 *         endGroup()
 *       }
 *       endGroup()
 *     }
 *
 * @see DurableKeyVisitor
 */
// 이 변환은 모듈의 모든 함수에 대해 “내구성”이 있고 대부분 고유한 키를 생성합니다.
// 이 경우 “내구성”이란 시간이 지나면서 코드가 편집될 때 동일한 의미적 정체성을 가진
// 함수는 일반적으로 컴파일될 때마다 동일한 키를 갖게 된다는 의미입니다. 이는 새 코드를
// 다시 컴파일할 수 있고 그 컴파일 후에 함수가 얻는 키가 이전과 동일해야 하므로, 새 코드를
// 삽입하고 런타임에 해당 키로 컴파일 가능한 함수는 유효하지 않은 것으로 간주해야 한다는
// 신호를 보내야 하기에 중요합니다.
//
// 이 변환은 lowering 파이프라인 초기에 실행되며 파일의 모든 함수에 대한 키를 각 함수에 대한
// BindingTrace에 저장합니다. 그런 다음 나중에 다른 lowering에서 이 키를 검색하여 사용된
// 것으로 표시합니다. 모든 lowering이 완료된 후에는 [realizeKeyMetaAnnotations] 메서드를
// 사용하여 각 함수의 키와 툴링에 활용할 소스 위치가 포함된 추가 어노테이션을 생성할 수 있습니다.
//
// 예를 들어, 이 변환은 다음과 같은 코드에서 실행됩니다:
//
// 그리고 다음과 같은 코드를 생성합니다:
class DurableFunctionKeyTransformer(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
) : DurableKeyTransformer(
  keyVisitor = DurableKeyVisitor(),
  context = context,
  stabilityInferencer = stabilityInferencer,
  metrics = metrics,
  featureFlags = featureFlags,
) {
  private val keyMetaAnnotation = getTopLevelClassOrNull(ComposeClassIds.FunctionKeyMeta)

  private fun irKeyMetaAnnotation(key: KeyInfo): IrConstructorCall =
    IrConstructorCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = keyMetaAnnotation!!.defaultType,
      symbol = keyMetaAnnotation.constructors.single(),
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0,
    ).apply {
      putValueArgument(0, irIntConst(key.key.hashCode()))
      putValueArgument(1, irIntConst(key.startOffset))
      putValueArgument(2, irIntConst(key.endOffset))
    }

  // STUDY 왜 `declaration.annotations += irKeyMetaAnnotation(functionKey)`를 같이 안 할까?
  //  아마 `used=true`인 `KeyInfo`만 BindingTrace에 남기는 듯?
  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    val signature = declaration.signatureString()
    val (fullName, _) = buildKey("fun-$signature")
    val info = KeyInfo(
      name = fullName,
      startOffset = declaration.startOffset,
      endOffset = declaration.endOffset,
    )
    context.irTrace.record(DURABLE_FUNCTION_KEY, declaration, info)
    return super.visitSimpleFunction(declaration)
  }

  fun realizeKeyMetaAnnotations(moduleFragment: IrModuleFragment) {
    moduleFragment.transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        run transform@{
          val functionKey = context.irTrace[DURABLE_FUNCTION_KEY, declaration] ?: return@transform
          if (!declaration.hasComposableAnnotation()) return@transform
          if (declaration.hasAnnotation(ComposeClassIds.FunctionKeyMeta)) return@transform
          declaration.annotations += irKeyMetaAnnotation(functionKey)
        }

        return super.visitSimpleFunction(declaration)
      }
    })
  }
}
