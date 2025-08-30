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

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.ComposeClassIds
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.Stability
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.analysis.forEach
import androidx.compose.compiler.plugins.kotlin.analysis.hasStableMarker
import androidx.compose.compiler.plugins.kotlin.analysis.knownStable
import androidx.compose.compiler.plugins.kotlin.analysis.normalize
import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.isEnumClass
import org.jetbrains.kotlin.ir.util.isEnumEntry
import org.jetbrains.kotlin.ir.util.isFileClass
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

// $stable 필드에만 쓰임
enum class StabilityBits(val bits: Int) {
  STABLE(0b000), // == ParamState.Uncertain(0b000)
  UNSTABLE(0b100); // == ParamState.Unknown(0b100)

  // 하나의 슬롯당 3비트 할당
  fun bitsForSlot(slot: Int): Int = bitsForSlot(bits = bits, slotIndex = slot)
}

/**
 * This transform determines the stability of every class, and synthesizes a StabilityInferred
 * annotation on it, as well as putting a static final int of the stability to be used at runtime.
 */
// 이 transformer는 모든 클래스의 안정성을 결정하고 StabilityInferred 어노테이션을
// 합성하며(synthesizes) 런타임에 사용할 안정성에 대한 정적 최종 int를 넣습니다.
class ClassStabilityTransformer(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
  private val messageCollector: MessageCollector,
) : AbstractComposeLowering(
  context = context,
  metrics = metrics,
  stabilityInferencer = stabilityInferencer,
  featureFlags = featureFlags,
),
  ClassLoweringPass,
  ModuleLoweringPass {

  /**
   * This annotation is added on classes by the compiler when their stability is inferred. It
   * indicates that there will be a synthetic static final int `$stable` added to the class which can
   * be used by the compose compiler plugin to generate expressions to determine the stability of a
   * realized type at runtime.
   *
   * @param parameters A bitmask, with one bit per type parameter of the annotated class. A 1 bit
   *  indicates that the stability of the annotated class should be calculated as a combination of
   *  the stability of the class itself and the stability of that type parameter.
   */
  // 이 어노테이션은 컴파일러가 클래스의 안정성(stability)을 추론했을 때 클래스에 추가됩니다.
  // 이 어노테이션이 붙은 클래스에는 $stable이라는 합성(synthetic)된 static final int 필드가
  // 추가되며, 이는 컴포즈 컴파일러 플러그인이 런타임에 구체화된 타입(realized type)의 안정성을
  // 판별하는 식(expression)을 생성하는 데 사용됩니다.
  //
  // @param parameters 비트마스크로, 어노테이션이 붙은 클래스의 타입 매개변수마다 하나의 비트가
  //  대응됩니다. 비트가 1이면, 클래스의 안정성을 “클래스 자체의 안정성 + 해당 타입 매개변수의
  //  안정성”을 조합하여 계산해야 함을 의미합니다.
  //
  // annotation class StabilityInferred(val parameters: Int)
  private val StabilityInferredClass = getTopLevelClass(ComposeClassIds.StabilityInferred)

  private val unstableClassesWarning: MutableSet<ClassDescriptor>? = if (!context.platform.isJvm()) mutableSetOf() else null

  override fun lower(irModule: IrModuleFragment) {
    irModule.transformChildrenVoid(this)

    if (!context.platform.isJvm() && !unstableClassesWarning.isNullOrEmpty()) {
      val classIds = unstableClassesWarning.mapTo(mutableSetOf()) { it.fqNameSafe.toString() }
      val classesConcatenated = classIds.sorted().joinToString("\n")

      // 일부 의존성이 이전 버전의 Compose 컴파일러 플러그인으로 빌드되어, JVM 이외의 타겟에서
      // 추가적인(또는 무한한) 리컴포지션이 발생할 수 있습니다. 이를 방지하려면 더 새로운
      // 컴포즈 컴파일러로 빌드된 의존성 라이브러리 버전으로 업데이트하는 것을 고려하세요.
      // 현재 다음 클래스들이 Unstable로 간주됩니다.
      messageCollector.report(
        CompilerMessageSeverity.WARNING,
        "Some of the dependencies were build using an older version of the Compose compiler plugin, " +
          "which may cause additional (or endless) recompositions on non-JVM targets. " +
          "To prevent that consider updating dependency libraries to versions built with a newer " +
          "Compose compiler. Right now, the following classes are considered `Unstable`:\n" +
          classesConcatenated,
      )
    }
  }

  override fun lower(irClass: IrClass) {
  }

  override fun lower(irFile: IrFile) {
    irFile.transformChildrenVoid(this)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun visitClass(declaration: IrClass): IrStatement {
    val result = super.visitClass(declaration)
    val cls = result as? IrClass ?: return result

    // MEMO transformed이 무시되는 경우
    //   - public이나 internal이 아님
    //   - enum class, enum entry
    //   - interface
    //   - annotation class
    //   - anonymous object
    //   - expect class
    //   - inner class
    //   - file class
    //   - companion object
    //   - inline class
    if (
      (
        // Including public and internal to support incremental compilation,
        // which is separated by file.
        //
        // 증분 컴파일을 지원하기 위해 public과 internal을 포함합니다.
        // 증분 컴파일은 파일 단위로 구분되어 처리됩니다.
        cls.visibility != DescriptorVisibilities.PUBLIC &&
          cls.visibility != DescriptorVisibilities.INTERNAL
        ) ||
      cls.isEnumClass ||
      cls.isEnumEntry ||
      cls.isInterface ||
      cls.isAnnotationClass ||
      cls.isAnonymousObject ||
      cls.isExpect ||
      cls.isInner ||
      cls.isFileClass ||
      cls.isCompanion ||
      cls.defaultType.isInlineClassType()
    )
      return cls

    if (declaration.hasStableMarker()) {
      metrics.recordClass(
        declaration = declaration,
        marked = true,
        stability = Stability.Stable,
      )
      cls.addSyntheticStableField(irIntConst(StabilityBits.STABLE /* Uncertain(0b000) */.bitsForSlot(slot = 0)))
      return cls
    }

    // property, field, superclass 타입 기반으로 Stability.Combined를 추론함
    // typeParameter가 있을 경우 typeArgument와 같이(together) 추론됨
    val stabilityOfCls = stabilityInferencer.stabilityOfType(type = declaration.defaultType).normalize()

    // 안정성 추론이 가능한 typeParameter 인덱스의 비트를 1로 설정함
    // 안정으로 추론됐다면 typeParameters.size 인덱스의 비트(MSB)를 1로 설정함
    //
    // 비트가 1이면, 클래스의 안정성을 “클래스 자체의 안정성 + 해당 타입 매개변수의 안정성”을 조합하여
    // 계산해야 함을 의미합니다.
    //
    // @StabilityInferred의 인자 값
    var typeParameterMask = 0b0

    // $stable 필드에 들어갈 비트마스킹 값
    val stableExpr: IrExpression

    if (cls.typeParameters.isNotEmpty()) {
      val typeParameterSymbols = cls.typeParameters.map(IrTypeParameter::symbol)
      var hasExternalParameter = false

      stabilityOfCls.forEach { stability ->
        if (stability is Stability.Parameter) {
          val index = typeParameterSymbols.indexOf(stability.typeParameter.symbol)
          if (index != -1) {
            // the stability of this parameter matters for the stability of the class.
            // 이 매개변수의 안정성은 클래스의 안정성에 중요합니다.
            typeParameterMask = typeParameterMask or (0b1 shl index)
          } else {
            hasExternalParameter = true
          }
        }
      }

      if (stabilityOfCls.knownStable() && typeParameterSymbols.size < 32) {
        // MEMO typeParameterSymbols.size번째 비트가 1이라면 클래스 자체가 Stable함을 의미함
        typeParameterMask = typeParameterMask or (0b1 shl typeParameterSymbols.size)
      }

      stableExpr =
        if (hasExternalParameter) {
          irIntConst(StabilityBits.UNSTABLE /* Unknown(0b100) */.bitsForSlot(slot = 0))
        } else {
          stabilityOfCls.irStabilityBitsExpression(
            resolveTypeParameter = {
              // STUDY 여기는 왜 항상 Stable로 될까??
              irIntConst(StabilityBits.STABLE /* Uncertain(0b000) */.bitsForSlot(slot = 0))
            },
            reportUnknownStability = { unstableClassesWarning?.add(it.descriptor) },
          )
            ?: irIntConst(StabilityBits.UNSTABLE /* Unknown(0b100) */.bitsForSlot(slot = 0))
        }
    }

    // cls.typeParameters.isEmpty() == true
    else {
      if (stabilityOfCls.knownStable()) {
        typeParameterMask = 0b1
      }

      stableExpr = stabilityOfCls.irStabilityBitsExpression(
        resolveTypeParameter = { null },
        reportUnknownStability = { unstableClassesWarning?.add(it.descriptor) },
      ) ?: irIntConst(StabilityBits.UNSTABLE /* Unknown(0b100) */.bitsForSlot(slot = 0))
    }

    metrics.recordClass(
      declaration = declaration,
      marked = false,
      stability = stabilityOfCls,
    )

    val stabilityInferredAnnotation =
      IrConstructorCallImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = StabilityInferredClass.defaultType,
        symbol = StabilityInferredClass.constructors.first(),
        typeArgumentsCount = 0,
        constructorTypeArgumentsCount = 0,
        origin = null,
      ).also {
        it.putValueArgument(0, irIntConst(typeParameterMask))
      }

    // MEMO 'fun IrAnnotationContainer.stabilityInferredArgumentBitmask(): Int?' 호출로 이 값이 쓰임
    context.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
      declaration = cls,
      /* (vararg) annotations = */ stabilityInferredAnnotation,
    )

    // MEMO 'fun IrClass.getRuntimeStabilityValue(): IrExpression' 호출로 이 값이 쓰임
    cls.addSyntheticStableField(stabilityExpression = stableExpr)

    return result
  }

  private fun IrClass.addSyntheticStableField(stabilityExpression: IrExpression) {
    val stabilityField = makeStabilityField()
    stabilityField.initializer = context.irFactory.createExpressionBody(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      expression = stabilityExpression,
    )
  }
}
