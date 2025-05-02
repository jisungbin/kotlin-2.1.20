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
import org.jetbrains.kotlin.ir.IrImplementationDetail
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
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

enum class StabilityBits(val bits: Int) {
  UNSTABLE(0b100),
  STABLE(0b000);

  // 하나의 슬릇당 3비트 할당
  fun bitsForSlot(slot: Int): Int = bits shl (1 + (slot * 3))
}

/**
 * This transform determines the stability of every class, and synthesizes a StabilityInferred
 * annotation on it, as well as putting a static final int of the stability to be used at runtime.
 */
// 이 트랜스폼은 모든 클래스의 안정성을 결정하고 StabilityInferred 어노테이션을 합성하며(synthesizes)
// 런타임에 사용할 안정성에 대한 정적 최종 int를 넣습니다.
class ClassStabilityTransformer(
  private val useK2: Boolean,
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  // always null in K2
  private val classStabilityInferredCollection: ClassStabilityInferredCollection? = null,
  featureFlags: FeatureFlags,
  private val messageCollector: MessageCollector,
) : AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags),
  ClassLoweringPass,
  ModuleLoweringPass {

  private val StabilityInferredClass = getTopLevelClass(ComposeClassIds.StabilityInferred)
  private val UNSTABLE = StabilityBits.UNSTABLE.bitsForSlot(0)
  private val STABLE = StabilityBits.STABLE.bitsForSlot(0)
  private val unstableClassesWarning: MutableSet<ClassDescriptor>? = if (!context.platform.isJvm()) mutableSetOf() else null


  override fun lower(irModule: IrModuleFragment) {
    irModule.transformChildrenVoid(this)

    if (!context.platform.isJvm() && !unstableClassesWarning.isNullOrEmpty()) {
      val classIds = unstableClassesWarning.mapTo(mutableSetOf()) { it.fqNameSafe.toString() }
      val classesConcatenated = classIds.sorted().joinToString("\n")
      messageCollector.report(
        CompilerMessageSeverity.WARNING,
        "Some of the dependencies were build using an older version of the Compose compiler plugin, " +
          "which may cause additional (or endless) recompositions on non-JVM targets. " +
          "To prevent that consider updating dependency libraries to versions built with a newer Compose compiler. " +
          "Right now, the following classes are considered `Unstable`:\n" +
          classesConcatenated
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

    if (
      (
        // Including public and internal to support incremental compilation, which
        // is separated by file.
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
      metrics.recordClass(declaration = declaration, marked = true, stability = Stability.Stable)
      cls.addStabilityMarkerField(irIntConst(STABLE))
      return cls
    }

    val stability = stabilityInferencer.stabilityOfType(declaration.defaultType).normalize()

    // remove type parameters

    var parameterMask = 0
    val stableExpr: IrExpression

    if (cls.typeParameters.isNotEmpty()) {
      val typeParameterSymbols = cls.typeParameters.map { it.symbol }
      var externalParameters = false

      stability.forEach {
        when (it) {
          is Stability.Parameter -> {
            val index = typeParameterSymbols.indexOf(it.typeParameter.symbol)
            if (index != -1) {
              // the stability of this parameter matters for the stability of the class.
              // 이 매개변수의 안정성은 클래스의 안정성에 중요합니다.
              parameterMask = parameterMask or (0b1 shl index)
            } else {
              externalParameters = true
            }
          }
          else -> {
            /* No action necessary */
          }
        }
      }

      if (stability.knownStable() && typeParameterSymbols.size < 32) {
        parameterMask = parameterMask or (0b1 shl typeParameterSymbols.size)
      }

      stableExpr = when (externalParameters) {
        true -> irIntConst(UNSTABLE)
        else -> stability.irStabilityBitsExpression(
          resolveTypeParameter = { irIntConst(STABLE) },
          reportUnknownStability = { unstableClassesWarning?.add(it.descriptor) },
        ) ?: irIntConst(UNSTABLE)
      }
    } else {
      stableExpr =
        stability.irStabilityBitsExpression(reportUnknownStability = { unstableClassesWarning?.add(it.descriptor) }) ?: irIntConst(UNSTABLE)

      if (stability.knownStable()) {
        parameterMask = 0b1
      }
    }

    metrics.recordClass(
      declaration = declaration,
      marked = false,
      stability = stability,
    )

    val annotation = IrConstructorCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = StabilityInferredClass.defaultType,
      symbol = StabilityInferredClass.constructors.first(),
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0,
      origin = null
    ).also {
      it.putValueArgument(0, irIntConst(parameterMask))
    }

    if (useK2 && cls.hasFirDeclaration()) {
      context.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(declaration = cls, annotation)
    } else {
      cls.annotations += annotation
      classStabilityInferredCollection?.addClass(cls, parameterMask)
    }

    cls.addStabilityMarkerField(stableExpr)
    return result
  }

  @OptIn(IrImplementationDetail::class, UnsafeDuringIrConstructionAPI::class)
  private fun IrClass.addStabilityMarkerField(stabilityExpression: IrExpression) {
    val stabilityField = makeStabilityField()
    stabilityField.initializer = context.irFactory.createExpressionBody(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      expression = stabilityExpression,
    )
  }
}
