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

import androidx.compose.compiler.plugins.kotlin.ComposeCallableIds
import androidx.compose.compiler.plugins.kotlin.ComposeFqNames
import androidx.compose.compiler.plugins.kotlin.ComposeNames
import androidx.compose.compiler.plugins.kotlin.FeatureFlag
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.FunctionMetrics
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.ComposeWritableSlices
import androidx.compose.compiler.plugins.kotlin.analysis.Stability
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.analysis.isUncertain
import androidx.compose.compiler.plugins.kotlin.analysis.knownStable
import androidx.compose.compiler.plugins.kotlin.analysis.knownUnstable
import androidx.compose.compiler.plugins.kotlin.irTrace
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.min
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.backend.jvm.ir.isInlineLambda
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrImplementationDetail
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isClassWithFqName
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.isFinalClass
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.isOverridableOrOverrides
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI

/**
 * An enum of the different "states" a parameter of a composable function can have relating to
 * comparison propagation. Each state is represented by two bits in the `$changed` bitmask.
 *
 * 비교 전파(comparison propagation)와 관련하여, 컴포저블 함수의 파라미터가 가질 수 있는 다양한
 * "상태"를 나타냅니다. 각 상태는 $changed 비트마스크에서 두 비트로 표현됩니다.
 */
enum class ParamState(val bits: Int) {
  /**
   * Indicates that nothing is certain about the current state of the parameter. It could be
   * different than it was during the last execution, or it could be the same, but it is not
   * known so the current function looking at it must call equals on it in order to find out.
   * This is the only state that can cause the function to spend slot table space in order to
   * look at it.
   *
   * 현재 파라미터의 상태에 대해 확실한 것이 아무것도 없음을 나타냅니다. 이전 실행 시점과
   * 다를 수도 있고 같을 수도 있지만, 알 수 없기 때문에 해당 값을 참조하는 함수는 이를 확인하기
   * 위해 equals를 호출해야 합니다. 이 상태만이 함수가 해당 값을 확인하기 위해 슬롯 테이블
   * 공간을 사용할 수 있게 만듭니다.
   */
  Uncertain(0b000), // "이를 확인하기 위해 equals를 호출해야 합니다" -> Stable로 간주

  /**
   * This indicates that the value is known to be the same since the last time the function was
   * executed. There is no need to store the value in the slot table in this case because the
   * calling function will *always* know whether the value was the same or different as it was
   * in the previous execution.
   *
   * 이 상태는 해당 값이 이전 함수 실행 이후로 동일하다는 것이 알려져 있음을 나타냅니다.
   * 이 경우 슬롯 테이블에 값을 저장할 필요가 없습니다. 호출하는 함수는 해당 값이 이전
   * 실행 시점과 동일한지 여부를 항상 알고 있기 때문입니다. (=> 동일함을 알고 있음)
   */
  Same(0b001), // "동일한지 여부를 항상 알고 있기 때문" -> Stable로 간주

  /**
   * This indicates that the value is known to be different since the last time the function
   * was executed. There is no need to store the value in the slot table in this case because
   * the calling function will *always* know whether the value was the same or different as it
   * was in the previous execution.
   *
   * 이 상태는 해당 값이 이전 함수 실행 이후로 달라졌다는 것이 알려져 있음을 나타냅니다.
   * 이 경우 슬롯 테이블에 값을 저장할 필요가 없습니다. 호출하는 함수는 해당 값이 이전 실행
   * 시점과 동일한지 여부를 항상 알고 있기 때문입니다. (=> 다름을 알고 있음)
   */
  Different(0b010), // "동일한지 여부를 항상 알고 있기 때문" -> Stable로 간주

  /**
   * This indicates that the value is known to *never change* for the duration of the running
   * program.
   *
   * 이 상태는 해당 값이 프로그램 실행 동안 절대로 변경되지 않음이 알려져 있음을 나타냅니다.
   * (=> 항상 동일함)
   */
  Static(0b011), // "절대로 변경되지 않음이 알려져 있음" -> Stable로 간주

  Unknown(0b100), // -> Unstable

  // ParamState가 가질 수 있는 최대 비트
  Mask(0b111);

  fun bitsForSlot(slot: Int): Int = bitsForSlot(bits = bits, slotIndex = slot)
}

// Chat GPT 설명:
// SLOTS_PER_INT는 하나의 정수(Int)에 저장할 수 있는 "슬롯"의 개수를 의미하는 것으로 보입니다.
//
// 코드에서 BITS_PER_SLOT이 3으로 정의되어 있고, SLOTS_PER_INT가 10으로 정의되어 있습니다.
// 이는 각 슬롯이 3비트를 차지하며, 하나의 정수에는 이러한 슬롯이 10개 들어갈 수 있다는 것을
// 나타냅니다 (3 bits/slot * 10 slots = 30 bits). BITS_PER_INT가 31인 것을 고려하면, 이는 `$changed`
// 매개변수의 상태와 같은 정보를 비트 단위로 압축하여 하나의 정수에 여러 개 저장하려는 의도로 보입니다.

// 하나의 Int에 저장할 수 있는 최대 비트 수 (32 - LSB => 31)
const val BITS_COUNT_PER_INT = 31

// 하나의 Int에 저장할 수 있는 최대 슬롯 수 (슬롯 하나당 3비트)
const val SLOTS_COUNT_PER_INT = 10

// 하나의 슬롯당 비트 수
const val BITS_COUNT_PER_SLOT = 3

/**
 * [bits] 값을 [slotIndex]번째 슬롯에 저장할 수 있도록, 0으로 shift left된
 * 값을 반환합니다.
 *
 * '1 (2)'를 3번째 슬롯으로 bitsForSlot 하면 '1 000 000 000 0 (2)'가 반환됩니다.
 */
fun bitsForSlot(bits: Int, slotIndex: Int): Int {
  val realSlot = slotIndex % SLOTS_COUNT_PER_INT

  // +1: LSB를 건너뛰기 위한 추가 오프셋
  return bits shl (realSlot * BITS_COUNT_PER_SLOT + 1)
}

fun defaultParamIndex(index: Int): Int = index / BITS_COUNT_PER_INT

fun defaultBitIndex(index: Int): Int = index % BITS_COUNT_PER_INT

/** The number of implicit('this') parameters the function has. */
val IrFunction.thisParamCount
  get() = parameters.count {
    it.kind == IrParameterKind.DispatchReceiver ||
      it.kind == IrParameterKind.ExtensionReceiver ||
      it.kind == IrParameterKind.Context
  }

/**
 * Calculates the number of 'changed' params needed based on the function's parameters.
 *
 * 함수의 매개변수를 기반으로 필요한 '$changed' 매개변수 수를 계산합니다.
 *
 * @param realValueParamCount The number of params defined by the user, those that are
 * not implicit (no extension or context receivers) or synthetic (no %composer, %changed
 * or %defaults).
 *
 * 사용자가 정의한 매개변수 중 암시적(extension 또는 contextReceiver 없음) 또는 synthetic
 * (%composer, %changed 또는 %defaults 없음)이 아닌 매개변수의 수입니다.
 *
 * @param thisParamCount The number of implicit params, i.e. [IrFunction.thisParamCount].
 *
 * 암시적 매개변수 수.
 */
// 하나의 $changed 매개변수가 최대 10개의 매개변수를 표현할 수 있음.
// 각각 매개변수의 [ParamState]를 %changed의 각 슬롯에 저장함.
fun changedParamCount(realValueParamCount: Int, thisParamCount: Int): Int {
  val totalParamCount = realValueParamCount + thisParamCount
  if (totalParamCount == 0) return 1 // There is always at least 1 changed param

  // ceil: 수 올림 (5.2 -> 6.0)
  return ceil(totalParamCount.toDouble() / SLOTS_COUNT_PER_INT.toDouble()).toInt()
}

/**
 * Calculates the number of '$changed' params needed based on the function's total amount of
 * parameters.
 *
 * 함수의 전체 파라미터 개수를 기준으로 ‘$changed’ 파라미터의 수를 계산합니다.
 *
 * @param totalParamsIncludingThisParams The total number of parameter including implicit and
 * synthetic ones.
 *
 * 암시적 및 생성된(synthetic) 파라미터를 포함한 전체 파라미터 수입니다.
 */
fun changedParamCountFromTotal(totalParamsIncludingThisParams: Int): Int {
  var realParams = totalParamsIncludingThisParams
  realParams-- // composer param
  realParams-- // first changed param (always present)

  var changedParams = 0
  do {
    realParams -= SLOTS_COUNT_PER_INT
    changedParams++
  } while (realParams > 0)

  return changedParams
}

/**
 * Calculates the number of 'defaults' params needed based on the function's parameters.
 *
 * 함수의 파라미터를 기준으로 ‘$defaults’ 파라미터의 개수를 계산합니다.
 *
 * @param valueParamCount The numbers of params, usually the size of [IrFunction.valueParameters].
 * Which includes context receivers params, but not extension param nor synthetic params.
 *
 * 파라미터의 개수입니다. 일반적으로 [IrFunction.valueParameters]의 크기를 의미하며,
 * 여기에는 context receivers 파라미터는 포함되지만, extension 파라미터나 synthetic
 * 파라미터는 포함되지 않습니다.
 */
fun defaultParamCount(valueParamCount: Int): Int =
  // ceil: 수 올림 (5.2 -> 6.0)
  ceil(valueParamCount.toDouble() / BITS_COUNT_PER_INT.toDouble()).toInt()

@JvmDefaultWithCompatibility
sealed interface IrChangedBitMaskValue {
  val used: Boolean
  val declarations: List<IrValueDeclaration>

  fun irShiftBits(fromSlot: Int, toSlot: Int): IrExpression
  fun irSlotAnd(slot: Int, bits: Int): IrExpression

  fun irIsolateBitsAtSlot(slot: Int, includeStableBit: Boolean): IrExpression
  fun irStableBitAtSlot(slot: Int): IrExpression

  fun irGetLowBit(): IrExpression
  fun irRestartFlags(): IrExpression
  fun irHasDifferences(usedParams: BooleanArray): IrExpression

  fun irCopyToDirtyVariable(
    nameHint: String? = null,
    isVar: Boolean = false,
    exactName: Boolean = false,
  ): IrChangedBitMaskVariable

  fun putAsValueArgumentInWithLowBit(
    fn: IrFunctionAccessExpression,
    paramIndex: Int,
    lowBit: Boolean,
  )
}

// 컴포저블이 리컴포지션될 가능성이 있다면 'var $dirty = $changed'로 $changed를 복사함.
// $dirty라면 ChangedVariable이고, $changed라면 ChangedValue임.
@JvmDefaultWithCompatibility
interface IrChangedBitMaskVariable : IrChangedBitMaskValue {
  fun irSetSlotUncertain(slot: Int): IrExpression
  fun irOrSetBitsAtSlot(slot: Int, value: IrExpression): IrExpression

  fun getDirtyVariables(): List<IrStatement>
}

interface IrDefaultBitMaskValue {
  fun irGetBitAtIndex(index: Int): IrExpression

  fun irHasAnyProvidedAndUnstable(unstable: BooleanArray): IrExpression

  fun putAsValueArgumentIn(fn: IrFunctionAccessExpression, paramIndex: Int)
}

/**
 * This IR Transform is responsible for the main transformations of the body of a composable
 * function.
 *
 * 1. Control-Flow Group Generation
 * 2. Default arguments
 * 3. Composable Function Skipping
 * 4. Comparison Propagation
 * 5. Recomposability
 * 6. Source location information (when enabled)
 *
 * Control-Flow Group Generation
 * =============================
 *
 * This transform will insert groups inside of the bodies of Composable functions
 * depending on the control-flow structures that exist inside of them.
 *
 * There are 3 types of groups in Compose:
 *
 * 1. Replace Groups
 * 2. Movable Groups
 * 3. Restart Groups
 *
 * Generally speaking, every composable function *must* emit a single group when it executes.
 * Every group can have any number of children groups. Additionally, we analyze each executable
 * block and apply the following rules:
 *
 * 1. If a block executes exactly 1 time always, no groups are needed
 * 2. If a set of blocks are such that exactly one of them is executed exactly once (for example,
 * the result blocks of a when clause), then we insert a replace group around each block.
 * 3. A movable group is only needed if the immediate composable call in the group has a Pivotal
 * property.
 *
 * Default Arguments
 * =================
 *
 * Composable functions need to have the default expressions executed inside of the group of the
 * function. In order to accomplish this, composable functions handle default arguments
 * themselves, instead of using the default handling of kotlin. This is also a win because we can
 * handle the default arguments without generating an additional function since we do not need to
 * worry about callers from java.
 *
 * Generally speaking though, compose handles default arguments similarly to kotlin in that we
 * generate a $default bitmask parameter which maps each parameter index to a bit on the int.
 * A value of "1" for a given parameter index indicated that that value was *not* provided at
 * the callsite, and the default expression should be used instead.
 *
 *     @Composable fun A(x: Int = 0) {
 *       f(x)
 *     }
 *
 * gets transformed into
 *
 *     @Composable fun A(x: Int, $default: Int) {
 *       val x = if ($default and 0b1 != 0) 0 else x
 *       f(x)
 *     }
 *
 * Note: This transform requires [ComposableFunctionParamTransformer] to also be run in order to work
 * properly.
 *
 * Composable Function Skipping
 * ============================
 *
 * Composable functions can "skip" their execution if certain conditions are met. This is done by
 * appealing to the composer and storing previous values of functions and determining if we can
 * skip based on whether or not they have changed.
 *
 *     @Composable fun A(x: Int) {
 *       f(x)
 *     }
 *
 * gets transformed into
 *
 *     @Composable fun A(x: Int, $composer: Composer<*>, $changed: Int) {
 *       var $dirty = $changed
 *       if ($changed and 0b0110 == 0) {
 *         $dirty = $dirty or if ($composer.changed(x)) 0b0010 else 0b0100
 *       }
 *      if (%dirty and 0b1011 != 0b1010 || !$composer.skipping) {
 *        f(x)
 *      } else {
 *        $composer.skipToGroupEnd()
 *      }
 *     }
 *
 * Note that this makes use of bitmasks for the $changed and $dirty values. These bitmasks work
 * in a different bit-space than the $default bitmask because three bits are needed to hold the
 * six different possible states of each parameter. Additionally, the lowest bit of the bitmask
 * is a special bit which forces execution of the function.
 *
 * This means that for the ith parameter of a composable function, the bit range of i*3 + 1 to
 * i*3 + 3 are used to store the state of the parameter.
 *
 * The states are outlines by the [ParamState] class.
 *
 * Comparison Propagation
 * ======================
 *
 * Because we detect changes in parameters of composable functions and have that data available
 * in the body of a composable function, if we pass values to another composable function, it
 * makes sense for us to pass on whatever information about that value we can determine at the
 * time. This type of propagation of information through composable functions is called
 * Comparison Propagation.
 *
 * Essentially, this comes down to us passing in useful values into the `$changed` parameter of
 * composable functions.
 *
 * When a composable function executes, we have the current known states of all of the function's
 * parameters in the $dirty variable. We can take bits off of this variable and pass them into a
 * composable function in order to tell that function what we know.
 *
 *     @Composable fun A(x: Int) {
 *       B(x, 123)
 *     }
 *
 * gets transformed into
 *
 *     @Composable fun A(x: Int, $composer: Composer<*>, $changed: Int) {
 *       var $dirty = ...
 *       // ...
 *       B(
 *           x,
 *           123,
 *           $composer,
 *           (0b110 and $dirty) or   // 1st param has same state that our 1st param does
 *           0b11000                 // 2nd parameter is "static"
 *       )
 *     }
 *
 * Recomposability
 * ===============
 *
 * Restartable composable functions get wrapped with "restart groups". Restart groups are like
 * other groups except the end call is more complicated, as it returns a null value if and
 * only if a subscription to that scope could not have occurred. If the value returned is
 * non-null, we generate a lambda that teaches the runtime how to "restart" that group. At a high
 * level, this transform comes down to:
 *
 *     @Composable fun A(x: Int) {
 *       f(x)
 *     }
 *
 * getting transformed into
 *
 *     @Composable fun A(x: Int, $composer: Composer<*>, $changed: Int) {
 *       $composer.startRestartGroup()
 *       // ...
 *       f(x)
 *       $composer.endRestartGroup()?.updateScope { next -> A(x, next, $changed or 0b1) }
 *     }
 *
 * Source information
 * ==================
 *
 * To enable Android Studio and similar tools to inspect a composition, source information is
 * optionally generated into the source to indicate where call occur in a block. The first group
 * of every function is also marked to correspond to indicate that the group corresponds to a call
 * and the source location of the caller can be determined from the containing group.
 */
/**
 * 이 IR 변환기는 컴포저블 함수 본문에 대한 주요 변환들을 담당합니다.
 *
 * 	1.	제어 흐름 그룹 생성
 * 	2.	기본 인자 처리
 * 	3.	컴포저블 함수 스킵 처리
 * 	4.	비교 전파
 * 	5.	리컴포지션 가능성
 * 	6.	소스 위치 정보 (활성화된 경우)
 *
 * ⸻
 *
 * ## 제어 흐름 그룹 생성
 *
 * 이 변환기는 컴포저블 함수 내부의 제어 흐름 구조에 따라 함수 본문 안에 그룹을 삽입합니다.
 *
 * Compose에는 다음과 같은 3가지 그룹이 있습니다:
 *
 * 	1.	Replace 그룹
 * 	2.	Movable 그룹
 * 	3.	Restart 그룹
 *
 * 일반적으로, 모든 컴포저블 함수는 실행 시 단일 그룹을 반드시 생성해야 합니다. 모든 그룹은
 * 여러 개의 하위 그룹을 가질 수 있습니다. 또한, 각 실행 가능한 블록에 대해 다음 규칙을 적용합니다:
 *
 * 1. 항상 정확히 한 번만 실행되는 블록의 경우 그룹이 필요하지 않습니다.
 * 2. 여러 블록 중 하나만 정확히 한 번 실행되는 구조(예: when 절의 결과 블록들)인 경우, 각 블록을
 *    replace 그룹으로 감쌉니다.
 * 3. 그룹 내 즉시 호출되는 컴포저블이 Pivotal 속성을 가지는 경우에만 movable 그룹이 필요합니다.
 *
 * ⸻
 *
 * ## 기본 인자 처리
 *
 * 컴포저블 함수는 기본 인자 표현식을 함수의 그룹 내부에서 실행해야 합니다. 이를 위해 컴포저블
 * 함수는 Kotlin의 기본 인자 처리 방식이 아닌, 자체적으로 처리합니다. 이는 Java 호출자를 고려할
 * 필요가 없기 때문에 별도의 함수를 생성하지 않고도 기본 인자를 처리할 수 있다는 점에서 이점이
 * 있습니다.
 *
 * Compose는 Kotlin과 유사하게 $default라는 비트마스크 인자를 생성하여, 각 파라미터 인덱스를
 * 정수의 비트와 매핑합니다. **해당 비트가 1인 경우, 호출 시 인자가 제공되지 않았음을 의미하며
 * 기본값을 사용해야 합니다.**
 *
 * 파라미터 인덱스 비트마스킹 예시:
 *  - 0번째 매개변수: (%default and 0b0001 != 0)
 *  - 1번째 매개변수: (%default and 0b0010 != 0)
 *  - 2번째 매개변수: (%default and 0b0100 != 0)
 *
 * ```
 * @Composable fun A(x: Int = 0) {
 *   f(x)
 * }
 * ```
 *
 * 위 함수는 다음과 같이 변환됩니다:
 *
 * ```
 * @Composable fun A(x: Int?, $default: Int) {
 *   val x = if ($default and 0b1 != 0) 0 else x
 *   f(x)
 * }
 * ```
 *
 * `A()`로 호출하는 곳은 `A(x = null)`로 변환됩니다.
 *
 * 참고: 이 변환이 제대로 작동하려면 [ComposableFunctionParamTransformer]가 함께 실행되어야 합니다.
 *
 * ⸻
 *
 * ## 컴포저블 함수 스킵 처리
 *
 * 특정 조건이 충족되면, 컴포저블 함수의 실행을 "스킵"할 수 있습니다. 이는 Composer가
 * 이전 값들을 저장하고 해당 값들이 변경되었는지를 기반으로 판단하여 수행됩니다.
 *
 * ```
 * @Composable fun A(x: Int) {
 *   f(x)
 * }
 * ```
 *
 * 위 함수는 다음과 같이 변환됩니다:
 *
 * ```
 * @Composable fun A(x: Int, $composer: Composer<*>, $changed: Int) {
 *   var $dirty = $changed
 *   if ($changed and 0b0110 == 0) {
 *     $dirty = $dirty or if ($composer.changed(x)) 0b0010 else 0b0100
 *   }
 *   if ($dirty and 0b1011 != 0b1010 || !$composer.skipping) {
 *     f(x)
 *   } else {
 *     $composer.skipToGroupEnd()
 *   }
 * }
 * ```
 *
 * 여기서 $changed와 $dirty는 비트마스크로 동작하며, $default 비트마스크와는 다른 공간을 사용합니다.
 * 각 파라미터의 상태를 나타내기 위해 세 개의 비트가 필요하며, 가장 낮은 비트는 강제로 함수 실행을
 * 유도하는 특수한 역할을 합니다. (=> LSB가 1로 제공되면 강제 리컴포지션?)
 *
 * 즉, i번째 파라미터는 비트마스크 상에서 i * 3 + 1부터 i * 3 + 3까지의 비트 범위를 사용합니다.
 *
 * 각 상태는 [ParamState] 클래스에 정의되어 있습니다.
 *
 * ⸻
 *
 * ## 비교 전파
 *
 * 컴포저블 함수의 파라미터 변화 감지를 통해, 해당 값에 대한 정보를 다른 컴포저블 함수에 전달할
 * 수 있습니다. 이러한 값의 상태 정보를 함께 넘기는 방식을 비교 전파(Comparison Propagation) 라고
 * 합니다.
 *
 * 즉, 다른 컴포저블 함수 호출 시 $changed 인자에 유의미한 값을 전달합니다.
 *
 * 함수가 실행될 때 모든 파라미터의 상태 정보는 $dirty 변수에 저장되어 있으며, 이 변수에서
 * 필요한 비트를 추출하여 다른 컴포저블 함수에 넘김으로써 상태 정보를 공유할 수 있습니다.
 *
 * ```
 * @Composable fun A(x: Int) {
 *   B(x, 123)
 * }
 * ```
 *
 * 위 함수는 다음과 같이 변환됩니다:
 *
 * ```
 * @Composable fun A(x: Int, $composer: Composer<*>, $changed: Int) {
 *   var $dirty = ...
 *   // ...
 *   B(
 *     x,
 *     123,
 *     $composer,
 *     (0b110 and $dirty) or  // 첫 번째 인자는 A의 첫 번째 인자와 동일한 상태
 *     0b11000                // 두 번째 인자는 "static" 상태
 *   )
 * }
 * ```
 *
 * ⸻
 *
 * ## 리컴포지션 가능성
 *
 * 재시작 가능한 composable 함수는 "restart group"으로 감싸집니다. Restart group은 다른 group과
 * 유사하지만, 종료 호출이 더 복잡하며, 해당 scope(RecomposeScope)에 대한 구독이 발생하지 않았을
 * 경우에는 null 값을 반환합니다. 반환된 값이 null이 아니라면, 해당 group을 "재시작"하는 방법을
 * 런타임에 알려주는 람다를 생성합니다. 높은 수준에서 이 변환은 다음과 같이 요약됩니다:
 *
 * ```
 * @Composable fun A(x: Int) {
 *   f(x)
 * }
 * ```
 *
 * 위 함수는 다음과 같이 변환됩니다:
 *
 * ```
 * @Composable fun A(x: Int, $composer: Composer<*>, $changed: Int) {
 *   $composer.startRestartGroup()
 *   // ...
 *   f(x)
 *   $composer.endRestartGroup()?.updateScope { next -> A(x, next, $changed or 0b1) }
 * }
 * ```
 *
 * ⸻
 *
 * ## 소스 정보
 *
 * Android Studio 등과 같은 도구들이 컴포지션을 분석할 수 있도록 하기 위해, 컴포저블 블록 내에서
 * 호출 위치를 나타내는 소스 정보를 선택적으로 삽입할 수 있습니다. 모든 함수의 첫 번째 그룹은
 * 해당 호출이 어떤 소스 위치에서 발생했는지를 나타내도록 마킹됩니다. 이를 통해 상위 그룹에서
 * 호출자의 소스 위치를 추적할 수 있습니다.
 */
class ComposableFunctionBodyTransformer(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  private val collectSourceInformation: Boolean,
  private val traceMarkersEnabled: Boolean,
  featureFlags: FeatureFlags,
) :
  AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags),
  FileLoweringPass,
  ModuleLoweringPass {

  private val inlineLambdaInfo = ComposeInlineLambdaLocator(context)

  override fun lower(irModule: IrModuleFragment) {
    inlineLambdaInfo.scan(irModule)
    irModule.transformChildrenVoid(this)
    applySourceInfoFixups()
    irModule.patchDeclarationParents()
  }

  override fun lower(irFile: IrFile) {
    irFile.transformChildrenVoid(this)
    applySourceInfoFixups()
  }

  // Skips the composer to the end of the current group. This generated by the compiler
  // to when the body of a Composable function can be skipped typically because the
  // parameters to the function are equal to the values passed to it in the previous composition.
  //
  // 현재 그룹의 끝까지 컴포저를 건너뜁니다. 컴포저블 함수의 파라미터가 이전 컴포지션에서
  // 전달된 값과 동일하여 해당 함수 본문을 건너뛸 수 있을 때 사용됩니다.
  private val skipToGroupEndFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name.identifier == "skipToGroupEnd" && it.valueParameters.size == 0
    }
  }

  // default 그룹을 시작하는 함수
  private val startDefaultsFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name.identifier == "startDefaults" && it.valueParameters.size == 0
    }
  }

  // Called at the end of defaults group.
  private val endDefaultsFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name.identifier == "endDefaults" && it.valueParameters.size == 0
    }
  }

  /**
   * Start a movable group. A movable group is one that can be moved based on the value of
   * [dataKey] which is typically supplied by the [key][androidx.compose.runtime.key] pseudo
   * compiler function.
   *
   * A movable group implements the semantics of [key][androidx.compose.runtime.key] which allows
   * the state and nodes generated by a loop to move with the composition implied by the key
   * passed to [key][androidx.compose.runtime.key].
   *
   * @param key a compiler generated key based on the source location of the call.
   * @param dataKey an additional object that is used as a second part of the key. This key
   *  produced from the `keys` parameter supplied to the [key][androidx.compose.runtime.key]
   *  pseudo compiler function.
   */
  /**
   * 이동 가능한 그룹을 시작합니다. 이동 가능한 그룹은 [dataKey]의 값에 따라 이동할 수 있으며,
   * 이 값은 일반적으로 [key][androidx.compose.runtime.key] 의사 컴파일러 함수에 의해 제공됩니다.
   *
   * 이동 가능한 그룹은 [key][androidx.compose.runtime.key]의 의미를 구현하며, 루프에 의해 생성된
   * 상태와 노드가 [key][androidx.compose.runtime.key]에 전달된 키에 의해 암시된 컴포지션과 함께
   * 이동할 수 있도록 합니다.
   *
   * @param key 호출의 소스 위치를 기반으로 컴파일러가 생성한 키입니다.
   * @param dataKey 키의 두 번째 부분으로 사용되는 추가 객체입니다. 이 키는 [key][androidx.compose.runtime.key]
   *  의사 컴파일러 함수에 제공된 keys 매개변수에서 생성됩니다.
   */
  // fun startMovableGroup(key: Int, dataKey: Any?)
  private val startMovableFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name.identifier == "startMovableGroup" && it.valueParameters.size == 2
    }
  }

  // Called at the end of a movable group.
  private val endMovableFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name.identifier == "endMovableGroup" && it.valueParameters.size == 0
    }
  }

  /**
   * Called to record a group for a [Composable] function and starts a group that can be
   * recomposed on demand based on the lambda passed to [updateScope][ScopeUpdateScope.updateScope]
   * when [endRestartGroup] is called.
   *
   * @param key A compiler generated key based on the source location of the call.
   * @return the instance of the composer to use for the rest of the function.
   */
  /**
   * [Composable] 함수에 대한 그룹을 기록하기 위해 호출되며, [endRestartGroup]이 호출될 때
   * [updateScope][ScopeUpdateScope.updateScope]에 전달된 람다를 기반으로 필요할 때 다시
   * 컴포즈될 수 있는 그룹을 시작합니다.
   *
   * @param key 호출의 소스 위치를 기반으로 컴파일러가 생성한 키입니다.
   * @return 함수의 나머지 부분에서 사용할 Composer 인스턴스입니다.
   */
  // fun startRestartGroup(key: Int): Composer
  private val startRestartGroupFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name == ComposeNames.START_RESTART_GROUP && it.valueParameters.size == 1
    }
  }

  /**
   * Return a marker for the current group that can be used in a call to [endToMarker].
   *
   * 현재 그룹에 대한 마커를 반환하며, 이 마커는 [endToMarker] 호출에서 사용할 수 있습니다.
   */
  // val currentMarker: Int
  private val currentMarkerProperty: IrProperty? by guardedLazy {
    composerIrClass.properties.firstOrNull {
      it.name == ComposeNames.CURRENT_MARKER
    }
  }

  /**
   * Ends all the groups up to but not including the group that is the parent group when
   * [currentMarker] was called to produce [marker]. All groups ended must have been started with
   * either [startReplaceableGroup] or [startMovableGroup]. Ending other groups can cause the
   * state of the composer to become inconsistent.
   *
   * [currentMarker]가 호출되어 [marker]를 생성했을 때의 부모 그룹에 해당하는 그룹은 제외하고,
   * 그 이전까지의 모든 그룹을 종료합니다. 종료되는 모든 그룹은 반드시 [startReplaceableGroup]
   * 또는 [startMovableGroup]으로 시작된 그룹이어야 합니다. 다른 그룹을 종료하면 컴포저의 상태가
   * 일관되지 않게 될 수 있습니다.
   */
  // fun endToMarker(marker: Int)
  //
  // 인자로 넣을 marker 값 구하는 로직의 설명:
  //   Return the index of the nearest group that contains currentGroup.
  //   현재 그룹을 포함하는 가장 가까운 그룹의 인덱스를 반환합니다.
  private val endToMarkerFunction: IrSimpleFunction? by guardedLazy {
    composerIrClass.functions.firstOrNull {
      it.name == ComposeNames.END_TO_MARKER && it.valueParameters.size == 1
    }
  }

  private val rollbackGroupMarkerEnabled: Boolean
    get() = currentMarkerProperty != null && endToMarkerFunction != null

  /**
   * End a restart group. If the recompose scope was marked used during composition then a
   * [ScopeUpdateScope] is returned that allows attaching a lambda that will produce the same
   * composition as was produced by this group (including calling [startRestartGroup] and
   * [endRestartGroup]).
   *
   * 재시작 그룹을 종료합니다. 컴포지션 중에 RecomposeScope가 사용된 것으로 표시되었다면,
   * [ScopeUpdateScope]가 반환되며 이를 통해 람다를 연결할 수 있습니다. 이 람다는 해당 그룹에서
   * 생성된 것과 동일한 컴포지션을 생성하며, 여기에는 [startRestartGroup]과 [endRestartGroup]
   * 호출도 포함됩니다. (=> 자기 자신을 다시 재귀호출하는 걸로 구현됨)
   */
  private val endRestartGroupFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name == ComposeNames.END_RESTART_GROUP && it.valueParameters.size == 0
    }
  }

  /**
   * Generated by the compile to determine if the composable function should be executed. It may
   * not execute if parameter has not changed and the nothing else is forcing the function to
   * execute (such as its scope was invalidated or a static composition local it was changed) or
   * the composition is pausable and the composition is pausing.
   *
   * @param parametersChanged `true` if the parameters to the composable function have changed.
   *   This is also `true` if the composition is [inserting] or if content is being reused.
   *
   * @param flags The `$changed` parameter that contains the forced recompose bit to allow the
   *   composer to disambiguate when the parameters changed due the execution being forced or if
   *   the parameters actually changed. This is only ambiguous in a [PausableComposition] and is
   *   necessary to determine if the function can be paused. The bits, other than 0, are reserved
   *   for future use (which would required the bit 31, which is unused in `$changed` values, to
   *   be set to indicate that the flags carry additional information). Passing the `$changed`
   *   flags directly, instead of masking the 0 bit, is more efficient as it allows less code to
   *   be generated per call to `shouldExecute` which is every called in every restartable
   *   function, as well as allowing for the API to be extended without a breaking changed.
   */
  /**
   * 컴파일러에 의해 생성되어, 컴포저블 함수가 실행되어야 하는지를 결정합니다. 매개변수가 변경되지
   * 않았고 함수 실행을 강제하는 다른 요인(예: 스코프가 무효화되었거나 정적 CompositionLocal이 변경된
   * 경우)이 없거나, 컴포지션이 일시 중지 가능하고 현재 일시 중지 중인 경우에는 실행되지 않을 수
   * 있습니다.
   *
   * @param parametersChanged 컴포저블 함수의 매개변수가 변경된 경우 true입니다. 컴포지션이
   *  [inserting] 상태이거나 콘텐츠가 재사용되는 경우에도 true가 됩니다.
   *
   * @param flags $changed 매개변수로, 강제 리컴포지션 비트를 포함하여 매개변수 변경이 강제 실행
   *  때문인지 실제 변경 때문인지 Composer가 구분할 수 있도록 합니다. 이 모호성은 [PausableComposition]에서만
   *  발생하며, 함수가 일시 중지될 수 있는지를 결정하는 데 필요합니다. 0 이외의 비트는 향후 사용을
   *  위해 예약되어 있으며, 그 경우 $changed 값에서 사용되지 않는 31번째 비트를 설정해 플래그에
   *  추가 정보가 포함됨을 나타내야 합니다. $changed 플래그를 직접 전달하는 것이 더 효율적인데,
   *  이는 shouldExecute가 모든 재시작 가능한 함수에서 호출될 때마다 생성되는 코드 양을 줄일 수
   *  있고, API가 변경 없이 확장될 수 있도록 하기 때문입니다.
   */
  // fun shouldExecute(parametersChanged: Boolean, flags: Int): Boolean
  private val shouldExecuteFunction by guardedLazy {
    if (FeatureFlag.PausableComposition.enabled) {
      composerIrClass.functions.firstOrNull {
        it.name == ComposeNames.SHOULD_EXECUTE &&
          it.valueParameters.size == 2 &&
          it.valueParameters[0].type.isBoolean() &&
          it.valueParameters[1].type.isInt()
      }
    } else {
      null
    }
  }

  // PausableComposition 설명
  /**
   * A [PausableComposition] is a sub-composition that can be composed incrementally as it supports
   * being paused and resumed.
   *
   * Pausable sub-composition can be used between frames to prepare a sub-composition before it is
   * required by the main composition. For example, this is used in lazy lists to prepare list items
   * in between frames to that are likely to be scrolled in. The composition is paused when the start
   * of the next frame is near allowing composition to be spread across multiple frames without
   * delaying the production of the next frame.
   *
   * The result of the composition should not be used (e.g. the nodes should not added to a layout
   * tree or placed in layout) until [PausedComposition.isComplete] is `true` and
   * [PausedComposition.apply] has been called. The composition is incomplete and will not
   * automatically recompose until after [PausedComposition.apply] is called.
   *
   * A [PausableComposition] is a [ReusableComposition] but [setPausableContent] should be used
   * instead of [ReusableComposition.setContentWithReuse] to create a paused composition.
   *
   * If [Composition.setContent] or [ReusableComposition.setContentWithReuse] are used then the
   * composition behaves as if it wasn't pausable. If there is a [PausedComposition] that has not yet
   * been applied, an exception is thrown.
   */
  /**
   * [PausableComposition]은 일시 중지 및 재개를 지원하여 점진적으로 composed될 수 있는 서브 컴포지션입니다.
   *
   * 일시 중지 가능한 서브 컴포지션은 메인 컴포지션에서 필요해지기 전에 프레임 사이에서 준비하는 데 사용할
   * 수 있습니다. 예를 들어, Lazy 리스트에서 스크롤될 가능성이 있는 리스트 아이템을 프레임 사이에서 미리
   * 준비하는 데 사용됩니다. 컴포지션은 다음 프레임 시작이 가까워졌을 때 일시 중지되며, 이를 통해 다음
   * 프레임 생성 지연 없이 여러 프레임에 걸쳐 컴포지션을 분산시킬 수 있습니다.
   *
   * 컴포지션의 결과는 [PausedComposition.isComplete]가 true이고 [PausedComposition.apply]가 호출되기 전까지
   * 사용되어서는 안 됩니다. (예: 노드를 레이아웃 트리에 추가하거나 레이아웃에 배치해서는 안 됩니다.)
   * 이 시점까지는 컴포지션이 불완전하며, [PausedComposition.apply]가 호출되기 전까지 자동으로 리컴포즈되지
   * 않습니다.
   *
   * [PausableComposition]은 [ReusableComposition]이지만, 일시 중지된 컴포지션을 생성하기 위해서는
   * [ReusableComposition.setContentWithReuse] 대신 [setPausableContent]를 사용해야 합니다.
   *
   * [Composition.setContent]나 [ReusableComposition.setContentWithReuse]를 사용할 경우 컴포지션은 일시 중지
   * 불가능한 것처럼 동작합니다. 아직 적용되지 않은 [PausedComposition]이 존재한다면 예외가 발생합니다.
   */
  // STUDY PausableComposition가 어디에 쓰이는 거지???

  /**
   * A compiler plugin utility function to change $changed flags from Different(10) to Same(01) for
   * when captured by restart lambdas. All parameters are passed with the same value as it was
   * previously invoked with and the changed flags should reflect that.
   *
   * 컴파일러 플러그인 유틸리티 함수로, restart lambda에 의해 캡처될 때 $changed 플래그를
   * Different(0b010)에서 Same(0b001)으로 변경합니다. 모든 매개변수는 이전에 호출되었을 때와
   * 동일한 값으로 전달되며, $changed 플래그는 이를 반영해야 합니다.
   */
  // updateScope()로 전달되는 람다는 self-invoke로 구현되고, 이 self-invoke에 사용되는 인자들은
  // 자기자신의 매개변수 값을 그대로 사용함. 즉, 모든 슬롯의 $changed를 Same으로 바꾸어야 함.
  //
  // fun updateChangedFlags(flags: Int): Int
  private val updateChangedFlagsFunction: IrSimpleFunction? by guardedLazy {
    getTopLevelFunctionOrNull(ComposeCallableIds.updateChangedFlags)?.let {
      val owner = it.owner
      if (owner.valueParameters.size == 1) owner else null
    }
  }

  /**
   * Records source information that can be used for tooling to determine the source location of the
   * corresponding composable function. By default, this function is declared as having no
   * side-effects. It is safe for code shrinking tools (such as R8 or ProGuard) to remove it.
   *
   * 해당 컴포저블 함수의 소스 위치를 확인하기 위해 툴링에서 사용할 수 있는 소스 정보를 기록합니다.
   * 기본적으로 이 함수는 부작용이 없는 것으로 선언됩니다. R8이나 ProGuard 같은 코드 축소 도구가
   * 이 함수를 제거해도 안전합니다.
   */
  // fun sourceInformation(composer: Composer, sourceInformation: String)
  private val sourceInformationFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.sourceInformation).owner
  }

  /**
   * Record a source information marker. This marker can be used in place of a group that would
   * have contained the information but was elided as the compiler plugin determined the group was
   * not necessary such as when a function is marked with [ReadOnlyComposable].
   *
   * @param key A compiler generated key based on the source location of the call.
   * @param sourceInformation An string value to that provides the compose tools enough
   *   information to calculate the source location of calls to composable functions.
   */
  /**
   * 소스 정보 마커를 기록합니다. 이 마커는 원래 정보를 포함했을 그룹이 컴파일러 플러그인에 의해
   * 불필요하다고 판단되어 생략된 경우(예: 함수가 [ReadOnlyComposable]로 표시된 경우) 그룹 대신
   * 사용될 수 있습니다.
   *
   * @param key 호출의 소스 위치를 기반으로 컴파일러가 생성한 키입니다.
   * @param sourceInformation 컴포즈 도구가 컴포저블 함수 호출의 소스 위치를 계산할 수 있을
   * 만큼의 정보를 제공하는 문자열 값입니다.
   */
  // fun sourceInformationMarkerStart(composer: Composer, key: Int, sourceInformation: String)
  private val sourceInformationMarkerStartFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.sourceInformationMarkerStart).owner
  }

  /**
   * Should be called without thread synchronization with occasional information loss.
   *
   * 스레드 동기화 없이 호출되어야 하며, 가끔 정보 손실이 발생할 수 있습니다.
   */
  // fun isTraceInProgress(): Boolean
  private val isTraceInProgressFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.isTraceInProgress)
      .singleOrNull { it.owner.valueParameters.isEmpty() }
      ?.owner
  }

  /**
   * Should be called without thread synchronization with occasional information loss.
   *
   * @param key is a group key generated by the compiler plugin for the function being traced. This
   *   key is unique the function.
   * @param dirty1 $dirty metadata: forced-recomposition and function parameters 1..10 if present
   * @param dirty2 $dirty2 metadata: forced-recomposition and function parameters 11..20 if present
   * @param info is a user displayable string that describes the function for which this is the start
   *   event.
   */
  /**
   * 스레드 동기화 없이 호출되어야 하며, 가끔 정보 손실이 발생할 수 있습니다.
   *
   * @param key 추적되는 함수에 대해 컴파일러 플러그인이 생성한 그룹 키입니다. 이 키는 해당 함수에
   *  대해 고유합니다.
   * @param dirty1 $dirty 메타데이터로, 강제 리컴포지션 여부와 함수 매개변수 1..10이 존재할 경우
   *  그 정보를 포함합니다.
   * @param dirty2 $dirty2 메타데이터로, 강제 리컴포지션 여부와 함수 매개변수 11..20이 존재할 경우
   *  그 정보를 포함합니다.
   * @param info 시작 이벤트에 해당하는 함수를 설명하는 사용자 표시용 문자열입니다.
   */
  // fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String)
  private val traceEventStartFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.traceEventStart)
      .singleOrNull {
        it.owner.valueParameters.map { p -> p.type } ==
          listOf(
            context.irBuiltIns.intType,
            context.irBuiltIns.intType,
            context.irBuiltIns.intType,
            context.irBuiltIns.stringType,
          )
      }
      ?.owner
  }


  /**
   * Should be called without thread synchronization with occasional information loss.
   *
   * 스레드 동기화 없이 호출되어야 하며, 가끔 정보 손실이 발생할 수 있습니다.
   */
  // fun traceEventEnd()
  private val traceEventEndFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.traceEventEnd)
      .singleOrNull { it.owner.valueParameters.isEmpty() }
      ?.owner
  }

  /**
   * Record the end of the marked source information range.
   *
   * 마킹된 소스 정보 범위의 끝을 기록합니다.
   */
  // fun sourceInformationMarkerEnd(composer: Composer)
  private val sourceInformationMarkerEndFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.sourceInformationMarkerEnd).owner
  }

  private val traceEventMarkersEnabled: Boolean
    get() = traceMarkersEnabled && traceEventEndFunction != null

  // fun rememberComposableLambda(key: Int, tracked: Boolean, block: Any): ComposableLambda =
  //   remember { ComposableLambdaImpl(key, tracked, block) }.also { it.update(block) }
  private val rememberComposableLambdaFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.rememberComposableLambda).singleOrNull()
  }

  // ComposableLambda 주석
  /**
   * A Restart is created to hold composable lambdas to track when they are invoked allowing the
   * invocations to be invalidated when a new composable lambda is created during composition.
   *
   * This allows much of the call-graph to be skipped when a composable function is passed through
   * multiple levels of composable functions.
   */
  /**
   * Restart는 컴포저블 람다를 보관하여, 해당 람다가 호출될 때 이를 추적하고, 컴포지션 중에 새로운
   * 컴포저블 람다가 생성되면 기존 호출을 무효화할 수 있도록 합니다.
   *
   * 이를 통해 컴포저블 함수가 여러 단계의 컴포저블 함수를 거쳐 전달되더라도 호출 그래프의 많은
   * 부분을 건너뛸 수 있습니다.
   */

  private val useNonSkippingGroupOptimization by guardedLazy {
    // Uses `rememberComposableLambda` as a indication that the runtime supports generating
    // remember after call as it was added at the same time as the slot table was modified
    // to support remember after call.
    //
    // rememberComposableLambda가 있다면 런타임이 호출 이후에 remember 생성을 지원한다는
    // 표시로 사용합니다. 이는 호출 이후 remember를 지원하도록 슬롯 테이블이 수정된 것과
    // 동시에 추가되었기 때문입니다.
    FeatureFlag.OptimizeNonSkippingGroups.enabled && rememberComposableLambdaFunction != null
  }

  private val IrType.typeArguments: List<IrTypeArgument>
    get() = (this as? IrSimpleType)?.arguments.orEmpty()

  /**
   * Update [block]. The scope is returned by [Composer.endRestartGroup] when [used] is true and
   * implements [ScopeUpdateScope].
   *
   * [block]을 업데이트합니다. 이 스코프는 [used]가 true일 때 [Composer.endRestartGroup]에 의해
   * 반환되며, [ScopeUpdateScope]를 구현합니다.
   */
  // fun updateScope(block: (Composer, Int) -> Unit)
  private val updateScopeFunction: IrSimpleFunction by guardedLazy {
    endRestartGroupFunction.returnType.classOrNull
      ?.owner
      ?.functions
      ?.singleOrNull {
        it.name == ComposeNames.UPDATE_SCOPE &&
          it.valueParameters.first().type.typeArguments.size == 3
      }
      ?: error("new updateScope not found in result type of endRestartGroup")
  }

  /**
   * Reflects whether the [Composable] function can skip. Even if a [Composable] function is
   * called with the same parameters it might still need to run because, for example,
   * a new value was provided for a [CompositionLocal] created by [staticCompositionLocalOf].
   *
   * [Composable] 함수가 건너뛸 수 있는지를 나타냅니다. 동일한 매개변수로 [Composable] 함수가
   * 호출되더라도, 예를 들어 [staticCompositionLocalOf]로 생성된 [CompositionLocal]에 새로운
   * 값이 제공된 경우에는 여전히 실행되어야 할 수 있습니다.
   */
  // val skipping: Boolean
  private val isSkippingProperty by guardedLazy {
    composerIrClass.properties.first {
      it.name.asString() == "skipping"
    }
  }

  /**
   * Reflects whether the default parameter block of a [Composable] function is valid. This is
   * `false` if a [State] object read in the [startDefaults] group was modified since the last
   * time the [Composable] function was run.
   *
   * [Composable] 함수의 기본 매개변수 블록이 유효한지를 나타냅니다. [startDefaults] 그룹에서
   * 읽은 [State] 객체가 마지막으로 [Composable] 함수가 실행된 이후 수정되었다면, 이 값은 false가
   * 됩니다. (true 아닌가???)
   */
  // val defaultsInvalid: Boolean
  private val defaultsInvalidProperty by guardedLazy {
    composerIrClass.properties.first {
      it.name.asString() == "defaultsInvalid"
    }
  }

  /**
   * Produce an object that will compare equal an iff [left] and [right] compare equal to some
   * [left] and [right] of a previous call to [joinKey]. This is used by [key] to handle multiple
   * parameters. Since the previous composition stored [left] and [right] in a "join key" object
   * this call is used to return the previous value without an allocation instead of blindly
   * creating a new value that will be immediately discarded.
   *
   * @param left the first part of a a joined key.
   * @param right the second part of a joined key.
   *
   * @return an object that will compare equal to a value previously returned by [joinKey] if
   *   [left] and [right] compare equal to the [left] and [right] passed to the previous call.
   */
  /**
   * [left]와 [right]가 이전에 [joinKey]에 전달된 [left], [right]와 동일하게 비교될 경우에만
   * 동일하다고 비교되는 객체를 생성합니다. 이는 [key]가 여러 매개변수를 처리할 때 사용됩니다.
   * 이전 컴포지션에서는 [left]와 [right]가 "join key" 객체에 저장되었기 때문에, 이 호출은
   * 새 값을 무작정 생성해 바로 폐기하는 대신 이전 값을 할당 없이 반환하는 데 사용됩니다.
   *
   * @param left 결합된 키의 첫 번째 부분입니다.
   * @param right 결합된 키의 두 번째 부분입니다.
   *
   * @return [left]와 [right]가 이전 호출에 전달된 [left], [right]와 동일하게 비교될 경우,
   *  이전에 [joinKey]가 반환했던 값과 동일하게 비교되는 객체입니다.
   */
  // fun joinKey(left: Any?, right: Any?): Any
  private val joinKeyFunction by guardedLazy {
    composerIrClass.functions.first {
      it.name == ComposeNames.JOIN_KEY && it.valueParameters.size == 2
    }
  }

  private var currentScope: Scope = Scope.RootScope()

  private fun printScopeStack(): String =
    buildString {
      currentScope.forEach { scope ->
        appendLine(scope.name)
      }
    }

  private val isInComposableScope: Boolean
    get() = currentScope.isInComposable

  private val currentFunctionScope: Scope.FunctionScope
    get() = currentScope.functionScope
      ?: error("Expected a FunctionScope but none exist.\n${printScopeStack()}")

  private val (Scope.BlockScope).hasSourceInformation: Boolean
    get() = hasSourceInformation(collectSourceInformation)

  private val (Scope.BlockScope).sourceInformation: String?
    get() = calculateSourceInfo(collectSourceInformation)

  private val sourceInfoFixups = mutableListOf<SourceInfoFixup>()

  override fun visitFile(declaration: IrFile): IrFile =
    includeFileNameInExceptionTrace(declaration) {
      inScope(Scope.FileScope(declaration)) {
        super.visitFile(declaration)
      }
    }

  override fun visitClass(declaration: IrClass): IrStatement {
    if (declaration.isComposableSingletonClass()) {
      return declaration
    }
    return inScope(Scope.ClassScope(declaration.name)) {
      super.visitDeclaration(declaration)
    }
  }

  override fun visitBlock(expression: IrBlock): IrExpression =
    when (expression.origin) {
      IrStatementOrigin.FOR_LOOP -> {
        // The psi2ir phase will turn for loops into a block, so:
        //
        //   for (loopVar in <someIterable>)
        //
        // gets transformed into
        //
        //   // #1: The "header"
        //   val it = <someIterable>.iterator()
        //
        //   // #2: The inner while loop
        //   while (it.hasNext()) {
        //     val loopVar = it.next()
        //     // Loop body
        //   }
        //
        // Additionally, the IR lowering phase will take this block and optimize it
        // for some shapes of for loops. What we want to do is keep this original
        // shape in tact so that we don't ruin some of these optimizations.
        //
        //
        // psi2ir 단계에서는 for 루프를 블록으로 변환합니다.
        //
        //    for (loopVar in <someIterable>)
        //
        // 따라서 위 코드는 다음과 같이 변환됩니다:
        //
        //    // #1: "헤더" 부분:
        //    val it = <someIterable>.iterator()
        //
        //    // #2: 내부 while 루프:
        //    while (it.hasNext()) {
        //      val loopVar = it.next()
        //      // 루프 본문
        //    }
        //
        // 또한 IR lowering 단계에서는 이 블록을 특정 형태의 for 루프에 대해 최적화합니다.
        // 우리는 이러한 최적화를 방해하지 않기 위해 이 원래 구조를 그대로 유지하고자 합니다.
        val statements = expression.statements

        require(statements.size == 2) { "Expected 2 statements in for-loop block" }

        val oldVar = statements[0] as IrVariable

        require(oldVar.origin == IrDeclarationOrigin.FOR_LOOP_ITERATOR) {
          "Expected FOR_LOOP_ITERATOR origin for iterator variable"
        }

        val newVar = oldVar.transform(this, null) as IrVariable
        val oldLoop = statements[1] as IrWhileLoop

        require(oldLoop.origin == IrStatementOrigin.FOR_LOOP_INNER_WHILE) {
          "Expected FOR_LOOP_INNER_WHILE origin for while loop"
        }

        val newLoop = oldLoop.transform(this, null)

        if (newVar == oldVar && newLoop == oldLoop) {
          expression
        } else if (newLoop is IrBlock) {
          require(newLoop.statements.size == 3) { "newLoop.statements.size != 3" }

          val before = newLoop.statements[0] as IrContainerExpression
          val loop = newLoop.statements[1] as IrWhileLoop
          val after = newLoop.statements[2] as IrContainerExpression

          val result = mutableStatementContainer()
          result.statements.addAll(
            listOf(
              before,
              irBlock(
                type = expression.type,
                origin = IrStatementOrigin.FOR_LOOP,
                statements = listOf(newVar, loop),
              ),
              after,
            )
          )
          result
        } else {
          error("Expected transformed loop to be an IrBlock")
        }
      }

      IrStatementOrigin.FOR_LOOP_INNER_WHILE -> {
        super.visitBlock(expression)
      }

      else -> super.visitBlock(expression)
    }

  override fun visitCall(expression: IrCall): IrExpression {
    val getterCall = expression.associatedComposableSingletonStub
    if (getterCall != null) {
      // This call has an associated stub in ComposableSingletons class. This stub is not
      // directly reachable by any code in this module, but might be used by other external libraries.
      // Transform it the same way as the one above.
      //
      // 이 호출은 ComposableSingletons 클래스에 연결된 스텁을 가지고 있습니다. 이 스텁은
      // 현재 모듈의 코드에서는 직접 접근할 수 없지만, 외부 라이브러리에서 사용될 수 있습니다.
      // 위에서와 동일한 방식으로 변환해야 합니다.
      val property = getterCall.symbol.owner.correspondingPropertySymbol?.owner
      property?.transformChildrenVoid()
    }

    if (expression.isComposableCall() || expression.isSyntheticComposableCall()) {
      return visitComposableCall(expression)
    }

    when {
      expression.symbol.owner.isInline -> {
        val captureScope = Scope.CaptureScope()

        withScope(Scope.CallScope(expression, this)) {
          expression.arguments.fastForEachIndexed { index, arg ->
            val parameter = expression.symbol.owner.parameters[index]
            val transformed = if (parameter.isInlineLambda()) {
              // if it is not a composable call but it is an inline function, then we allow
              // composable calls to happen inside of the inlined lambdas. This means that we have
              // some control flow analysis to handle there as well. We wrap the call in a
              // CaptureScope and coalescable group if the call has any composable invocations
              // inside of it.
              //
              // composable 호출은 아니지만 inline 함수인 경우, inline된 람다 내부에서 composable 호출이
              // 발생할 수 있도록 허용합니다. 따라서 이 경우에도 제어 흐름 분석을 처리해야 합니다.
              // 호출 내부에 composable 호출이 포함되어 있다면, 해당 호출을 CaptureScope와 병합 가능한
              // 그룹으로 래핑합니다.
              inScope(captureScope) { arg?.transform(this, null) }
            } else {
              arg?.transform(this, null)
            }

            expression.arguments[index] = transformed
          }
        }

        return if (captureScope.hasCapturedComposableCall) {
          captureScope.shouldRealizeCoalescableChildren()

          // argument 처리 로직을 CoalescableGroup으로 묶음
          expression.wrapWithCoalescableGroup(scope = captureScope)
        } else {
          expression
        }
      }

      expression.isComposableSingletonGetter() -> {
        // This looks like `ComposableSingletonClass.lambda-123`, which is a static/saved
        // call of composableLambdaInstance. We want to transform the property here now
        // so the assumptions about the invocation order assumed by source locations is
        // preserved.
        //
        // 이 코드는 ComposableSingletonClass.lambda-123처럼 보이며, 이는 composableLambdaInstance의
        // 정적/저장된 호출입니다. 소스 위치에서 호출 순서에 대한 가정을 유지하기 위해,
        // 이 속성을 지금 변환해야 합니다.
        val property = expression.symbol.owner.correspondingPropertySymbol?.owner
        property?.transformChildrenVoid()

        return super.visitCall(expression)
      }

      else -> return super.visitCall(expression)
    }
  }

  override fun visitFunction(declaration: IrFunction): IrStatement {
    val scope = Scope.FunctionScope(function = declaration, transformer = this)
    return inScope(scope) {
      visitFunctionInScope(fn = declaration)
    }.also {
      // 현재 함수가 인라인되는 람다이고, 컴포저블 람다는 아니지만, 컴포저블 호출이 있다면
      //   => 컴포저블 캡처가 있음
      if (scope.isInlineLambda && !scope.isComposable && scope.hasComposableCalls) {
        encounteredCapturedComposableCall()
      }

      metrics.recordFunction(function = scope.metrics)
      context.irTrace.record(
        slice = ComposeWritableSlices.FUNCTION_METRICS,
        key = declaration,
        value = scope.metrics,
      )
    }
  }

  override fun visitProperty(declaration: IrProperty): IrStatement =
    inScope(Scope.PropertyScope(declaration.name)) {
      super.visitProperty(declaration)
    }

  override fun visitField(declaration: IrField): IrStatement =
    inScope(Scope.FieldScope(declaration.name)) {
      super.visitField(declaration)
    }

  // MEMO 모든 정의부에 컴포저블 대응하고 있는지 검사하는 용도인 듯?
  override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement =
    when (declaration) {
      is IrField,
      is IrProperty,
      is IrFunction,
      is IrClass,
        -> {
        // these declarations get scopes, but they are handled individually.
        // 이 선언들은 스코프를 가지지만, 각각 개별적으로 처리됩니다.
        super.visitDeclaration(declaration)
      }

      is IrTypeAlias,
      is IrEnumEntry,
      is IrAnonymousInitializer,
      is IrTypeParameter,
      is IrLocalDelegatedProperty,
      is IrValueDeclaration,
      is IrScript,
        -> {
        // these declarations do not create new "scopes", so we do nothing.
        // 이러한 선언들은 새로운 "스코프"를 생성하지 않으므로 아무 작업도 하지 않습니다.
        super.visitDeclaration(declaration)
      }

      else -> error("Unhandled declaration! ${declaration::class.java.simpleName}")
    }

  // 매개변수의 사용 여부 검사. 그냥 각 매개변수별로 IrGetValue 연산 여부만 검사함.
  override fun visitGetValue(expression: IrGetValue): IrExpression {
    val declaration = expression.symbol.owner
    var scope: Scope? = currentScope

    if (declaration is IrValueParameter) {
      val fn = declaration.parent

      while (scope != null) {
        if (scope is Scope.FunctionScope) {
          if (scope.function == fn) {
            val index = scope.trackedParameters.indexOf(declaration)
            if (index != -1) scope.usedParams[index] = true
            return expression
          }
        }

        scope = scope.parent
      }
    }

    return expression
  }

  override fun visitReturn(expression: IrReturn): IrExpression {
    if (!isInComposableScope) return super.visitReturn(expression)

    val scope = Scope.ReturnScope(expression)
    withScope(scope) {
      expression.transformChildrenVoid()
    }

    val endBlock = mutableStatementContainer()
    encounteredReturn(
      symbol = expression.returnTargetSymbol,
      extraEndLocation = { endExpr -> endBlock.statements.add(endExpr) },
    )

    return if (!scope.hasComposableCalls && expression.value.type.isUnitOrNullableUnit()) {
      // return에 컴포저블 호출이 없고, Unit[?] 타입인 경우
      expression.wrap(before = listOf(endBlock))
    } else {
      val tempVar = irTemporary(value = expression.value, nameHint = "return")
      tempVar.wrap(
        startOffset = expression.startOffset,
        endOffset = expression.endOffset,
        type = expression.type,
        after = listOf(
          endBlock,
          IrReturnImpl(
            startOffset = expression.startOffset,
            endOffset = expression.endOffset,
            type = expression.type,
            returnTargetSymbol = expression.returnTargetSymbol,
            value = irGet(tempVar),
          ),
        ),
      )
    }
  }

  override fun visitWhileLoop(loop: IrWhileLoop): IrExpression {
    if (!isInComposableScope) return super.visitWhileLoop(loop)
    return handleLoop(loop)
  }

  override fun visitDoWhileLoop(loop: IrDoWhileLoop): IrExpression {
    if (!isInComposableScope) return super.visitDoWhileLoop(loop)
    return handleLoop(loop)
  }

  override fun visitBreakContinue(jump: IrBreakContinue): IrExpression {
    if (!isInComposableScope) return super.visitBreakContinue(jump)

    val endBlock = mutableStatementContainer()
    encounteredJump(
      jump = jump,
      extraEndLocation = { endExpr -> endBlock.statements.add(endExpr) },
    )

    return jump.wrap(before = listOf(endBlock))
  }

  // STUDY 전체 뭉탱이가 이해 안댐!!!
  override fun visitWhen(expression: IrWhen): IrExpression {
    if (!isInComposableScope) return super.visitWhen(expression)
    if (currentFunctionScope.function.hasExplicitGroupsAnnotation) return super.visitWhen(expression)

    val optimizeNonSkippingGroups = FeatureFlag.OptimizeNonSkippingGroups.enabled

    // Composable calls in conditions are more expensive than composable calls in the different
    // result branches of the when clause. This is because if we have N branches of a when
    // clause, we will always execute exactly 1 result branch, but we will execute 0-N of the
    // conditions. This means that if only the results have composable calls, we can use
    // replace groups to represent the entire expression. If a condition has a composable
    // call in it, we need to place the whole expression in a Container group, since a variable
    // number of them will be created. The exception here is the first branch's condition,
    // since it will *always* be executed. As a result, if only the first conditional has a
    // composable call in it, we can avoid creating a group for it since it is not
    // conditionally executed.
    //
    // 조건절에 있는 Composable 호출은 when 절의 결과 분기(branch)에 있는 Composable 호출보다 비용이
    // 더 많이 듭니다. 그 이유는 when 절에 N개의 분기가 있을 경우, 결과 분기 중 정확히 1개만 실행되지만,
    // 조건절(condition)은 0~N개가 실행될 수 있기 때문입니다. 즉, 결과에만 Composable 호출이 있다면
    // replace group을 사용하여 전체 표현식을 표현할 수 있습니다. 하지만 조건절에 Composable 호출이 있다면,
    // 조건의 수가 가변적이므로 전체 표현식을 Container group으로 감싸야 합니다. 예외는 첫 번째 분기의
    // 조건절입니다. 이 조건절은 항상 실행되므로, 첫 번째 조건에만 Composable 호출이 있는 경우에는 그룹
    // 생성을 생략할 수 있습니다. 즉, 조건부 실행이 아니므로 별도의 그룹이 필요하지 않습니다.
    var needsWrappingWholeGroup = false
    var resultWithComposableCalls = 0
    var hasElseBranch = false

    val transformed =
      IrWhenImpl(
        startOffset = expression.startOffset,
        endOffset = expression.endOffset,
        type = expression.type,
        origin = expression.origin,
      )

    val conditionScopes = mutableListOf<Scope.BranchScope>()
    val resultScopes = mutableListOf<Scope.BranchScope>()

    val whenScope = withScope(Scope.WhenScope()) {
      expression.branches.fastForEachIndexed { index, branch ->
        if (branch is IrElseBranch) {
          hasElseBranch = true
          val (resultScope, result) = branch.result.transformWithScope(scope = Scope.BranchScope())

          conditionScopes.add(Scope.BranchScope())
          resultScopes.add(resultScope)

          if (resultScope.hasComposableCalls)
            resultWithComposableCalls++

          transformed.branches.add(
            IrElseBranchImpl(
              startOffset = branch.startOffset,
              endOffset = branch.endOffset,
              condition = branch.condition,
              result = result,
            ),
          )
        } else {
          val (conditionScope, condition) = branch.condition.transformWithScope(scope = Scope.BranchScope())
          val (resultScope, result) = branch.result.transformWithScope(scope = Scope.BranchScope())

          conditionScopes.add(conditionScope)
          resultScopes.add(resultScope)

          // the first condition is always executed so if it has a composable call in it,
          // it doesn't necessitate a group. However, non-skipping group optimization is
          // enabled, we need a wrapping group if any conditions have a composable call.
          //
          // 첫 번째 조건은 항상 실행되므로, 해당 조건에 컴포저블 호출이 포함되어 있어도
          // 반드시 그룹이 필요한 것은 아닙니다. (=> 어차피 스킵 불가능한 컴포저블 호출이므로)
          // 하지만 non-skipping 그룹 최적화가 활성화되어 있는 경우, 조건 중 하나라도
          // 컴포저블 호출을 포함하고 있다면 전체를 감싸는 그룹이 필요합니다.
          //
          // MEMO 두 번째 결과 분기부터 컴포저블 호출이 포함되어 있다면 when 전체를 그룹으로
          //  감싸야 함 (non-skipping 그룹 최적화와 무관)
          needsWrappingWholeGroup = needsWrappingWholeGroup || (index >= 1 && conditionScope.hasComposableCalls)

          if (resultScope.hasComposableCalls && !branch.result.isGroupBalanced())
            resultWithComposableCalls++

          transformed.branches.add(
            IrBranchImpl(
              startOffset = branch.startOffset,
              endOffset = branch.endOffset,
              condition = condition,
              result = result,
            ),
          )
        }
      }
    }

    // If we are optimizing the non-skipping functions we always need the
    // same number of groups if any of the results have composable functions
    // and it needs to be the same number even if only one branch requires a
    // group.
    //
    // non-skipping 함수 최적화를 수행하는 경우, 결과 중 하나라도 컴포저블 함수를
    // 포함하고 있다면 항상 동일한 개수의 그룹이 필요합니다. 그리고 단 하나의 분기만
    // 그룹이 필요한 경우라도 동일한 개수의 그룹을 유지해야 합니다.
    val needsResultGroups =
      if (optimizeNonSkippingGroups) {
        resultWithComposableCalls > 0
      } else {
        // 두 개 이상의 결과 분기가 컴포저블 호출을 포함하고,
        // when 블록 전체를 그룹하지 않아도 된다면
        resultWithComposableCalls > 1 && !needsWrappingWholeGroup
      }

    // If we are putting groups around the result branches, we need to guarantee that exactly
    // one result branch is executed. We do this by adding an else branch if it there is not
    // one already. Note that we only need to do this if we aren't going to wrap the if
    // statement in a group entirely, which we will do if the conditions have calls in them.
    //
    // 결과 분기들에 그룹을 둘 경우, 반드시 정확히 하나의 결과 분기만 실행되도록 보장해야 합니다.
    // (한 번에 두 개 이상의 결과 분기가 실행될 수 있나??)
    // 이를 위해 else 분기가 없다면 else 분기를 추가합니다. 단, 조건문 전체를 그룹으로 감쌀
    // 예정이라면(즉, 조건절에 composable 호출이 있는 경우), 이 작업은 필요하지 않습니다.

    // NOTE: we might also be able to assume that the when is exhaustive if it has a non-unit
    //  resulting type, since the type system should enforce that.
    //
    // 참고: 반환 타입이 Unit이 아닌 경우에는 타입 시스템이 이를 강제하므로, when 문이
    //  exhaustive하다고 가정할 수도 있습니다.
    if (!hasElseBranch && needsResultGroups) {
      conditionScopes.add(Scope.BranchScope())
      resultScopes.add(Scope.BranchScope())

      transformed.branches.add(
        IrElseBranchImpl(
          startOffset = expression.endOffset,
          endOffset = expression.endOffset,
          condition = irBooleanConst(true),
          result = IrBlockImpl(
            startOffset = expression.endOffset,
            endOffset = expression.endOffset,
            type = context.irBuiltIns.unitType,
            origin = null,
            statements = emptyList(),
          ),
        ),
      )
    }

    forEachWith(transformed.branches, conditionScopes, resultScopes) { branch, conditionScope, resultScope ->
      if (conditionScope.hasComposableCalls) {
        if (needsWrappingWholeGroup && !optimizeNonSkippingGroups) {
          // Generate a group around the conditional block when it has a composable call
          // in it and we are generating a group around when block.
          //
          // 조건 블록에 컴포저블 호출이 있고 when 블록 전체에 그룹을 생성하는 경우,
          // 해당 조건 블록에 그룹을 생성합니다.
          branch.condition = branch.condition.wrapWithReplaceGroup(scope = conditionScope)
        } else {
          // Ensure that the inner structure of condition is correct if the wrapping group
          // is not required by realizing groups in condition scope.
          //
          // when 블록 전체를 그룹으로(wrapping 그룹) 감싸지 않더라도 조건문 내부 구조가
          // 올바르도록 조건 스코프 내에서 그룹을 realize합니다.
          conditionScope.shouldRealizeCoalescableChildren()
          conditionScope.realizeCoalescableChildren()
        }
      }

      // if no wrapping group but more than we need branch groups, we have to have every
      // result be a group so that we have a consistent number of groups during execution.
      //
      // wrapping 그룹은 없지만 결과 분기 그룹이 여러개인 경우, 실행 중 일관된 그룹 수를
      // 유지하기 위해 모든 결과 블록을 그룹으로 만들어야 합니다.
      if (
        needsResultGroups ||
        // if we are wrapping the if with a group, then we only need to add a group when
        // the block has composable calls. The check of the feature flag check here is redundant
        // as needsResultGroups will be true if any result scope has composable calls but it
        // is here redundantly so when this flag is removed this code will be updated.
        //
        // if 문을 그룹으로 감싸는 경우, 해당 블록에 컴포저블 호출이 있을 때만 그룹을 추가하면 됩니다.
        // 여기에서 feature flag를 확인하는 것은 중복되지만, needsResultGroups가 어떤 결과 스코프든
        // 컴포저블 호출이 있으면 true가 되므로 문제가 없습니다. 다만 이 feature flag가 제거될 때
        // 이 코드도 함께 업데이트되어야 하므로 중복 검사는 유지되고 있습니다.
        !optimizeNonSkippingGroups &&
        (needsWrappingWholeGroup && resultScope.hasComposableCalls)
      ) {
        branch.result = branch.result.wrapWithReplaceGroup(scope = resultScope)
      }

      if (resultWithComposableCalls == 1 && resultScope.hasComposableCalls) {
        // Realize all groups in the branch result with a conditional call - making sure
        // that nested control structures are wrapped correctly.
        //
        // 조건부 호출이 포함된 분기 결과 내부의 모든 그룹을 realize합니다.
        // 중첩된 제어 구조가 올바르게 그룹으로 감싸지도록 보장합니다.
        resultScope.realizeCoalescableChildren()
      }
    }

    if (
      optimizeNonSkippingGroups &&
      needsResultGroups &&
      (transformed.origin == IrStatementOrigin.ANDAND || transformed.origin == IrStatementOrigin.OROR)
    ) {
      // When a IrWhen has a ANDAND or OROR origin it is required they also have a
      // specific shape such as for ANDAND requires a `true -> false` clause at the end.
      // As we violate this by adding a wrapping group around all results, this origin
      // is removed down-stream lowerings will no longer special case this IrWhen.
      //
      // IrWhen이 ANDAND 또는 OROR 연산자로부터 생성된 경우, ANDAND의 경우 마지막에
      // `true -> false` 절과 같은 특정한 형태를 가져야 합니다. 그러나 모든 결과에
      // 래핑 그룹을 추가함으로써 이러한 형태를 위반하게 되므로, 이 origin은 제거됩니다.
      // 이후 단계의 lowering에서는 이 IrWhen을 더 이상 특별하게 처리하지 않게 됩니다.
      transformed.origin = IrStatementOrigin.WHEN
    }

    return when {
      (
        (!optimizeNonSkippingGroups && resultWithComposableCalls == 1) ||
          needsWrappingWholeGroup
        ) -> transformed.wrapWithCoalescableGroup(scope = whenScope)
      else -> transformed
    }
  }

  private fun visitFunctionInScope(fn: IrFunction): IrStatement {
    val scope = currentFunctionScope

    // if the function isn't composable, there's nothing to do.
    if (!scope.isComposable) return super.visitFunction(fn)

    if (fn.isDefaultParamStub) {
      // don't transform the body of the stub normally.
      return visitComposableFunctionStub(fn)
    }

    // if the function doesn't have a body, there's nothing to do.
    if (fn.body == null) return fn

    val isRestartable = fn.shouldBeRestartable()
    val isLambda = fn.isLambda()
    val isUnit = fn.returnType.isUnit()

    val changedBitMaskValue = scope.changedBitMaskValue!!
    val defaultBitMaskValue = scope.defaultBitMaskValue

    // restartable functions get extra logic and different types of groups from
    // non-restartable functions, and lambdas get no groups at all.
    //
    // 재시작 가능한 함수는 재시작 불가능한 함수와는 다른 종류의 그룹과
    // 추가 로직을 가지며, 람다식은 아예 그룹을 가지지 않습니다.
    return when {
      // Unit을 반환하는 컴포저블 람다라면
      isLambda && isUnit -> visitComposableLambda(
        fn = fn,
        scope = scope,
        changedParam = changedBitMaskValue,
      )

      // restart가 가능하고, Unit을 반환하는 컴포저블 함수라면
      isRestartable && isUnit -> visitRestartableComposableFunction(
        fn = fn,
        scope = scope,
        changedParam = changedBitMaskValue,
        defaultParam = defaultBitMaskValue,
      )

      // restart가 불가능한 컴포저블 함수라면 (replace, move만 가능)
      else -> visitNonRestartableComposableFunction(
        fn = fn,
        scope = scope,
        changedParam = changedBitMaskValue,
        defaultParam = defaultBitMaskValue,
      )
    }
      .also { transformedFunction ->
        // only default args and composer are marked as `isAssignable`.
        // 기본 인자와 composer만 isAssignable로 표시됩니다.
        //
        //
        //    fun myFunction(a: Int) {
        //      var a = a
        //      ...
        //    }
        //
        // 위처럼 함수 매개변수가 로컬 변수로 재할당되는 경우가 isAssignable == true 임.
        //
        // 기본 인자가 있는 매개변수는 컴파일 타임에 모두 로컬 변수로 복사되고, $composer 매개변수는
        // ComposerParamTransformer에서 $composer 매개변수 추가할 때 'isAssignable = true'로 생성함.
        val assignableParams = transformedFunction.valueParameters.filter { it.isAssignable }.toSet()
        if (assignableParams.isNotEmpty()) {
          transformedFunction.transform(
            object : IrElementTransformerVoid() {
              override fun visitGetValue(expression: IrGetValue): IrExpression {
                if (expression.symbol.owner !in assignableParams) {
                  return super.visitGetValue(expression)
                }

                val defaultParameterType = expression.type.defaultParameterType()
                if (defaultParameterType != expression.type) {
                  return IrTypeOperatorCallImpl(
                    startOffset = expression.startOffset,
                    endOffset = expression.endOffset,
                    type = expression.type,
                    operator = IrTypeOperator.IMPLICIT_CAST,
                    typeOperand = expression.type,
                    argument = IrGetValueImpl(
                      startOffset = expression.startOffset,
                      endOffset = expression.endOffset,
                      type = defaultParameterType,
                      symbol = expression.symbol,
                      origin = expression.origin,
                    ),
                  )
                }

                return super.visitGetValue(expression)
              }
            },
            null,
          )
        }
      }
  }

  private fun visitComposableCall(expression: IrCall): IrExpression =
    when (expression.symbol.owner.kotlinFqName) {
      ComposeFqNames.remember -> {
        if (FeatureFlag.IntrinsicRemember.enabled) {
          visitIntrinsicRememberCall(expression)
        } else {
          visitNormalComposableCall(expression)
        }
      }

      ComposeFqNames.key -> visitKeyCall(expression)

      else -> visitNormalComposableCall(expression)
    }

  // MEMO 함수의 metrics 기록과 $changed 인자 주입을 진행함
  private fun visitNormalComposableCall(expression: IrCall): IrExpression {
    val callScope = Scope.CallScope(expression = expression, transformer = this)

    // it's important that we transform all of the parameters here since this will cause the
    // IrGetValue's of remapped default parameters to point to the right variable.
    //
    // 여기서 모든 파라미터를 변환하는 것이 중요합니다. 그래야 리매핑된 기본 파라미터의
    // IrGetValue가 올바른 변수로 연결되기 때문입니다.
    inScope(callScope) {
      expression.transformChildrenVoid()
    }

    // read-only라면 값이 변경될 일이 없기에(-> 추적할 변경이 만들어지지 않음) 별도 그룹을 만들지 않음
    encounteredComposableCall(withGroups = !expression.symbol.owner.hasReadOnlyAnnotation)

    val owner = expression.symbol.owner

    val valueParamCount = owner.valueParameters.size
    val contextParamCount = owner.contextReceiverParametersCount
    val defaultParamCount: Int
    val changedParamCount: Int
    val realValueParamCount: Int

    val hasDefaultParam = owner.valueParameters.any { it.name == ComposeNames.DEFAULT_PARAMETER }
    if (!hasDefaultParam && expression.isInvoke()) {
      // In the case of an invoke without any defaults, all of the parameters are going to
      // be type parameter args which won't have special names. In this case, we know that
      // the values cannot be defaulted though, so we can calculate the number of real parameters
      // based on the total number of parameters.
      //
      // 기본값이 없는 invoke의 경우, 모든 파라미터는 특별한 이름이 없는 타입 파라미터 인자입니다.
      // 이 경우 값이 기본값일 수 없다는 것을 알고 있으므로, 전체 파라미터 수를 기준으로 실제 파라미터
      // 수를 계산할 수 있습니다.

      defaultParamCount = 0
      changedParamCount = changedParamCountFromTotal(
        // Subtracting context params from total since they are included in thisParams.
        // thisParams에 컨텍스트 파라미터가 포함되어 있으므로 전체 파라미터 수에서 이를 빼줍니다.
        totalParamsIncludingThisParams = valueParamCount - contextParamCount + owner.thisParamCount,
      )
      realValueParamCount =
        valueParamCount -
          contextParamCount -
          1 - // composer param
          changedParamCount
    }

    // hasDefaultParam == true || expression.isInvoke() == false
    else {
      // Context receiver params are value parameters and will precede real params, calculate
      // the amount of real params by finding the index off the last real param (if any) and
      // offsetting it by the amount of context receiver params.
      //
      // 컨텍스트 리시버 파라미터는 값 파라미터이며 실제 파라미터보다 앞에 위치합니다. 따라서
      // 마지막 실제 파라미터의 인덱스를 기준으로 컨텍스트 리시버 파라미터 수만큼 보정하여
      // 실제 파라미터 수를 계산합니다.
      val composerParamIndex = owner.valueParameters.indexOfFirst { it.name == ComposeNames.COMPOSER_PARAMETER }
      realValueParamCount = if (composerParamIndex != -1) composerParamIndex - contextParamCount else 0

      defaultParamCount =
        if (hasDefaultParam)
          defaultParamCount(valueParamCount = contextParamCount + realValueParamCount)
        else
          0
      changedParamCount =
        changedParamCount(realValueParamCount = realValueParamCount, thisParamCount = owner.thisParamCount)
    }

    val expectedAllParamCount =
      contextParamCount +
        realValueParamCount +
        1 + // composer param
        changedParamCount +
        defaultParamCount

    require(valueParamCount == expectedAllParamCount) {
      "Expected $expectedAllParamCount params for ${owner.name}, but got $valueParamCount"
    }

    val composerIndex = contextParamCount + realValueParamCount
    val changedArgIndex = composerIndex + 1
    val defaultArgIndex = changedArgIndex + changedParamCount
    val defaultArgs = (defaultArgIndex until valueParamCount).map { expression.getValueArgument(index = it) }
    val hasDefaultArgs = defaultArgs.isNotEmpty()

    val defaultMasks = defaultArgs.map { arg ->
      if (arg !is IrConst) error("Expected default mask to be a const")
      arg.value as? Int ?: error("Expected default mask to be an Int")
    }

    val contextMetas = mutableListOf<CallArgumentMeta>()
    val paramMetas = mutableListOf<CallArgumentMeta>()

    for (paramIndex in 0 until contextParamCount + realValueParamCount) {
      val arg = expression.getValueArgument(index = paramIndex)

      // MEMO 기본값을 사용하는 인자는 IrConst(null) 혹은 원시타입 기본값이 들어감.
      //  arg 자체가 null인 경우는 vararg 매개변수밖에 없음.
      //  (vararg는 기본값 없이 인자가 제공되지 않을 수 있음)
      //
      // ComposableFunctionParamTransformer의 IrCall.copyCallWithComposerParamIfNeeded 함수 참고
      if (arg == null) {
        val param = owner.valueParameters[paramIndex]
        if (param.varargElementType == null) {
          // ComposerParamTransformer should not allow for any null arguments on a composable
          // invocation unless the parameter is vararg. If this is null here, we have
          // missed something.
          //
          // ComposerParamTransformer(ComposableFunctionParamTransformer)는 가변 인자가 아닌 한,
          // 컴포저블 호출에서 null 인자를 허용하지 않아야 합니다. 여기서 null이라면 무언가를
          // 놓친 것입니다.
          error("Unexpected null argument for composable call")
        } else {
          paramMetas.add(CallArgumentMeta(isVararg = true))
          continue
        }
      }

      if (paramIndex < contextParamCount) {
        val meta = argumentMetaOf(arg = arg, isProvided = true)
        contextMetas.add(meta)
      } else {
        val defaultBitIndex = defaultBitIndex(index = paramIndex)
        val defaultMaskValue = if (hasDefaultArgs) defaultMasks[defaultParamIndex(index = paramIndex)] else 0
        val meta = argumentMetaOf(
          arg = arg,
          // default가 0b1이라면 "인자 제공이 안되었으니 기본 인자를 사용함"을 의미함
          isProvided = defaultMaskValue and (0b1 shl defaultBitIndex) == 0,
        )
        paramMetas.add(meta)
      }
    }

    val extensionMeta = expression.extensionReceiver?.let { extensionArg ->
      argumentMetaOf(arg = extensionArg, isProvided = true)
    }
    val dispatchMeta = expression.dispatchReceiver?.let { dispatchArg ->
      argumentMetaOf(arg = dispatchArg, isProvided = true)
    }

    val changedArgs =
      buildChangedArgumentsForCall(
        contextArgs = contextMetas,
        valueArgs = paramMetas,
        extensionArg = extensionMeta,
        dispatchArg = dispatchMeta,
      )

    changedArgs.fastForEachIndexed { i, arg ->
      expression.putValueArgument(changedArgIndex + i, arg)
    }

    currentFunctionScope.metrics.recordComposableCall(
      expression = expression,
      paramMeta = paramMetas,
    )
    metrics.recordComposableCall(
      expression = expression,
      paramMeta = paramMetas,
    )
    recordCallInSource(call = expression)

    return callScope.marker?.let { expression.variablePrefix(variable = it) } ?: expression
  }

  // FeatureFlag.IntrinsicRemember.enabled 일 때만 호출됨
  // MEMO remember {} 호출을 composer.cache() 호출로 *직접* 인라인하는 작업.
  //  불필요한 컴포즈 코드 생성이 줄어든다.
  private fun visitIntrinsicRememberCall(expression: IrCall): IrExpression {
    val keyArgs = mutableListOf<IrExpression>()
    var calculationArg: IrExpression? = null
    var hasSpreadArgs = false

    for (i in 0 until expression.valueArgumentsCount) {
      val param = expression.symbol.owner.valueParameters[i]
      val arg = expression.getValueArgument(i) ?: error("Unexpected null argument found on key call")

      if (param.name.asString().startsWith('$'))
      // we are done. synthetic args go at the end
        break

      when {
        param.name.identifier == "calculation" -> {
          calculationArg = arg
        }

        arg is IrVararg -> {
          keyArgs.addAll(
            arg.elements.mapNotNull { element ->
              if (element is IrSpreadElement) {
                hasSpreadArgs = true
                arg
              } else {
                // STUDY ValueParameter에 IrExpression 아닌 게 들어올 수 있나??
                element as? IrExpression
              }
            },
          )
        }

        else -> keyArgs.add(arg)
      }
    }

    for (i in keyArgs.indices) {
      keyArgs[i] = keyArgs[i].transform(this, null)
    }

    encounteredComposableCall(withGroups = true)
    recordCallInSource(call = expression)

    if (calculationArg == null) {
      return expression
    }

    if (hasSpreadArgs) {
      calculationArg.transform(this, null)
      return expression
    }

    // Build the change parameters as if this was a call to remember to ensure the
    // use of the $dirty flags are calculated correctly.
    //
    // remember 호출처럼 $dirty 플래그의 사용이 정확히 계산되도록 $change 파라미터를
    // 구성합니다.
    val keyArgMetas = keyArgs.map { argumentMetaOf(arg = it, isProvided = true) }

    // If intrinsic remember uses $dirty, we are not sure if it is going to be populated,
    // so we have to apply fixups after function body is transformed.
    //
    // intrinsic remember가 $dirty를 사용할 경우, 해당 값이 채워질지 확실하지 않기 때문에
    // 함수 본문이 변환된 후에 후속 수정 작업(fixups)을 적용해야 합니다.
    var dirty: IrChangedBitMaskValue? = null
    keyArgMetas.fastForEach { argMeta ->
      val parent = argMeta.referencedParam
      if (parent?.dirty is IrChangedBitMaskVariable /* %dirty */) {
        if (dirty == null) {
          dirty = parent.dirty
        } else {
          // Validate that we only capture dirty param from a single scope. Capturing
          // $dirty is only allowed in inline functions, so we are guaranteed to only
          // encounter one.
          //
          // STUDY "Capturing $dirty is only allowed in inline functions" 관련 로직 파악해 보기
          //
          // %dirty는 inline 함수 내에서만 캡처할 수 있으므로 단일 스코프에서만 캡처되는지를
          // 검증해야 합니다. 이로 인해 하나의 스코프만 다루게 된다는 보장이 있습니다.
          require(dirty == parent.dirty) {
            "Only single dirty param is allowed in a capture scope"
          }
        }
      }
    }

    val usesDirtyVariable = keyArgMetas.any { it.referencedParam?.dirty is IrChangedBitMaskVariable }
    val isMemoizedLambda = expression.origin == ComposeMemoizedLambdaOrigin

    // We can only rely on the $changed or $dirty if the flags are correctly updated in
    // the restart function or the result of replacing remember with cached will be
    // different.
    //
    // $changed나 $dirty는 restart 함수에서 해당 플래그들이 정확히 업데이트되는 경우에만
    // 신뢰할 수 있습니다. 그렇지 않으면 remember를 cached로 대체했을 때 결과가 달라질 수
    // 있습니다.
    //
    // consistent: 한결같은, 일관된 (복습!)
    //
    // STUDY 이건 변수명이 왜이럴까?
    val metaMaskConsistent = updateChangedFlagsFunction != null

    val changedFunction: (isMemoizedLambda: Boolean, arg: IrExpression, argInfo: CallArgumentMeta) -> IrExpression? =
      if (usesDirtyVariable || !metaMaskConsistent) {
        { _, arg, _ ->
          irChanged(
            value = arg,
            compareInstanceForFunctionTypes = false,
            compareInstanceForUnstableValues = isMemoizedLambda,
          )
        }
      } else {
        ::irIntrinsicChanged
      }

    // Hoist execution of input params outside of the remember group, similar to how it is
    // handled with inlining.
    //
    // 인라인 처리 방식과 유사하게, remember 그룹 바깥으로 입력 파라미터의 실행을 끌어올립니다.
    val keyCopiedVaraiables = keyArgs.mapIndexed { index, key ->
      val meta = keyArgMetas[index]

      // Only create variables when reads introduce side effects.
      // 읽기가 부작용을 일으키는 경우에만 변수를 생성합니다.
      //
      // trivial: 사소한, 하찮은
      val trivialExpression = meta.isReferenced || key is IrGetValue || key is IrConst
      if (!trivialExpression) {
        irTemporary(value = key, nameHint = $$"remember$arg$$$index")
      } else {
        null
      }
    }

    val keyGetterExprs = keyCopiedVaraiables.mapIndexed { index, variable ->
      variable?.let(::irGet) ?: keyArgs[index]
    }
    val invalidExpr =
      irIntrinsicRememberInvalid(
        isMemoizedLambda = isMemoizedLambda,
        args = keyGetterExprs,
        metas = keyArgMetas,
        changedExpr = changedFunction,
      )

    val functionScope = currentFunctionScope
    val cacheCall =
      irCache(
        currentComposer = irCurrentComposer(),
        startOffset = expression.startOffset,
        endOffset = expression.endOffset,
        returnType = expression.type,
        invalid = invalidExpr,
        calculation = calculationArg.transform(this, null),
      )

    if (usesDirtyVariable && metaMaskConsistent) {
      functionScope.recordIntrinsicRememberFixup(
        isMemoizedLambda = isMemoizedLambda,
        args = keyGetterExprs,
        metas = keyArgMetas,
        call = cacheCall,
      )
    }

    val nonNullKeyVariables = keyCopiedVaraiables.filterNotNull()
    val blockScope = intrinsicRememberScope(expression)

    return inScope(blockScope) {
      if (useNonSkippingGroupOptimization) {
        val body = irWrapWithSourceInformationMarkerIfNeeded(
          expression = cacheCall,
          scope = blockScope,
          before = nonNullKeyVariables,
        )

        // Ensure that the body of intrinsic remember is always represented as a block,
        // so that intrinsic remember propagates isStatic if needed.
        //
        // intrinsic remember의 본문이 항상 블록으로 표현되도록 하여, 필요한 경우
        // isStatic이 전파되도록 보장합니다.
        body as? IrBlock ?: body.wrap(type = body.type)
      }

      // useNonSkippingGroupOptimization == false
      else {
        cacheCall.wrap(
          before = nonNullKeyVariables +
            listOf(
              irStartReplaceGroup(
                element = expression,
                scope = blockScope,
                key = irFunctionSourceKey(expression.symbol.owner),
              ),
            ),
          after = listOf(irEndReplaceGroup(scope = blockScope)),
        )
      }
    }
      .also { expr ->
        if (
          stabilityInferencer.stabilityOfType(type = expr.type).knownStable() &&
          keyArgMetas.all { it.isStatic }
        ) {
          context.irTrace.record(
            slice = ComposeWritableSlices.IS_STATIC_EXPRESSION,
            key = expr,
            value = true,
          )
        }
      }
  }

  private fun visitKeyCall(expression: IrCall): IrExpression {
    encounteredComposableCall(withGroups = true)

    val keyArgs = mutableListOf<IrExpression>()
    var blockArg: IrExpression? = null

    for (argIndex in 0 until expression.valueArgumentsCount) {
      val param = expression.symbol.owner.valueParameters[argIndex]
      val arg = expression.getValueArgument(argIndex) ?: error("Unexpected null argument found on key call")
      if (param.name.asString().startsWith('$'))
      // we are done. synthetic args go at the end.
        break

      when {
        param.name.identifier == "block" -> {
          blockArg = arg
        }

        arg is IrVararg -> {
          keyArgs.addAll(arg.elements.mapNotNull { it as? IrExpression })
        }

        else -> {
          keyArgs.add(arg)
        }
      }
    }

    if (blockArg !is IrFunctionExpression)
      error("Expected function expression but was ${blockArg?.let { it::class }}")

    val (nonReturningBody, resultVar) = blockArg.function.body!!.asBodyAndResultVar()
    var transformedBody: IrExpression = nonReturningBody

    val scope = withScope(Scope.BranchScope()) {
      transformedBody = transformedBody.transform(this, null)
    }

    // now after the inner block is extracted, the $composer parameter used in the block needs
    // to be remapped to the outer composer instead for the expression and any inlined lambdas.
    //
    // 이제 내부 블록이 추출된 후, 해당 블록에서 사용된 $composer 매개변수는 해당 표현식 및
    // 인라인된 람다들에 대해 외부의 composer로 다시 매핑되어야 합니다.
    nonReturningBody.transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitFunction(declaration: IrFunction): IrStatement =
        if (inlineLambdaInfo.isInlineLambda(declaration)) {
          super.visitFunction(declaration)
        } else {
          declaration
        }

      override fun visitGetValue(expression: IrGetValue): IrExpression {
        super.visitGetValue(expression)

        val value = expression.symbol.owner
        return if (value is IrValueParameter && value.name == ComposeNames.COMPOSER_PARAMETER) {
          irCurrentComposer()
        } else {
          expression
        }
      }
    })

    return irBlock(
      type = expression.type,
      statements = listOfNotNull(
        irStartMovableGroup(
          element = expression,
          joinedData = irJoinKeyChain(keyExprs = keyArgs.map { it.transform(this, null) }),
          scope = scope,
        ),
        nonReturningBody,
        irEndMovableGroup(scope = scope),
        resultVar?.let { irGet(resultVar) },
      ),
    )
  }

  // Composable lambdas are always wrapped with a ComposableLambda class, which has its own
  // group in the invoke call. As a result, composable lambdas:
  //
  // 1. receive no group at the root of their body
  // 2. cannot have default parameters, so have no default handling
  // 3. they cannot be skipped since we do not know their capture scope, so no skipping logic
  // 4. proper groups around control flow structures in the body
  //
  // 컴포저블 람다는 항상 ComposableLambda 클래스로 감싸지며, 해당 클래스는 invoke 호출 시
  // 자체 그룹을 가집니다. 이로 인해 컴포저블 람다는 다음과 같은 특징을 가집니다:
  //
  // 1. 본문 루트에 그룹이 생성되지 않습니다.
  // 2. 기본 파라미터를 가질 수 없어, 기본값 처리 로직이 없습니다.
  // 3. **캡처 스코프를 알 수 없기 때문에 스킵될 수 없으며, 스킵 로직이 없습니다.**
  // 4. 본문 내 제어 흐름 구조에는 적절한 그룹이 추가됩니다.
  //
  // STUDY 본문에 skipToGroupEnd() 호출이 있으니 스킵 로직이 있는 거 아닌가???
  //  "they cannot be skipped since we do not know their capture scope, so no skipping logic" 의미
  //  해석이 필요함.
  @OptIn(IrImplementationDetail::class, IDEAPluginsCompatibilityAPI::class)
  private fun visitComposableLambda(
    fn: IrFunction,
    scope: Scope.FunctionScope,
    changedParam: IrChangedBitMaskValue,
  ): IrFunction {
    // no group, since composableLambda should already create one no default logic.
    // 그룹은 생성하지 않으며, composableLambda가 이미 그룹을 생성하기 때문입니다. 기본값 처리 로직도 없습니다.
    //
    //
    // composableLambda는 restart group을 만든다.
    // https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/internal/ComposableLambda.kt;l=119;drc=c6155c0227c25d3cbdbeafb3e42418b5d843c5df
    val body = fn.body!!

    // preamble: 서문, 전문, 말의 서두 (에필로그의 반대!!)
    val sourceInformationPreamble = mutableStatementContainer()
    val skipPreamble = mutableStatementContainer()
    val bodyPreamble = mutableStatementContainer()

    // epilogue: 끝맺는 말 (에필로그!!)
    val bodyEpilogue = mutableStatementContainer()

    val isInlineLambda = scope.isInlineLambda
    val emitTraceMarkers = traceEventMarkersEnabled && !scope.isInlineLambda

    // 인라인 람다가 아닐 때만 SourceInfo를 기록함 (인라인되면 함수 오프셋 등이 다 달라짐)
    if (collectSourceInformation && !isInlineLambda) {
      sourceInformationPreamble.statements.add(irSourceInformation(scope = scope))
    }

    // we start off assuming that we *can* skip execution of the function.
    // 함수의 실행을 스킵할 수 있다고 처음부터 가정하고 시작합니다.
    //
    // skippable 조건:
    //   - Unit 반환
    //   - inline 람다가 아님
    //   - 모든 trackedParameter가 불안정한 타입이 아님
    var canSkipExecution =
      fn.returnType.isUnit() &&
        !isInlineLambda &&
        scope.trackedParameters.none { stabilityInferencer.stabilityOfType(type = it.type).knownUnstable() }

    // if the function can never skip, or there are no parameters to test, then we
    // don't need to have the dirty parameter locally since it will never be different from
    // the passed in `changed` parameter.
    //
    // 함수를 절대 스킵할 수 없거나, 검사할 파라미터가 없는 경우에는 dirty 파라미터를 지역 변수로
    // 가질 필요가 없습니다. 이 경우 dirty는 전달받은 changed 파라미터와 항상 동일하기 때문입니다.
    val dirty =
      if (canSkipExecution && scope.trackedParameters.isNotEmpty()) {
        // NOTE(lmr): Technically, dirty is a mutable variable, but we don't want to mark it
        //  as one since that will cause a `Ref<Int>` to get created if it is captured. Since
        //  we know we will never be mutating this variable _after_ it gets captured, we can
        //  safely mark this as `isVar = false`.
        //
        // 기술적으로 dirty는 변경 가능한 변수지만, 이를 var로 표시하고 싶지는 않습니다.
        // var로 표시하면 캡처될 경우 'Ref<Int>'가 생성되기 때문입니다. 하지만 이 변수는
        // 캡처된 이후에는 절대 변경되지 않는다는 것을 알고 있으므로, 'isVar = false'로
        // 안전하게 설정할 수 있습니다.
        //
        //
        // $dirty 만듦
        changedParam.irCopyToDirtyVariable(
          // LLVM validation doesn't allow us to have val here.
          // LLVM 유효성 검사는 여기에서 val을 사용하는 것을 허용하지 않습니다.
          isVar = !context.platform.isJvm() && !context.platform.isJs(),
          nameHint = $$"$dirty",
          exactName = true,
        )
      } else {
        // $dirty 없이 $changed만 사용함
        changedParam
      }
    scope.dirty = dirty

    val (nonReturningBody, returnVar) = body.asBodyAndResultVar(expectedTarget = fn)

    // we must transform the body first, since that will allow us to see whether or not we
    // are using the dispatchReceiverParameter or the extensionReceiverParameter.
    //
    // 본문을 먼저 변환해야 합니다. 그래야 dispatchReceiverParameter나 extensionReceiverParameter를
    // 사용하는지 여부를 확인할 수 있습니다.
    val transformedNonReturningBody: IrContainerExpression =
      nonReturningBody.apply { transformChildrenVoid() }.let { body ->
        // Ensure that all group children of composable inline lambda are realized, since the inline
        // lambda doesn't require a group on its own.
        //
        // 컴포저블 inline 람다는 자체적으로 그룹이 필요하지 않기 때문에, 해당 람다의 모든 그룹 자식들이
        // 실제로 실현되도록 해야 합니다.
        if (scope.isInlineLambda && scope.isComposable) {
          scope.shouldRealizeCoalescableChildren()
        }

        if (isInlineLambda) body.asSourceOrEarlyExitGroup(scope = scope) else body
      }

    canSkipExecution = buildPreambleStatementsAndReturnIsSkippable(
      sourceElement = body,
      functionScope = scope,
      defaultParamScope = Scope.ParametersScope(),
      isSkippableDeclaration = canSkipExecution,
      skipPreamble = skipPreamble,
      bodyPreamble = bodyPreamble,
      dirtyBitMaskValue = dirty,
      changedBitMaskValue = changedParam,
      defaultBitMaskValue = null,
    )

    // NOTE: It's important to do this _after_ the above call since it can change the
    //  value of `dirty.used`.
    //
    // 위의 호출 이후에 이 작업을 수행하는 것이 중요합니다. 해당 호출이 'dirty.used' 값을
    // 변경할 수 있기 때문입니다.
    if (emitTraceMarkers) {
      transformedNonReturningBody.wrapWithTraceEvents(key = irFunctionSourceKey(), scope = scope)
    }

    // if it has non-optional unstable params, the function can never skip, so we always
    // execute the body. Otherwise, we wrap the body in an if and only skip when certain
    // conditions are met.
    //
    // 기본값이 없는 불안정한 파라미터가 있다면, 해당 함수는 절대 스킵할 수 없으므로
    // 본문을 항상 실행합니다. 그 외의 경우에는 본문을 if로 감싸고, 특정 조건이 충족될
    // 때에만 스킵합니다.
    val dirtyForSkipping =
      if (dirty.used && dirty is IrChangedBitMaskVariable /* $dirty */) {
        skipPreamble.statements.addAll(0, dirty.getDirtyVariables())
        dirty
      } else {
        changedParam
      }

    if (emitTraceMarkers) {
      scope.realizeEndCalls { irTraceEventEnd()!! }
    }

    scope.applyIntrinsicRememberInvalidFixups { isMemoizedLambda, args, metas ->
      if (!canSkipExecution) {
        // replace dirty with changed param in meta used for inference, as we are not
        // populating dirty.
        //
        // dirty 값을 채우지 않기 때문에, 추론에 사용되는 메타데이터에서는 dirty 대신
        // changed 파라미터를 사용합니다.
        metas.fastForEach {
          if (it.referencedParam?.dirty == dirty) {
            it.referencedParam?.dirty = changedParam
          }
        }
      }

      irIntrinsicRememberInvalid(
        isMemoizedLambda = isMemoizedLambda,
        args = args,
        metas = metas,
        changedExpr = ::irIntrinsicChanged,
      )
    }

    if (canSkipExecution) {
      // We CANNOT skip if any of the following conditions are met
      //
      // 1. if any of the stable parameters have *differences* from last execution.
      // 2. if the composer.skipping call returns false
      // 3. function is inline
      //
      // 다음 조건 중 하나라도 충족되면 절대 스킵할 수 없습니다:
      //
      // 1. 안정적인 파라미터 중 하나라도 이전 실행과 다른 값을 가진 경우
      // 2. composer.skipping 호출이 false를 반환하는 경우
      // 3. 함수가 inline인 경우
      val shouldRecompose =
        irShouldExecute(
          // - trackedParameters 중에 ParamState.Same이 아닌 매개변수가 하나라도 있다면 true
          // - $changed의 LSB가 1이라면 true
          parametersChanged = dirtyForSkipping.irHasDifferences(usedParams = scope.usedParams),

          // $changed의 LSB를 가져옴
          flags = dirtyForSkipping.irRestartFlags(),
        )
      val transformedBody =
        irIfThenElse(
          condition = shouldRecompose,
          thenPart = irBlock(
            type = context.irBuiltIns.unitType,
            statements = transformedNonReturningBody.statements,
          ),
          // Use end offsets so that stepping out of the composable function
          // does not step back to the start line for the function.
          //
          // end offset를 사용하여 컴포저블 함수에서 빠져나갈 때, 디버깅 시 함수의
          // 시작 라인으로 되돌아가지 않도록 합니다.
          //
          // STUDY 여기서 닫는 그룹은 무슨 그룹??
          elsePart = irSkipToGroupEnd(),
        )

      scope.realizeCoalescableChildren()
      fn.body = context.irFactory.createBlockBody(body.startOffset, body.endOffset).apply {
        this.statements.addAll(
          listOfNotNull(
            *sourceInformationPreamble.statements.toTypedArray(),
            *scope.markerPreamble.statements.toTypedArray(),
            *skipPreamble.statements.toTypedArray(),
            *bodyPreamble.statements.toTypedArray(),
            transformedBody,
            returnVar?.let { irReturnVar(target = fn.symbol, value = it) },
          ),
        )
      }
    }

    // canSkipExecution == false
    else {
      scope.realizeCoalescableChildren()
      fn.body = context.irFactory.createBlockBody(body.startOffset, body.endOffset).apply body@{
        this@body.statements.addAll(
          listOfNotNull(
            *scope.markerPreamble.statements.toTypedArray(),
            *sourceInformationPreamble.statements.toTypedArray(),
            *skipPreamble.statements.toTypedArray(),
            *bodyPreamble.statements.toTypedArray(),
            transformedNonReturningBody,
            *bodyEpilogue.statements.toTypedArray(),
            returnVar?.let { irReturnVar(target = fn.symbol, value = it) },
          )
        )
      }
    }

    scope.metrics.recordFunction(
      composable = true,
      restartable = true,
      skippable = canSkipExecution,
      isLambda = true,
      inline = false,
      hasDefaults = false,
      readonly = false,
    )

    // composable lambdas all have a root group, but we don't generate them as the source
    // code itself has the start/end call.
    //
    // 컴포저블 람다는 모두 루트 그룹을 가지지만, 소스 코드 자체에 start와 end 호출이
    // 있기 때문에 이를 생성하지는 않습니다.
    scope.metrics.recordGroup()

    return fn
  }

  // Most composable function declarations will be restartable. At a high level, this means
  // that for this function we:
  //
  // 1. generate a startRestartGroup and endRestartGroup call around its body
  // 2. generate an updateScope lambda and call
  // 3. generate handling of default parameters if necessary
  // 4. generate skipping logic based on parameters passed into the function
  // 5. generate groups around control flow structures in the body
  //
  // 대부분의 컴포저블 함수 선언은 재시작 가능하게 처리됩니다. 상위 수준에서 보면,
  // 해당 함수에 대해 다음과 같은 처리가 이루어집니다:
  //
  // 1. 본문을 감싸는 startRestartGroup과 endRestartGroup 호출을 생성합니다.
  // 2. updateScope 람다와 그 호출을 생성합니다.
  // 3. 필요 시 기본 파라미터 처리 로직을 생성합니다.
  // 4. 함수에 전달된 파라미터를 기반으로 스킵 처리 로직을 생성합니다.
  // 5. 본문 내 제어 흐름 구조에 그룹을 생성합니다.
  @OptIn(IrImplementationDetail::class, IDEAPluginsCompatibilityAPI::class)
  private fun visitRestartableComposableFunction(
    fn: IrFunction,
    scope: Scope.FunctionScope,
    changedParam: IrChangedBitMaskValue,
    defaultParam: IrDefaultBitMaskValue?,
  ): IrFunction {
    val body = fn.body!!

    val skipPreamble = mutableStatementContainer()
    val bodyPreamble = mutableStatementContainer()

    // NOTE(lmr): Technically, dirty is a mutable variable, but we don't want to mark it
    // as one since that will cause a `Ref<Int>` to get created if it is captured. Since
    // we know we will never be mutating this variable _after_ it gets captured, we can
    // safely mark this as `isVar = false`.
    //
    // 기술적으로 dirty는 변경 가능한 변수지만, 이를 var로 표시하고 싶지 않습니다. var로
    // 표시하면 캡처될 경우 Ref<Int>가 생성되기 때문입니다. 하지만 이 변수는 캡처된 이후에는
    // 절대 변경되지 않음을 알고 있으므로, 안전하게 isVar = false로 설정할 수 있습니다.
    val dirty =
      if (scope.trackedParameters.isNotEmpty())
        changedParam.irCopyToDirtyVariable(
          // LLVM validation doesn't allow us to have val here.
          isVar = !context.platform.isJvm() && !context.platform.isJs(),
          nameHint = $$"$dirty",
          exactName = true,
        )
      else
        changedParam

    scope.dirty = dirty

    val (nonReturningBody, returnVar) = body.asBodyAndResultVar()
    val defaultScope = transformDefaultValues(scope = scope)

    val end = {
      irEndRestartGroupAndUpdateScope(
        scope = scope,
        changedParam = changedParam,
        defaultParam = defaultParam,
        realValueParamCount = scope.realValueParamCount,
      )
    }
    val endWithTraceEventEnd = {
      irComposite(
        statements = listOfNotNull(
          if (traceEventMarkersEnabled) irTraceEventEnd() else null,
          end()
        ),
      )
    }

    // we must transform the body first, since that will allow us to see whether or not we
    // are using the dispatchReceiverParameter or the extensionReceiverParameter.
    //
    // 본문을 먼저 변환해야 합니다. 그래야 dispatchReceiverParameter나 extensionReceiverParameter를
    // 사용하는지 여부를 확인할 수 있습니다.
    val transformed = nonReturningBody.apply { transformChildrenVoid() }

    val canSkipExecution = buildPreambleStatementsAndReturnIsSkippable(
      sourceElement = body,
      functionScope = scope,
      defaultParamScope = defaultScope,
      isSkippableDeclaration = !fn.hasNonSkippableAnnotation,
      skipPreamble = skipPreamble,
      bodyPreamble = bodyPreamble,
      // we start off assuming that we *can* skip execution of the function.
      // 함수 실행을 스킵할 수 있다고 처음부터 가정합니다.
      dirtyBitMaskValue = dirty,
      changedBitMaskValue = changedParam,
      defaultBitMaskValue = defaultParam,
    )

    // NOTE: It's important to do this _after_ the above call since it can change the
    //  value of `dirty.used`.
    //
    // 위의 호출 이후에 이 작업을 수행하는 것이 중요합니다. 해당 호출이 'dirty.used' 값을
    // 변경할 수 있기 때문입니다.
    if (traceEventMarkersEnabled) {
      transformed.wrapWithTraceEvents(key = irFunctionSourceKey(), scope = scope)
    }

    // if it has non-optional unstable params, the function can never skip, so we always
    // execute the body. Otherwise, we wrap the body in an if and only skip when certain
    // conditions are met.
    //
    // 기본값이 없는 불안정한 파라미터가 있다면, 해당 함수는 절대 스킵할 수 없으므로
    // 본문을 항상 실행합니다. 그 외의 경우에는 본문을 if로 감싸고, 특정 조건이 충족될
    // 때에만 스킵합니다.
    val dirtyForSkipping =
      if (dirty.used && dirty is IrChangedBitMaskVariable /* $dirty */) {
        skipPreamble.statements.addAll(0, dirty.getDirtyVariables())
        dirty
      } else
        changedParam

    scope.applyIntrinsicRememberInvalidFixups { isMemoizedLambda, args, metas ->
      if (!canSkipExecution) {
        // replace dirty with changed param in meta used for inference, as we are not
        // populating dirty.
        //
        // dirty를 채우지 않기 때문에, 추론에 사용되는 메타데이터에서는 dirty 대신
        // changed 파라미터를 사용합니다.
        metas.fastForEach {
          if (it.referencedParam?.dirty == dirty) {
            it.referencedParam?.dirty = changedParam
          }
        }
      }
      irIntrinsicRememberInvalid(
        isMemoizedLambda = isMemoizedLambda,
        args = args,
        metas = metas,
        changedExpr = ::irIntrinsicChanged,
      )
    }

    val transformedBody =
      if (canSkipExecution) {
        // We CANNOT skip if any of the following conditions are met
        //
        // 1. if any of the stable parameters have *differences* from last execution.
        // 2. if the composer.skipping call returns false
        // 3. if any of the provided parameters to the function were unstable
        //
        // (3) is only necessary to check if we actually have unstable params, so we only
        // generate that check if we need to.
        //
        //
        // 다음 조건 중 하나라도 충족되면 절대 스킵할 수 없습니다:
        //
        // 1. 안정적인 파라미터 중 하나라도 이전 실행과 다른 값을 가진 경우
        // 2. composer.skipping 호출이 false를 반환하는 경우
        // 3. 함수에 전달된 인자 중 하나라도 불안정한 값이 있는 경우
        //
        // (3)은 실제로 불안정한 파라미터가 있을 때만 검사하면 되므로, 필요한 경우에만
        // 해당 검사를 생성합니다.
        var shouldRecompose =
          irShouldExecute(
            // - trackedParameters 중에 ParamState.Same이 아닌 매개변수가 하나라도 있다면 true
            // - $changed의 LSB가 1이라면 true
            parametersChanged = dirtyForSkipping.irHasDifferences(usedParams = scope.usedParams),

            // $changed의 LSB를 가져옴
            flags = dirtyForSkipping.irRestartFlags(),
          )

        val trackedParameters =
          fn.valueParameters
            .take(fn.contextReceiverParametersCount + scope.realValueParamCount)

        // boolean array mapped to parameters. true indicates that the type is unstable.
        // The unstable mask is indexed by valueParameter index, which is different
        // than the slotIndex but that is OKAY because we only care about defaults, which
        // also use the value parameter index.
        //
        // 파라미터에 매핑된 boolean 배열이며, true는 해당 타입이 불안정함을 나타냅니다.
        // 불안정 마스크는 slotIndex가 아니라 valueParameter 인덱스를 기준으로 하며,
        // 기본값 처리에서도 이 인덱스를 사용하므로 문제가 없습니다.
        val unstableMask =
          trackedParameters
            .map { stabilityInferencer.stabilityOfType(type = it.varargElementType ?: it.type).knownUnstable() }
            .toBooleanArray()

        val hasAnyUnstableParam = unstableMask.any { it }

        // If we aren't in strong skipping mode and if there are unstable params,
        // then we fence the whole expression with a check to see if any of the unstable
        // params were the ones that were provided to the function. If they were, then
        // we short-circuit and always execute
        //
        // 강력한 건너뛰기가 비활성화되어 있고, 불안정한 파라미터가 있는 경우, 전체 표현식을
        // 감싸서 해당(불안정한 타입을 갖는) 파라미터 중 실제 인자값이 제공된 게 있는지
        // 확인합니다. 만약 있다면, if 분기를 건너뛰고 항상 리컴포지션합니다.
        //
        // fence: 울타리
        //
        // 강력한 건너뛰기가 비활성되어 있고, 불안정한 매개변수가 있고,
        // $default가 있다면
        if (
          !FeatureFlag.StrongSkipping.enabled &&
          hasAnyUnstableParam &&
          defaultParam != null
        ) {
          shouldRecompose = irOrOr(
            lhs = defaultParam.irHasAnyProvidedAndUnstable(unstable = unstableMask),
            rhs = shouldRecompose,
          )
        }

        irIfThenElse(
          condition = shouldRecompose,
          thenPart = irBlock(statements = bodyPreamble.statements + transformed.statements),
          // Use end offsets so that stepping out of the composable function
          // does not step back to the start line for the function.
          //
          // end offset를 사용하여 컴포저블 함수에서 빠져나갈 때 디버깅 커서가
          // 함수 시작 라인으로 돌아가지 않도록 합니다.
          elsePart = irSkipToGroupEnd(),
        )
      }

      // canSkipExecution == false
      else {
        irComposite(
          statements = bodyPreamble.statements // $default에 따라 매개변수에 기본 인자값 넣는 작업들
            + transformed.statements,
        )
      }

    scope.realizeGroup(endWithTraceEventEnd)

    fn.body = context.irFactory.createBlockBody(body.startOffset, body.endOffset).apply body@{
      this@body.statements.addAll(
        listOfNotNull(
          irStartRestartGroup(
            element = body,
            scope = scope,
            key = irFunctionSourceKey(),
          ),
          *scope.markerPreamble.statements.toTypedArray(),
          *skipPreamble.statements.toTypedArray(),
          transformedBody,
          if (returnVar == null) end() else null,
          returnVar?.let { irReturnVar(target = fn.symbol, value = it) },
          // STUDY return이 있을 때는 end()를 어디서 할까??
          //  endsWithReturnOrJump() 를 참고하여 조사해 보기.
        )
      )
    }

    scope.metrics.recordFunction(
      composable = true,
      restartable = true,
      skippable = canSkipExecution,
      isLambda = false,
      inline = false,
      hasDefaults = scope.hasDefaultsGroup,
      readonly = false,
    )
    scope.metrics.recordGroup()

    return fn
  }

  // At a high level, without useNonSkippingGroupOptimization, a non-restartable composable
  // function
  //
  // 1. gets a replace group placed around the body
  // 2. never calls $composer.changed(...) with its parameters
  // 3. can have default parameters, so needs to add the defaults preamble if defaults present
  // 4. proper groups around control flow structures in the body
  //
  // If supported by the runtime and useNonSkippingGroupOptimization is enabled then the
  // replace group is not necessary so the above list is changed to,
  //
  // 1. never calls $composer.changed(...) with its parameters
  // 2. can have default parameters, so needs to add the defaults preamble if defaults present
  // 3. never elides groups around control flow structures in the body
  //
  // If the function has ExplicitGroupsComposable annotation, groups or markers should be added.
  //
  //
  // 상위 수준에서 보면, useNonSkippingGroupOptimization을 사용하지 않을 경우 재시작
  // 불가능한 컴포저블 함수는 다음과 같은 처리를 받습니다:
  //
  // 1.	함수 본문을 감싸는 replace 그룹이 생성됩니다.
  // 2.	파라미터에 대해 $composer.changed(...) 호출을 절대 하지 않습니다.
  // 3.	기본 인자가 있는 경우, 기본값을 처리하는 preamble 코드를 추가해야 합니다.
  // 4.	본문 내 제어 흐름 구조에 대해 적절한 그룹이 추가됩니다.
  //
  // 만약 런타임이 이를 지원하고 useNonSkippingGroupOptimization이 활성화되어 있다면
  // replace 그룹은 불필요하므로, 위 리스트는 다음과 같이 바뀝니다:
  //
  // 1.	파라미터에 대해 $composer.changed(...) 호출을 절대 하지 않습니다.
  // 2.	기본 인자가 있는 경우, 기본값을 처리하는 preamble 코드를 추가해야 합니다.
  // 3.	본문 내 제어 흐름 구조에 대해 그룹 생략 없이 항상 그룹이 추가됩니다.
  //
  // 또한, 함수에 ExplicitGroupsComposable 어노테이션이 있는 경우에는 반드시 그룹이나
  // 마커가 추가되어야 합니다.
  //
  // MEMO 상황에 따라 replace group으로만 감쌈. 안 감쌀 수도 있음.
  @OptIn(IrImplementationDetail::class, IDEAPluginsCompatibilityAPI::class)
  private fun visitNonRestartableComposableFunction(
    fn: IrFunction,
    scope: Scope.FunctionScope,
    changedParam: IrChangedBitMaskValue,
    defaultParam: IrDefaultBitMaskValue?,
  ): IrFunction {
    val body = fn.body!!

    val hasExplicitGroups = fn.hasExplicitGroupsAnnotation
    val isReadOnly = fn.hasReadOnlyAnnotation || fn.isComposableDelegatedAccessor()

    // An outer group is required if we are a lambda or dynamic method or the runtime doesn't
    // support remember after call. A outer group is explicitly elided by readonly and has
    // explicit groups.
    //
    // STUDY "runtime doesn't support remember after call"가 무슨 기능일까???
    //
    // elision: 생략, 탈락
    //
    // 외부 그룹은 이 함수가 람다이거나, 동적(가상(virtual)이 맞는 듯?) 메서드이거나, 런타임이
    // 호출 이후 remember를 지원하지 않는 경우에 필요합니다. 외부 그룹은 readonly이면서
    // explicit groups를 가진 경우에는 명시적으로 생략됩니다.
    var outerGroupRequired =
    // [@ReadOnlyComposable이 아니고, @ExplicitGroupsComposable이 아니고, OptimizeNonSkippingGroups가 비활성화됨]
      // 이거나,
      (!isReadOnly && !hasExplicitGroups && !useNonSkippingGroupOptimization) ||
        fn.isLambda() ||            // 람다 함수이거나,
        fn.isOverridableOrOverrides // 가상 메서드일 때

    val skipPreamble = mutableStatementContainer()
    val bodyPreamble = mutableStatementContainer()

    // restart 할 수 없으므로 $dirty를 만들지 않음
    scope.dirty = changedParam
    scope.outerGroupRequired = outerGroupRequired

    val defaultScope = transformDefaultValues(scope = scope)
    val emitTraceMarkers = traceEventMarkersEnabled && !scope.function.isInline

    val (nonReturningBody, returnVar) = body.asBodyAndResultVar()
    val transformed = nonReturningBody.apply { transformChildrenVoid() }

    // If we get an early return from this function then the function itself acts like
    // an if statement and the outer group is required if the functions is not readonly
    // or has explicit groups.
    //
    // 이 함수에서 조기 리턴(early return)이 발생한다면 이 함수는 if문 처럼 동작하며,
    // 함수에 @ReadOnlyComposable과 @ExplicitGroupsComposable이 없다면 외부 그룹이 필요합니다.
    if (!isReadOnly && !hasExplicitGroups && scope.hasAnyEarlyReturn)
      outerGroupRequired = true

    // restart 할 수 없는 그룹이므로 skippable 여부를 관찰하지 않음
    buildPreambleStatementsAndReturnIsSkippable(
      sourceElement = body,
      functionScope = scope,
      defaultParamScope = defaultScope,
      isSkippableDeclaration = false,
      skipPreamble = skipPreamble,
      bodyPreamble = bodyPreamble,
      dirtyBitMaskValue = changedParam,
      changedBitMaskValue = changedParam,
      defaultBitMaskValue = defaultParam,
    )

    // NOTE: It's important to do this _after_ the above call since it can change the
    //  value of `dirty.used`.
    //
    // 위의 호출 이후에 이 작업을 수행하는 것이 중요합니다. 해당 호출이 'dirty.used' 값을
    // 변경할 수 있기 때문입니다.
    if (emitTraceMarkers) {
      transformed.wrapWithTraceEvents(key = irFunctionSourceKey(), scope = scope)
    }

    if (outerGroupRequired) {
      scope.realizeGroup(
        makeEnd = {
          irComposite(
            statements = listOfNotNull(
              if (emitTraceMarkers) irTraceEventEnd() else null,
              irEndReplaceGroup(scope = scope),
            ),
          )
        },
      )
    } else if (useNonSkippingGroupOptimization) {
      scope.shouldRealizeCoalescableChildren()
      scope.realizeCoalescableChildren()
    }

    // MEMO replace로만 감쌈.. moveable group은 key() 로직에서만 직접 감싸는 듯?
    fn.body = context.irFactory.createBlockBody(body.startOffset, body.endOffset).apply body@{
      this@body.statements.addAll(
        listOfNotNull(
          when {
            outerGroupRequired ->
              irStartReplaceGroup(
                element = body,
                scope = scope,
                key = irFunctionSourceKey(),
              )
            collectSourceInformation ->
              irSourceInformationMarkerStart(
                element = body,
                scope = scope,
                key = irFunctionSourceKey(),
              )
            else -> null
          },
          *scope.markerPreamble.statements.toTypedArray(),
          *bodyPreamble.statements.toTypedArray(),
          *transformed.statements.toTypedArray(),
          when {
            outerGroupRequired -> irEndReplaceGroup(scope = scope)
            collectSourceInformation -> irSourceInformationMarkerEnd(element = body, scope = scope)
            else -> null
          },
          returnVar?.let { irReturnVar(target = fn.symbol, value = it) },
        ),
      )
    }

    if (!outerGroupRequired) {
      scope.realizeEndCalls(
        makeEnd = {
          irComposite(
            statements = listOfNotNull(
              if (emitTraceMarkers) irTraceEventEnd() else null,
              if (collectSourceInformation)
                irSourceInformationMarkerEnd(element = body, scope = scope)
              else
                null,
            ),
          )
        },
      )
    }

    scope.metrics.recordFunction(
      composable = true,
      restartable = false,
      skippable = false,
      isLambda = fn.isLambda(),
      inline = fn.isInline,
      hasDefaults = false,
      readonly = isReadOnly,
    )
    scope.metrics.recordGroup()

    return fn
  }

  // Currently, we make all composable functions restartable by default, unless:
  //
  // 1. They are inline
  // 2. They have a return value (may get relaxed in the future)
  // 3. They are a lambda (we use ComposableLambda<...> class for this instead)
  // 4. They are annotated as @NonRestartableComposable
  //
  // 현재 모든 컴포저블 함수는 기본적으로 재시작 가능하도록 처리되지만, 다음과 같은
  // 경우는 예외입니다: (다음과 같은 경우는 restartable하지 않음. 오직 replace/move만 가능함.)
  //
  // 1. inline 함수
  // 2. 반환 타입이 Unit이 아닌 경우 (향후 완화될 수 있음)
  // 3. 람다인 경우 (ComposableLambda 클래스를 대신 사용함)
  // 4. @NonRestartableComposable 어노테이션이 지정된 경우
  //
  // (추가)
  // 5. @Composable fun interface 구현체가 아닌 로컬 함수인 경우
  // 6. @ExplicitGroupsComposable 어노테이션이 지정된 경우
  // 7. 'val a by remember { mutableStateOf(..) }' 처럼 컴포저블 함수를 델리게이트할 경우
  // 8. $composer 매개변수가 없는 경우
  // 9. 기본 인자가 있는 컴포저블의 원본 함수
  //    (ComposableDefaultParamLowering로 만들어진 스텁 함수의 원본 함수)
  // 10. open 함수
  private fun IrFunction.shouldBeRestartable(): Boolean {
    // Only insert observe scopes in non-empty composable function.
    // 비어 있지 않은 컴포저블 함수에만 observe 스코프를 삽입합니다.
    if (body == null || this !is IrSimpleFunction)
      return false

    //    fun interface A {
    //      @Composable fun compute(value: Int): Int
    //    }
    //
    //    fun test() {
    //      A { 1 }
    //    }
    //
    // 위 함수는 ComposableFunInterfaceLowering에 의해? 아래처럼 변환됨
    //
    //    interface A {
    //      @Composable abstract fun compute(value: Int, $composer: Composer?, $changed: Int): Int
    //    }
    //
    //    fun test() {
    //      class <no name provided> : A {
    //        @Composable
    //        override fun compute(it: Int, $composer: Composer?, $changed: Int): Int {
    //          return 1
    //        }
    //      }
    //      <no name provided>()
    //    }
    //
    // 이때 변환되는 compute() 함수가 local 함수이고, 이를 감싸는 <no name provided> 클래스의
    // origin이 LAMBDA_IMPL임. 이 외의 경우에 LAMBDA_IMPL origin을 갖는 코드는 찾지 못함.
    //
    //
    // val lambda = object {
    //   (이렇게 로컬 클래스 안에 정의된 함수도 로컬 함수로 간주됨)
    //   fun invoke() {}
    // }
    if (isLocal && parentClassOrNull?.origin != JvmLoweredDeclarationOrigin.LAMBDA_IMPL)
      return false

    if (isInline)
      return false

    if (hasNonRestartableAnnotation)
      return false

    if (hasExplicitGroupsAnnotation)
      return false

    if (inlineLambdaInfo.isInlineLambda(this))
      return false

    if (!returnType.isUnit())
      return false

    // val a by remember { mutableStateOf(..) } 처럼 컴포저블 함수를 델리게이트할 경우
    if (isComposableDelegatedAccessor())
      return false

    // Do not insert an observe scope if the function hasn't been transformed by the
    // ComposerParamTransformer and has a synthetic "composer param" as its last parameter.
    //
    // 함수가 ComposerParamTransformer에 의해 변환되지 않았고 마지막 파라미터로 합성된
    // "composer 파라미터"를 갖는 경우에는 observe scope를 삽입하지 않습니다. ("갖지 않는"이 맞는 듯)
    if (composerParam() == null)
      return false

    // Virtual functions with default params are called through wrapper generated in
    // ComposableDefaultParamLowering. The restartable group is moved to the wrapper, while
    // the function itself is no longer restartable.
    //
    // 기본 인자를 가진 가상 함수는 ComposableDefaultParamLowering에서 생성된 래퍼를 통해 호출됩니다.
    // 재시작 가능한 그룹은 래퍼로 이동되며, 원래 함수 자체는 더 이상 재시작 가능하지 않습니다.
    if (isVirtualFunctionWithDefaultParam())
      return false

    // Open functions cannot be restartable since restart logic makes a virtual call (TODO b/329477544)
    // open 함수는 재시작 로직이 virtual 호출을 발생시키기 때문에 재시작 가능하게 만들 수 없습니다.
    //
    // virtual call: 가상 함수는 상속하는 클래스 내에서 같은 시그니처의 함수로 오버라이딩 될 수 있는
    //               함수 또는 메소드이다.
    if (modality == Modality.OPEN && parentClassOrNull?.isFinalClass != true)
      return false

    // Check if the descriptor has restart scope calls and resolved lambdas should be ignored.
    // All composable lambdas are wrapped by a restartable function wrapper by ComposerLambdaMemoization
    // which supplies the startRestartGroup/endRestartGroup pair on behalf of the lambda.
    //
    // descriptor에 재시작 범위 호출이 있는지 확인하고, 해결된 람다는 무시해야 합니다.
    // 모든 컴포저블 람다는 ComposerLambdaMemoization에 의해 재시작 가능한 함수 래퍼로
    // 래핑되며, 이 래퍼는 람다를 대신하여 startRestartGroup/endRestartGroup 쌍을 제공합니다.
    //
    //   fun test() {
    //     run { println() }
    //         ^^^^^^^^^^^^^ <- LOCAL_FUNCTION_FOR_LAMBDA
    //   }
    return origin != IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
  }

  private fun IrFunction.isVirtualFunctionWithDefaultParam(): Boolean =
    this is IrSimpleFunction &&
      (isVirtualFunctionWithDefaultParam != null ||
        overriddenSymbols.any { it.owner.isVirtualFunctionWithDefaultParam() })

  // Stub: 함수의 본문을 다른 함수 호출로 위임하는 함수
  private fun visitComposableFunctionStub(declaration: IrFunction): IrStatement {
    // remove default parameters as the transform below would
    declaration.parameters.fastForEach { it.defaultValue = null }

    // patch $changed and $default parameters to be the same as passed to the stub
    // stub should always have the form of return Call(...), so we can just match this structure
    //
    // $changed와 $default 파라미터를 스텁에 전달된 값과 동일하게 패치합니다.
    // 스텁은 항상 return Call(...) 형태를 가지므로, 해당 구조에 맞춰 매칭하면 됩니다.
    val body = declaration.body ?: error("Expected body for composable function stub")
    val returnCall = (body.statements[0] as? IrReturn)?.value as? IrCall ?: error("Expected a single return statement with a call")

    returnCall.symbol.owner.parameters.fastForEach { param ->
      val paramName = param.name.asString()
      if (
        paramName.startsWith(ComposeNames.CHANGED_PARAMETER.asString()) ||
        paramName.startsWith(ComposeNames.DEFAULT_PARAMETER.asString())
      ) {
        val realParameter = declaration.valueParameters.find { it.name == param.name } ?: error("Expected parameter for ${param.name}")
        returnCall.arguments[param.indexInParameters] = irGet(realParameter)
      }
    }

    return declaration
  }

  // MEMO condition과 body를 replace group으로 감쌈
  private fun handleLoop(loop: IrLoop): IrExpression {
    val loopScope = Scope.LoopScope(loop)

    withScope(loopScope) {
      loop.condition = loop.condition.transform(this, null)

      if (loopScope.needsGroupPerIteration && loopScope.hasComposableCalls) {
        loop.condition = loop.condition.wrapWithReplaceGroup(loopScope)
      }

      loop.body = loop.body?.transform(this, null)

      if (loopScope.needsGroupPerIteration && loopScope.hasComposableCalls) {
        val currentBody = loop.body
        if (currentBody is IrBlock) {
          /*
           * Kotlin optimizes for loops by separating them into three pieces
           *
           *   #1: The "header"
           *   val it = <someIterable>.iterator()
           *
           *   #2: The condition
           *   while (it.hasNext()) {
           *       val loopVar = it.next()
           *
           *       #3: The loop body
           *       ...
           *   }
           *
           * We need to generate groups inside the "body", otherwise the behavior is
           * undefined, so we find the loopVar and insert groups after it.
           *
           *
           * Kotlin은 for 루프를 세 부분으로 분리하여 최적화합니다.
           *
            *   #1: The "header"
           *   val it = <someIterable>.iterator()
           *
           *   #2: The condition
           *   while (it.hasNext()) {
           *       val loopVar = it.next()
           *
           *       #3: The loop body
           *       ...
           *   }
           *
           * 루프 본문 내부에 그룹을 생성해야 하며, 그렇지 않으면 동작이 정의되지 않으므로,
           * loopVar를 찾아 그 뒤에 그룹을 삽입합니다.
           */
          val forLoopVariableIndex =
            currentBody.statements.indexOfFirst { statement ->
              (statement as? IrVariable)?.origin == IrDeclarationOrigin.FOR_LOOP_VARIABLE
            }

          loop.body = currentBody.wrapWithReplaceGroup(
            scope = loopScope,
            startAt = forLoopVariableIndex + 1,
          )
        } else {
          loop.body = currentBody?.wrapWithReplaceGroup(scope = loopScope)
        }
      }
    }

    return if (
    // 각 순회별로 그룹이 필요하지 않거나(or),
    // 내가 속한 함수를 감싸는 그룹이 필요하지 않으면서 내가 속한 함수에 early return이 없고,
      (!loopScope.needsGroupPerIteration || (
        !currentFunctionScope.outerGroupRequired &&
          // if we end up getting an early return this group will come back
          // However this might generate less efficient (but still correct code) if the
          // early return is encountered after the loop.
          //
          // 루프 이후에 조기 반환이 발생하는 경우 이 그룹은 다시 나타납니다. 다만,
          // 루프 이후에 조기 반환이 발생할 경우 덜 효율적인(그러나 여전히 올바른)
          // 코드가 생성될 수 있습니다.
          !currentFunctionScope.hasAnyEarlyReturn)
        ) &&
      // 현재 루프 블록에 컴포저블 호출이 있다면
      loopScope.hasComposableCalls
    ) {
      // If a loop contains composable calls but not a otherwise need a group per iteration
      // group, none of the children can be coalesced and must be realized as the second
      // iteration as composable calls at the end might end of overlapping slots with the
      // start of the loop. See b/232007227 for details.
      //
      //  STUDY "none of the children can be coalesced and must be realized" 이해가 안된다 ㅠㅠ
      //
      // 루프에 Composable 호출이 포함되어 있지만 반복마다 그룹이 필요하지 않은 경우,
      // 모든 자식 요소는 병합(coalesced)될 수 없으며 반드시 실현(realized)되어야 합니다.
      // 이는 두 번째 반복에서 Composable 호출이 루프 시작 부분의 슬롯과 겹칠 수 있기 때문입니다.
      // 자세한 내용은 b/232007227을 참조하십시오.
      loopScope.shouldRealizeCoalescableChildren()
      loop.wrapWithCoalescableGroup(scope = loopScope)
    } else {
      loop
    }
  }

  private fun transformDefaultValues(scope: Scope.FunctionScope): Scope.ParametersScope {
    val trackedParameters = scope.trackedParameters
    val parametersScope = Scope.ParametersScope()

    trackedParameters.fastForEach { param ->
      val defaultValue = param.defaultValue
      if (defaultValue != null) {
        defaultValue.expression = inScope(scope = parametersScope) {
          defaultValue.expression.transform(this, null)
        }
      }
    }

    return parametersScope
  }

  // MEMO $dirty, $changed, $default 파라미터 다루는 코드 만드는 로직.
  //  $dirty, $default 상태를 보고 이 컴포저블이 skippable한지 여부를 반환함.
  //
  // 원래 함수 이름: buildPreambleStatementsAndReturnIfSkippingPossible
  private fun buildPreambleStatementsAndReturnIsSkippable(
    sourceElement: IrElement,
    functionScope: Scope.FunctionScope,
    defaultParamScope: Scope.ParametersScope,
    isSkippableDeclaration: Boolean,
    skipPreamble: IrStatementContainer, // $changed와 $default에 따라 $dirty를 업데이트하는 로직이 들어감
    bodyPreamble: IrStatementContainer, // $default에 따라 매개변수에 기본 인자값을 넣는 로직이 들어감
    dirtyBitMaskValue: IrChangedBitMaskValue,
    changedBitMaskValue: IrChangedBitMaskValue,
    defaultBitMaskValue: IrDefaultBitMaskValue?,
  ): Boolean {
    val trackedParameters = functionScope.trackedParameters
    val trackedParamStabilities = Array(trackedParameters.size) { Stability.Unstable }

    // we default to true because the absence of a default expression we want to consider as
    // "static".
    //
    // 기본 표현식이 없는 경우 이를 "static"으로 간주하기 때문에 기본값은 true로 설정합니다.
    val defaultExprIsStaticOrNone = BooleanArray(trackedParameters.size) { true }
    val defaultExpr = Array<IrExpression?>(trackedParameters.size) { null }

    // 아래 조건일 때 false로 저장됨 (유일한 false 하드코딩 조건)
    //
    //   강한 건너뛰기가 비활성되어 있고, 사용되는 매개변수이고,
    //   불안정한 타입의 매개변수이고, 기본 인자가 없다면
    var mightSkip = isSkippableDeclaration

    val setDefaultStatements = mutableStatementContainer()
    val skipDefaultStatements = mutableStatementContainer()

    // #1
    withScope(defaultParamScope) {
      // - $default에 따라 매개변수에 기본 인자값을 넣는 작업
      // - $default에 따라 $changed 혹은 $dirty에 uncertain을 넣는 작업
      trackedParameters.fastForEachIndexed { slotIndex, param ->
        val defaultBitIndex = functionScope.defaultBitIndexForParamIndex(index = slotIndex)
        val defaultValue = param.defaultValue?.expression

        // 컴포저블 람다는 defaultBitMaskValue가 항상 null이라 이 로직을 절대 탈 수 없음
        if (defaultBitMaskValue != null && defaultValue != null) {
          // we want to call this on the transformed version.
          // 변환된 버전에서 이 함수를 호출하고자 합니다.
          defaultExprIsStaticOrNone[slotIndex] = defaultValue.isStaticExpression()
          defaultExpr[slotIndex] = defaultValue

          val hasStaticDefaultExprOrNone = defaultExprIsStaticOrNone[slotIndex]
          when {
            // skippable하고, 기본 인자가 있으며 static하지 않고, $dirty 변수가 있는 경우
            isSkippableDeclaration && !hasStaticDefaultExprOrNone &&
              dirtyBitMaskValue is IrChangedBitMaskVariable -> {
              // If we are setting the parameter to the default expression and
              // running the default expression again, and the expression isn't
              // provably static, we can't be certain that the dirty value of
              // SAME is going to be valid. We must mark it as UNCERTAIN. In order
              // to avoid slot-table misalignment issues, we must mark it as
              // UNCERTAIN even when we skip the defaults, so that any child
              // function receives UNCERTAIN vs SAME/DIFFERENT deterministically.
              //
              // 파라미터를 기본 표현식으로 설정하고 해당 표현식을 다시 실행하는 경우,
              // 그 표현식이 확실히 static하지 않다면 SAME으로 표시된 dirty 값이 유효하다고
              // 확신할 수 없습니다. 이럴 경우 반드시 UNCERTAIN으로 표시해야 합니다.
              // 기본값 실행을 스킵하더라도 슬롯 테이블의 불일치 문제를 방지하기 위해,
              // 모든 하위 함수가 SAME 또는 DIFFERENT가 아닌 UNCERTAIN 값을 일관되게 받도록
              // UNCERTAIN으로 표시해야 합니다.

              // [defaultExpr 재실행 진행 로직] 만약 현재 매개변수에 인자가 제공되지 않았다면,
              // 현재 매개변수에 기본 인자를 제공하고, 현재 매개변수의 $dirty를 uncertain으로
              // 설정함.
              //
              // 기본 인자값이 static하다고 확신할 수 없으므로 uncertain으로 지정함
              setDefaultStatements.statements.add(
                irIf(
                  condition = irIsArgumentValueNotProvided(
                    defaultBitMaskValue = defaultBitMaskValue,
                    bitIndex = defaultBitIndex,
                  ),
                  body = irBlock(
                    statements = listOf(
                      irSet(variable = param, value = defaultValue),
                      dirtyBitMaskValue.irSetSlotUncertain(slot = slotIndex),
                    ),
                  ),
                ),
              )

              // [defaultExpr 재실행 스킵 로직] 만약 현재 매개변수에 인자가 제공되지 않았다면,
              // 현재 매개변수의 $dirty를 uncertain으로 설정함.
              skipDefaultStatements.statements.add(
                irIf(
                  condition = irIsArgumentValueNotProvided(
                    defaultBitMaskValue = defaultBitMaskValue,
                    bitIndex = defaultBitIndex,
                  ),
                  body = dirtyBitMaskValue.irSetSlotUncertain(slot = slotIndex),
                ),
              )
            }

            // skippable하지 않거나, 기본 인자가 없거나 static하거나, $changed만 사용하는 경우
            else -> {
              // [defaultExpr 재실행 진행 로직] 만약 현재 매개변수에 인자가 제공되지 않았다면,
              // 현재 매개변수에 기본 인자를 제공함.
              setDefaultStatements.statements.add(
                irIf(
                  condition = irIsArgumentValueNotProvided(
                    defaultBitMaskValue = defaultBitMaskValue,
                    bitIndex = defaultBitIndex,
                  ),
                  body = irSet(variable = param, value = defaultValue),
                ),
              )
            }
          }
        }
      }
    }

    // #2
    // 리컴포지션 스킵을 지원하는 매개변수인지 조회하는 작업. 모든 매개변수를 순회하며
    // 하나라도 리컴포지션 스킵이 안되는 매개변수가 있다면 mightSkip를 false로 지정함.
    trackedParameters.fastForEachIndexed { slotIndex, param ->
      val stabilityOfParam = stabilityInferencer.stabilityOfType(type = param.varargElementType ?: param.type)

      trackedParamStabilities[slotIndex] = stabilityOfParam

      val isUsedParam = functionScope.usedParams[slotIndex]
      val isUnstableParam = stabilityOfParam.knownUnstable()

      functionScope.metrics.recordParameter(
        declaration = param,
        type = param.type,
        stability = stabilityOfParam,
        default = defaultExpr[slotIndex],
        defaultStatic = defaultExprIsStaticOrNone[slotIndex],
        used = isUsedParam,
      )

      // 강한 건너뛰기가 비활성되어 있고, 사용되는 매개변수이고,
      // 불안정한 타입의 매개변수이고, 기본 인자가 없다면
      if (
        !FeatureFlag.StrongSkipping.enabled &&
        isUsedParam &&
        isUnstableParam &&
        param.defaultValue == null
      ) {
        // if it is a used + unstable parameter with no default expression and we are
        // not in strong skipping mode, the fn will _never_ skip.
        //
        // 사용 중이며 불안정한 파라미터인데 기본 표현식이 없고, 강력한 스킵 모드가 아니라면
        // 해당 함수는 절대 스킵되지 않습니다.
        //
        // mightSkip 값이 바뀌는 유일한 공간
        mightSkip = false
      }
    }

    // we start the skipPreamble with all of the changed calls. These need to go at the top
    // of the function's group. Note that these end up getting called *before* default
    // expressions, but this is okay because it will only ever get called on parameters that
    // are provided to the function.
    //
    // skipPreamble은 모든 changed 호출로 시작합니다. 이 호출들은 함수 그룹의 최상단에 위치해야
    // 합니다. 이 호출들이 기본 표현식보다 먼저 실행되지만, 이는 문제가 되지 않습니다. 왜냐하면
    // 해당 호출은 오직 함수에 실제로 전달된 파라미터에 대해서만 수행되기 때문입니다.
    //
    //
    // #3
    // $changed와 $default에 따라 $dirty를 업데이트하는 작업
    trackedParameters.fastForEachIndexed { slotIndex, param ->
      // varargs get handled separately because they will require their own groups.
      // vararg 파라미터는 별도의 그룹이 필요하므로 따로 처리됩니다.
      if (param.isVararg) return@fastForEachIndexed

      val defaultBitIndex = functionScope.defaultBitIndexForParamIndex(index = slotIndex)
      val defaultValue = param.defaultValue

      val stabilityOfParam = trackedParamStabilities[slotIndex]
      val isUnstableParam = stabilityOfParam.knownUnstable()
      val isUsedParam = functionScope.usedParams[slotIndex]

      when {
        // 리컴포지션 스킵이 이미 불가능하거나, 현재 매개변수가 사용되지 않는다면
        !mightSkip || !isUsedParam -> {
          // nothing to do
        }

        // $dirty가 아니라면 ($changed라면)
        dirtyBitMaskValue !is IrChangedBitMaskVariable -> {
          // this will only ever be true when mightSkip is false, but we put this
          // branch here so that `dirty` gets smart cast in later branches.
          //
          // 이 조건은 mightSkip이 false일 때만 true가 되지만, 이 분기를 여기 두는 이유는
          // 이후 분기들에서 dirtyBitMaskValue가 $dirty로 스마트 캐스트되도록 하기 위함입니다.

          // skippable 하지 않다면 $dirty 없이 $changed만 사용함.
          // mightSkip의 초기값은 skippable을 따르고, 'mightSkip = true' 하는 로직은 없음.
          // 즉, "이 조건은 mightSkip이 false일 때만 true가 되지만"이 성립함.
        }

        // 강한 건너뛰기가 비활성되어 있고, 불안정한 타입의 매개변수이고,
        // $default가 있고, 기본 인자가 제공되었다면
        //
        // 컴포저블 람다는 $default가 없으므로 이 분기는 항상 건너뜀
        !FeatureFlag.StrongSkipping.enabled &&
          isUnstableParam &&
          defaultBitMaskValue != null &&
          defaultValue != null -> {
          // if it has a default parameter then the function can still potentially skip.
          // 기본 파라미터가 있는 경우, 해당 함수는 여전히 스킵될 가능성이 있습니다.
          //
          // [defaultExpr 재실행 스킵 로직] 만약 현재 매개변수에 인자가 제공되지 않았다면,
          // 현재 매개변수의 $dirty를 Same으로 설정함.
          //
          // MEMO 만약 현재 매개변수에 인자가 제공되지 않았다면, 기본 인자를 현재 매개변수에
          //  제공함. 즉, 만약 defaultExpr이 변하지 않았다면 기본 인자도 동일하기에 현재 매개변수는
          //  항상 Same임. 하지만 defaultExpr이 static한지 검사하는 로직 없이 바로 Same으로 넣음.
          //  ParamState에는 Same과 Static이 별도로 존재하는데, 이런 상황을 구분하는 듯?
          skipPreamble.statements.add(
            irIf(
              condition = irIsArgumentValueNotProvided(
                defaultBitMaskValue = defaultBitMaskValue,
                bitIndex = defaultBitIndex,
              ),
              body = dirtyBitMaskValue.irOrSetBitsAtSlot(
                slot = slotIndex,
                value = irIntConst(ParamState.Same /* 0b001 */.bitsForSlot(slot = slotIndex)),
              ),
            ),
          )
        }

        // 강한 건너뛰기가 활성화되어 있거나, 매개변수의 타입이 불안정하지 않다면
        FeatureFlag.StrongSkipping.enabled || !isUnstableParam -> {
          val defaultValueIsStaticOrNone = defaultExprIsStaticOrNone[slotIndex]
          val changedCall =
            irChanged(
              value = param,
              stabilityOfParam = stabilityOfParam,
              slotIndex = slotIndex,
              changedBitMaskValue = changedBitMaskValue,
            )

          val isChanged =
            // 만약 $default가 있고(컴포저블 람다는 항상 없음), 기본 인자가 있으며 static하지 않고
            if (defaultBitMaskValue != null && !defaultValueIsStaticOrNone)
              irAndAnd(
                // 현재 매개변수에 인자 값이 제공됐다면 (=> defaultExpr가 사용되지 않았다면)
                lhs = irIsArgumentValueProvided(
                  defaultBitMaskValue = defaultBitMaskValue,
                  bitIndex = defaultBitIndex,
                ),
                // composer.changed(param)
                rhs = changedCall,
              )
            else
              changedCall

          val modifyDirtyFromChangedStatement =
            dirtyBitMaskValue.irOrSetBitsAtSlot(
              slot = slotIndex,
              value = irIfThenElse(
                type = context.irBuiltIns.intType,
                condition = isChanged,
                // if the value has changed, update the bits in the slot to be "Different"
                // 값이 변경된 경우, $dirty 슬롯의 비트를 "Different"로 업데이트합니다.
                thenPart = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = slotIndex)),
                // if the value has not changed, update the bits in the slot to be "Same"
                // 값이 변경되지 않은 경우, $dirty 슬롯의 비트를 "Same"으로 업데이트합니다.
                elsePart = irIntConst(ParamState.Same /* 0b001 */.bitsForSlot(slot = slotIndex)),
              ),
            )

          val skipConditionByChangedBitMask =
            if (FeatureFlag.StrongSkipping.enabled)
            // 강한 건너뛰기가 활성화되어 있다면 unknown 상황에서도 인스턴스 비교함
              irIsUncertainOrUnknown(
                changedBitMaskValue = changedBitMaskValue,
                slot = slotIndex,
              )
            else
              irIsStableUncertain(
                changedBitMaskValue = changedBitMaskValue,
                slot = slotIndex,
              )

          val dirtyUpdateStatement =
            // 만약 $default가 있고(컴포저블 람다는 항상 없음), 기본 인자가 없거나 static하다면
            if (defaultBitMaskValue != null && defaultValueIsStaticOrNone) {
              // if the default expression is "static", then we know that if we are using the
              // default expression, the parameter can be considered "static".
              //
              // 기본 표현식이 "static"인 경우, 해당 기본 표현식을 사용하고 있다면 해당 파라미터
              // 역시 "static"으로 간주할 수 있습니다.
              irWhen(
                origin = IrStatementOrigin.IF,
                branches = listOf(
                  irBranch(
                    // 매개변수에 인자가 제공되지 않았다면 (기본 인자를 사용한다면)
                    condition = irIsArgumentValueNotProvided(
                      defaultBitMaskValue = defaultBitMaskValue,
                      bitIndex = defaultBitIndex,
                    ),
                    result = dirtyBitMaskValue.irOrSetBitsAtSlot(
                      slot = slotIndex,
                      value = irIntConst(ParamState.Static /* 0b011 */.bitsForSlot(slot = slotIndex)),
                    )
                  ),
                  irBranch(
                    condition = skipConditionByChangedBitMask,
                    result = modifyDirtyFromChangedStatement,
                  ),
                ),
              )
            }

            // 만약 $default가 없거나(컴포저블 람다), 기본 인자가 있으며 static하지 않다면
            else {
              // we only call `$composer.changed(...)` on a parameter if the value came in
              // with an "Uncertain" state AND the value was provided. This is safe to do
              // because this will remain true or false for *every* execution of the
              // function, so we will never get a slot table misalignment as a result.
              //
              // 파라미터의 값이 "Uncertain" 상태로 전달되었고 실제로 값이 제공된 경우에만
              // $composer.changed(...)를 호출합니다. 이 방식은 함수의 모든 실행에서 결과가
              // 항상 true 또는 false로 일관되게 유지되기 때문에, 슬롯 테이블의 불일치가 발생할
              // 위험 없이 안전하게 사용할 수 있습니다.
              irIf(
                condition = skipConditionByChangedBitMask,
                body = modifyDirtyFromChangedStatement,
              )
            }

          skipPreamble.statements.add(dirtyUpdateStatement)
        }
      }
    }

    // now we handle the vararg parameters specially since it needs to create a group
    // 이제 vararg 파라미터를 별도로 처리합니다. 이 파라미터는 그룹을 생성해야 하기 때문입니다.
    //
    // #3-번외
    // vararg의 모든 요소를 순회하며 하나라도 Different인 요소가 없다면, 해당 매개변수의
    // $dirty를 Same으로 지정하는 로직
    trackedParameters.fastForEachIndexed { slotIndex, param ->
      val varargType = param.varargElementType ?: return@fastForEachIndexed
      val varargTypeIsStable = stabilityInferencer.stabilityOfType(type = varargType)

      // 현재까지는 리컴포지션 스킵이 가능하고, $dirty가 있을 때
      if (mightSkip && dirtyBitMaskValue is IrChangedBitMaskVariable) {
        // for vararg parameters of stable type, we can store each value in the slot
        // table, but need to generate a group since the size of the array could change
        // over time. In the future, we may want to make an optimization where whether or
        // not the call site had a spread or not and only create groups if it did.
        //
        // 안정적인 타입의 vararg 파라미터의 경우, 각 값을 슬롯 테이블에 저장할 수 있습니다.
        // 하지만 배열의 크기가 시간이 지나면서 변경될 수 있기 때문에 그룹을 생성해야 합니다.
        // 향후에는 호출 지점에서 spread가 사용되었는지 여부를 기반으로, spread가 사용된 경우에만
        // 그룹을 생성하는 최적화를 고려할 수 있습니다.

        // for varargs with default type, check if $default is set for that parameter.
        // 기본값이 있는 vararg 파라미터의 경우, 해당 파라미터에 $default가 설정되어 있는지 확인합니다.
        val skipStatements =
          // $default가 있고(컴포저블 람다는 항상 없음), 현재 매개변수에 기본 인자가 있다면
          if (defaultBitMaskValue != null && param.defaultValue != null) {
            val defaultBitIndex = functionScope.defaultBitIndexForParamIndex(index = slotIndex)
            val block = irBlock(statements = emptyList())

            skipPreamble.statements.add(
              irIf(
                // 현재 매개변수에 인자 값이 제공됐다면
                //
                // 현재 매개변수에 기본 인자가 있지만, 실제 인자 값도 제공됐을 때만
                // skipStatements를 실행함.
                condition = irIsArgumentValueProvided(
                  defaultBitMaskValue = defaultBitMaskValue,
                  bitIndex = defaultBitIndex,
                ),
                body = block,
              ),
            )

            block.statements
          } else {
            skipPreamble.statements
          }

        val vararySizeGetter = param.type.classOrNull!!.getPropertyGetter("size")!!.owner

        // composer.startMovableGroup(key = ..., dataKey = values.size)
        skipStatements.add(
          // STUDY 왜 movable group이지??
          irStartMovableGroup(
            element = param,
            joinedData = irMethodCall(target = irGet(param), function = vararySizeGetter),
            scope = defaultParamScope,
          ),
        )

        // $dirty = if (composer.changed(values.length)) 0b010_0 else 0b000_0
        skipStatements.add(
          dirtyBitMaskValue.irOrSetBitsAtSlot(
            slot = slotIndex,
            value = irIfThenElse(
              type = context.irBuiltIns.intType,
              condition = irChanged(
                value = irMethodCall(target = irGet(param), function = vararySizeGetter),
                compareInstanceForFunctionTypes = true,
              ),
              // 사이즈가 달라졌으니 Different로 설정함
              thenPart = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = slotIndex)),
              // 사이즈만 동일한 거고, 내용이 달라졌을 수 있으니 Uncertain으로 설정함
              elsePart = irIntConst(ParamState.Uncertain /* 0b000 */.bitsForSlot(slot = slotIndex)),
            ),
          ),
        )

        // for (value in values) {
        //   $dirty = $dirty or if (composer.changed(value)) 0b010_0 else 0b000_0
        // }
        skipStatements.add(
          irWhileLoop(elementType = varargType, subject = irGet(param)) { varargElement ->
            val changedCall =
              irChanged(
                value = varargElement,
                stabilityOfParam = varargTypeIsStable,
                slotIndex = slotIndex,
                changedBitMaskValue = changedBitMaskValue,
              )

            dirtyBitMaskValue.irOrSetBitsAtSlot(
              slot = slotIndex,
              value = irIfThenElse(
                type = context.irBuiltIns.intType,
                condition = changedCall,
                // if the value has changed, update the bits in the slot to be "Different".
                // 값이 변경된 경우, 슬롯의 비트를 "Different"로 업데이트합니다.
                thenPart = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = slotIndex)),
                // if the value has not changed, we are still uncertain if the entire
                // list of values has gone unchanged or not, so we use Uncertain.
                //
                // 값이 변경되지 않았더라도 전체 값 목록이 변하지 않았는지는 여전히
                // 확실하지 않으므로, "Uncertain"으로 표시합니다.
                elsePart = irIntConst(ParamState.Uncertain /* 0b000 */.bitsForSlot(slot = slotIndex))
              )
            )
          },
        )

        // composer.endMovableGroup()
        skipStatements.add(irEndMovableGroup(scope = functionScope))

        // if ($dirty and 0b111_0 == 0) {
        //   $dirty = $dirty or 0b001_0
        // }
        skipStatements.add(
          irIf(
            condition = irIsStableUncertain(
              changedBitMaskValue = dirtyBitMaskValue,
              slot = slotIndex,
            ),
            body = dirtyBitMaskValue.irOrSetBitsAtSlot(
              slot = slotIndex,
              value = irIntConst(ParamState.Same /* 0b001 */.bitsForSlot(slotIndex)),
            ),
          ),
        )
      }
    }

    // #4 모든 trackedParameter의 기본 인자 제거
    trackedParameters.fastForEach { param ->
      // we want to remove the default expression from the function. This will prevent
      // the kotlin compiler from doing its own default handling, which we don't need.
      //
      // 기본 표현식을 함수에서 제거하려고 합니다. 이렇게 하면 Kotlin 컴파일러가 자체적으로
      // 기본값 처리를 수행하지 않게 되며, 이는 우리가 필요로 하지 않는 동작입니다.
      param.defaultValue = null
    }

    // after all of this, we need to potentially wrap the default setters in a group and if
    // statement, to make sure that defaults are only executed when they need to be.
    //
    // 지금까지의 모든 작업 이후에는, 기본값 설정 코드를 그룹과 if문으로 감싸야 합니다.
    // 이 작업으로 기본값이 실제로 필요한 경우에만 실행되도록 보장합니다.
    //
    // #5-1
    // 리컴포지션을 건너뛸 수 없거나, 모든 기본 인자값이 static하다면
    if (!mightSkip || defaultExprIsStaticOrNone.all { it }) {
      // if we don't skip execution ever, then we don't need these groups at all.
      // Additionally, if all of the defaults are static, we can avoid creating the groups
      // as well.
      // NOTE(lmr): should we still wrap this in an if statement to be safe???
      //
      // 함수가 절대 스킵되지 않는다면 이러한 그룹들을 전혀 생성할 필요가 없습니다.
      // 또한, 모든 기본값이 static하다면 마찬가지로 그룹 생성을 생략할 수 있습니다.
      // 그래도 안전을 위해 if 문으로 감싸는 것이 좋을까요?
      bodyPreamble.statements.addAll(setDefaultStatements.statements)

      // skipDefaultStatements는 사용하지 않음
    }

    // otherwise, we wrap the whole thing in an if expression with a skip.
    // 그렇지 않은 경우, 전체 코드를 스킵 가능한 if문으로 감쌉니다.
    // + default group도 시작함
    //
    // #5-2
    // - 리컴포지션을 건너뛸 수 있거나, 모든 기본 인자값이 static하지 않고,
    // - 매개변수에 기본값을 지정하는 로직이 비어있지 않다면 (기본 인자가 하나라도 있다면)
    else if (setDefaultStatements.statements.isNotEmpty()) {
      functionScope.hasDefaultsGroup = true
      functionScope.metrics.recordGroup()

      bodyPreamble.statements.add(irStartDefaults(element = sourceElement, scope = defaultParamScope))
      bodyPreamble.statements.add(
        // this prevents us from re-executing the defaults if this function is getting
        // executed from a recomposition.
        //
        // 이렇게 하면 함수가 리컴포지션될 때 defaultExpr이 재실행되는 걸 막을 수 있습니다.
        //
        // MEMO updateScope로 리컴포지션될 때는 내부 상태가 변경된 상황이고, LSB가 1임.
        //  이때는 내부 상태만 변경된 상황이므로 굳이 컴포저블의 인자까지 다시 계산하지
        //  않아도 됨. 만약 LSB가 0인데 리컴포지션됐다면 컴포저블 인자가 변경됐을 수 있으므로
        //  인자 재계산이 필요함.
        irIfThenElse(
          // if (%changed and 0b000_1 == 0 || %composer.defaultsInvalid) {
          condition = irOrOr(
            lhs = irEqual(lhs = changedBitMaskValue.irGetLowBit(), rhs = irIntConst(0)),
            rhs = irIsDefaultsInvalid(),
          ),
          // set all of the default temp vars
          thenPart = setDefaultStatements,
          // composer.skipCurrentGroup()
          elsePart = irBlock(
            statements = listOf(
              irSkipToGroupEnd(),
              *skipDefaultStatements.statements.toTypedArray(),
            ),
          ),
        ),
      )
      bodyPreamble.statements.add(irEndDefaults())
    }

    return mightSkip
  }

  private fun buildChangedArgumentsForCall(
    contextArgs: List<CallArgumentMeta>,
    valueArgs: List<CallArgumentMeta>,
    extensionArg: CallArgumentMeta?,
    dispatchArg: CallArgumentMeta?,
  ): List<IrExpression> {
    val allArgs =
      contextArgs +
        listOfNotNull(extensionArg) +
        valueArgs +
        listOfNotNull(dispatchArg)

    // passing in 0 for thisParams since they should be included in the params list.
    // thisParams는 params 목록에 포함되어야 하므로 0을 전달합니다.
    val changedCount = changedParamCount(realValueParamCount = allArgs.size, thisParamCount = 0)
    val result = mutableListOf<IrExpression>()

    for (i in 0 until changedCount) {
      val start = i * SLOTS_COUNT_PER_INT
      val end = min(start + SLOTS_COUNT_PER_INT, allArgs.size)
      result.add(buildChangedArgumentForCall(arguments = allArgs.subList(start, end)))
    }

    return result
  }

  // MEMO $changed 파라미터 값 구하는 로직
  private fun buildChangedArgumentForCall(arguments: List<CallArgumentMeta>): IrExpression {
    // The general pattern here is:
    //
    // $changed = bitMaskConstant or
    //            (0b11 and someMask shl x) or
    //            (0b1100 and someMask shl y) or
    //            ...
    //            (0b11000000 and someMask shr z)
    //
    // where `bitMaskConstant` is created in this function based on
    // all of the static (constant) params and uncertain params (not direct parameter pass
    // throughs). The other params have had their state made "certain" by the preamble checks
    // in a composable function in scope. We can extract that state directly by pulling out
    // the specific slot state from that function's dirty parameter (represented as
    // `someMask` here, and then shifting the resulting bit mask over to the correct slot
    // (the shift amount represented here by `x`, `y`, and `z`).
    //
    //
    // 일반적인 패턴은 다음과 같습니다:
    //
    // $changed = bitMaskConstant or
    //            (0b110 and someMask shl x) or
    //            (0b1100 and someMask shl y) or
    //            ...
    //            (0b11000000 and someMask shr z)
    //
    // 여기서 bitMaskConstant는 이 함수에서 생성되며, 모든 정적인(상수) 파라미터와 불확실한
    // 파라미터(직접적인 파라미터 전달이 아닌 것들)를 기반으로 합니다. 나머지 파라미터들은
    // 컴포저블 함수의 preamble 체크를 통해 상태가 "확실"하게 되었으며, 그 상태는 해당 함수의
    // $dirty 파라미터(someMask로 표현됨)로부터 직접 추출할 수 있습니다. 그런 다음 해당 비트
    // 마스크를 올바른 슬롯으로 시프트하여 상태를 맞춥니다(x, y, z가 시프트 양을 나타냅니다).

    // TODO we could make some small optimization here if we have multiple values passed
    //  from one function into another in the same order. This may not happen commonly enough
    //  to be worth the complication though.
    //
    // TODO 여러 개의 값이 동일한 순서로 한 함수에서 다른 함수로 전달되는 경우, 여기서 약간의
    //  최적화를 할 수 있습니다. 하지만 이런 경우가 자주 발생하지는 않을 수 있어, 복잡성을
    //  감수할 가치가 없을 수도 있습니다.

    // NOTE: we start with 0b0 because it is important that the low bit is always 0.
    //       가장 낮은 비트가 항상 0이어야 하기에 0b0으로 시작합니다.
    var bitMaskConstant = 0b0
    val orExprs = mutableListOf<IrExpression>()

    arguments.fastForEachIndexed { slotIndex, argInfo ->
      val stabilityOfExpr = argInfo.stabilityOfExpr

      // 1. 인자 표현식의 안정성 정보를 $changed의 각 슬롯에 넣는 작업
      when {
        // 강력한 건너뛰기가 비활성화되었고, 인자 표현식이 불안정하다면
        !FeatureFlag.StrongSkipping.enabled && stabilityOfExpr.knownUnstable() -> {
          bitMaskConstant = bitMaskConstant or StabilityBits.UNSTABLE /* Unknown(0b100) */.bitsForSlot(slot = slotIndex)

          // If it is known to be unstable, there's no purpose in propagating any
          // additional metadata _for this parameter_, but we still want to propagate
          // the other parameters.
          //
          // 알려진 정보가 안정적이지 않은 경우, 이 매개변수에 대해서는 추가 메타데이터를
          // 전파할 필요는 없지만, 다른 매개변수에 대해서는 여전히 전파해야 합니다.
          return@fastForEachIndexed
        }

        // 인자 표현식이 안정하다면
        stabilityOfExpr.knownStable() -> {
          bitMaskConstant = bitMaskConstant or StabilityBits.STABLE /* Uncertain(0b000) */.bitsForSlot(slot = slotIndex)
        }

        // 인자 표현식이 안정하지 않다면
        else -> {
          val stabilityExpression =
            stabilityOfExpr.irStabilityBitsExpression(resolveTypeParameter = ::irTypeParameterStability)

          if (stabilityExpression != null) {
            val expr =
              if (slotIndex == 0) stabilityExpression
              else {
                val int = context.irBuiltIns.intType
                val bitsToShiftLeft = slotIndex * BITS_COUNT_PER_SLOT

                irCall(
                  symbol = int.binaryOperator(name = OperatorNameConventions.SHL, paramType = int),
                  origin = null,
                  dispatchReceiver = stabilityExpression,
                  extensionReceiver = null,
                  /*args = */ irIntConst(bitsToShiftLeft),
                )
              }

            orExprs.add(expr)
          }
        }
      }

      // 2. 인자의 메타 정보를 $changed의 각 슬롯에 넣는 작업
      //   + referencedParam의 dirty를 내 $changed에 넣는 로직
      when {
        argInfo.isVararg -> {
          bitMaskConstant = bitMaskConstant or ParamState.Uncertain /* 0b000 */.bitsForSlot(slot = slotIndex)
        }

        !argInfo.hasDefaultValue -> {
          bitMaskConstant = bitMaskConstant or ParamState.Uncertain /* 0b000 */.bitsForSlot(slot = slotIndex)
        }

        argInfo.isStatic -> {
          bitMaskConstant = bitMaskConstant or ParamState.Static /* 0b011 */.bitsForSlot(slot = slotIndex)
        }

        !argInfo.isReferenced -> {
          bitMaskConstant = bitMaskConstant or ParamState.Uncertain /* 0b000 */.bitsForSlot(slot = slotIndex)
        }

        // 인자가 가변하지 않고, 인자에 기본값이 있고,
        // 인자 표현식이 static하지 않고, 상위 함수의 매개변수를 레퍼런스한다면
        //   -> 상위 함수의 dirty를 나에게 전달
        else -> {
          val referencedParam = argInfo.referencedParam!! // argInfo.isReferenced == true 라면 항상 존재함
          val referencedDirty = referencedParam.dirty ?: error($$"$changed or $dirty is required if param is Certain")
          val referencedSlotIndex = referencedParam.slotIndex

          require(referencedSlotIndex != -1) { "invalid parent slotIndex for Certain param" }

          // if parentSlot is lower than current slotIndex, we shift left a positive amount of bits.
          // parentSlot이 현재 slot보다 작으면 비트를 왼쪽으로 양수만큼 시프트합니다.
          orExprs.add(
            irAnd(
              lhs = irIntConst(ParamState.Mask /* 0b111 */.bitsForSlot(slot = slotIndex)),
              rhs = referencedDirty.irShiftBits(fromSlot = referencedSlotIndex, toSlot = slotIndex),
            ),
          )
        }
      }
    }

    return when {
      // if there are no orExprs, then we can just use the constant.
      // orExpr가 없다면 상수만 사용하면 됩니다.
      orExprs.isEmpty() -> irIntConst(bitMaskConstant)

      // if the constant is still 0, then we can just use the or expressions. This is safe
      // because the low bit will still be 0 regardless of the result of the or expressions.
      //
      // 상수가 여전히 0b0이라면 or 표현식만 사용해도 됩니다. 이는 or 표현식의 결과와 관계없이
      // 가장 낮은 비트가 여전히 0이기 때문에 안전합니다.
      bitMaskConstant == 0b0 -> orExprs.reduce { lhs, rhs -> irIntOr(lhs = lhs, rhs = rhs) }

      // otherwise, we do (bitMaskConstant or a or b ... or z)
      else -> orExprs.fold<IrExpression, IrExpression>(irIntConst(bitMaskConstant)) { lhs, rhs ->
        irIntOr(lhs = lhs, rhs = rhs)
      }
    }
  }

  private fun IrExpression.endsWithReturnOrJump(): Boolean {
    var expr: IrStatement? = this
    while (expr != null) {
      if (expr is IrReturn) return true
      if (expr is IrBreakContinue) return true

      if (expr !is IrBlock) return false

      expr = expr.statements.lastOrNull()
    }
    return false
  }

  private fun IrContainerExpression.wrapWithTraceEvents(key: IrExpression, scope: Scope.FunctionScope) {
    val start = irTraceEventStart(key = key, scope = scope)
    val end = irTraceEventEnd()

    if (start != null && end != null) {
      statements.add(0, start)
      statements.add(end)
    }
  }

  private fun IrBody.asBodyAndResultVar(expectedTarget: IrFunction? = null): Pair<IrContainerExpression, IrVariable?> {
    val original = IrCompositeImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = context.irBuiltIns.unitType,
      origin = null,
      statements = statements,
    )
    var block: IrStatementContainer? = original
    var lastStatement: IrStatement? = block?.statements?.lastOrNull()

    while (lastStatement != null && block != null) {
      if (
        lastStatement is IrReturn &&
        (expectedTarget == null || expectedTarget == lastStatement.returnTargetSymbol.owner)
      ) {
        block.statements.pop()

        val valueType = lastStatement.value.type
        val returnType = (lastStatement.returnTargetSymbol as? IrFunctionSymbol)?.owner?.returnType ?: valueType

        return if (returnType.isUnit() || returnType.isNothing() || valueType.isNothing()) {
          block.statements.add(lastStatement.value)
          original to null
        } else {
          val temp = irTemporary(lastStatement.value)
          block.statements.add(temp)
          original to temp
        }
      }

      if (lastStatement !is IrBlock)
        return original to null

      block = lastStatement
      lastStatement = block.statements.lastOrNull()
    }

    return original to null
  }

  private fun nearestComposer(): IrValueParameter = currentScope.myComposer

  // MEMO 강한 건너뛰기 활성화 여부에 따라 changed() 함수 선택이 달라짐
  private fun irChanged(
    value: IrValueDeclaration,
    stabilityOfParam: Stability,
    slotIndex: Int, // param 실제 값에 기반한 stable 여부 파악에만 쓰임
    changedBitMaskValue: IrChangedBitMaskValue, // param 실제 값에 기반한 stable 여부 파악에만 쓰임
  ): IrExpression =
    // 강한 건너뛰기가 활성화되어 있고, 매개변수 타입의 안정성이 Uncertain 하다면
    if (FeatureFlag.StrongSkipping.enabled && stabilityOfParam.isUncertain()) {
      irIfThenElse(
        type = context.irBuiltIns.booleanType,
        // 만약 매개변수에 제공된 인자 값($changed or $dirty)이 안정하다면
        condition = irIsStable(
          changedBitMaskValue = changedBitMaskValue,
          slot = slotIndex,
        ),
        thenPart = irChanged(
          currentComposer = irCurrentComposer(),
          value = irGet(value),
          inferredStable = true,
          compareInstanceForFunctionTypes = true,
          compareInstanceForUnstableValues = true,
        ),
        elsePart = irChanged(
          currentComposer = irCurrentComposer(),
          value = irGet(value),
          inferredStable = false,
          compareInstanceForFunctionTypes = true,
          compareInstanceForUnstableValues = true,
        )
      )
    }

    // 강한 건너뛰기가 활성화되지 않았거나, 매개변수 타입의 안정성이 Uncertain 하지 않다면
    else {
      irChanged(
        value = irGet(value),
        compareInstanceForFunctionTypes = true,
      )
    }

  private fun irEndRestartGroupAndUpdateScope(
    scope: Scope.FunctionScope,
    changedParam: IrChangedBitMaskValue,
    defaultParam: IrDefaultBitMaskValue?,
    realValueParamCount: Int,
  ): IrExpression {
    val function = scope.function

    // Save the dispatch receiver into a temporary created in
    // the outer scope because direct references to the
    // receiver sometimes cause an invalid name, "$<this>", to
    // be generated.
    //
    // 디스패치 리시버를 외부 스코프에 생성된 임시 변수에 저장합니다.
    // 이는 리시버를 직접 참조할 경우 간혹 "$<this>"와 같은 잘못된
    // 이름이 생성되는 문제를 방지하기 위함입니다.
    val dispatchReceiverParameter = function.dispatchReceiverParameter
    val dispatchReceiverCopy =
      if (dispatchReceiverParameter != null)
        irTemporary(value = irGet(dispatchReceiverParameter), nameHint = "rcvr")
      else
        null

    // $composer, $changed, $default 포함
    val valueParameterCount = function.valueParameters.size
    val contextParameterCount = function.contextReceiverParametersCount

    val composerParamIndex = contextParameterCount + realValueParamCount
    val changedParamIndex = composerParamIndex + 1
    val defaultParamIndex =
      changedParamIndex + changedParamCount(
        realValueParamCount = realValueParamCount,
        thisParamCount = function.thisParamCount,
      )

    if (defaultParam == null) {
      // param count is 1-based, index is 0-based
      require(valueParameterCount == defaultParamIndex) {
        "Expected $defaultParamIndex params for ${function.fqNameWhenAvailable}, " +
          "found $valueParameterCount"
      }
    } else {
      val expectedParamCount =
        defaultParamIndex + defaultParamCount(valueParamCount = contextParameterCount + realValueParamCount)

      require(valueParameterCount == expectedParamCount) {
        "Expected $expectedParamCount params for ${function.fqNameWhenAvailable}, " +
          "found $valueParameterCount"
      }
    }

    // Create self-invoke lambda
    val lambda = irLambdaExpression(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      returnType = builtIns.unitType,
    ) { fn ->
      fn.parent = function

      val newComposer = fn.addValueParameter(
        name = ComposeNames.COMPOSER_PARAMETER.identifier,
        type = composerIrClass.defaultType
          .replaceArgumentsWithStarProjections() // STUDY Composer는 타입 매개변수가 없는 걸??
          .makeNullable(), // STUDY composer가 왜 nullable일까? 런타임에서는 항상 NotNull로 내려줌.
      )

      // MEMO 런타임에서 0b1(== force recomposition flag) 상수로 내려주는 파라미터.
      //  실제로 쓰이진 않음.
      fn.addValueParameter(name = ComposeNames.FORCE_PARAMETER, type = builtIns.intType)

      fn.body = DeclarationIrBuilder(context, fn.symbol).irBlockBody {
        // Call the function again with the same parameters
        +irReturn(
          irCall(function.symbol).apply irCall@{
            symbol.owner
              .valueParameters
              .fastForEach { param ->
                this@irCall.arguments[param.indexInParameters] = irGet(param)
              }

            // new composer
            putValueArgument(composerParamIndex, irGet(newComposer))

            // the call in updateScope needs to *always* have the low bit set to 1.
            // This ensures that the body of the function is actually executed.
            //
            // updateScope에서 호출되는 함수의 인자는 반드시 항상 하위 비트(low bit)를
            // 1로 설정해야 합니다. 이렇게 해야 함수 본문이 실제로 실행되도록 보장할 수 있습니다.
            // => 강제로 리컴포지션하는 LSB 비트 플래그!!!
            changedParam.putAsValueArgumentInWithLowBit(
              fn = this@irCall,
              paramIndex = changedParamIndex,
              lowBit = true,
            )

            defaultParam?.putAsValueArgumentIn(
              fn = this@irCall,
              paramIndex = defaultParamIndex,
            )

            this@irCall.extensionReceiver = function.extensionReceiverParameter?.let(::irGet)
            this@irCall.dispatchReceiver = dispatchReceiverCopy?.let(::irGet)

            function.typeParameters.fastForEachIndexed { index, parameter ->
              this@irCall.typeArguments[index] = parameter.defaultType
            }
          }
        )
      }
    }

    // $composer.endRestartGroup()?.updateScope { composer, _ -> MyFunction(..., composer) }
    //                                                      ^ 이 인자는 $force인데, 항상 0b1로 전달됨
    return irBlock(
      statements = listOfNotNull(
        dispatchReceiverCopy,
        irSafeCall(
          dispatchReceiver = irEndRestartGroup(scope = scope),
          callSymbol = updateScopeFunction.symbol,
          lambda, // arguments
        ),
      )
    )
  }

  fun irCurrentMarker(composerParameter: IrValueParameter): IrCall =
    irMethodCall(
      target = irCurrentComposer(composerParameter = composerParameter),
      function = currentMarkerProperty!!.getter!!,
    )

  private fun irIsSkipping(): IrCall =
    irMethodCall(target = irCurrentComposer(), function = isSkippingProperty.getter!!)

  private fun irShouldExecute(parametersChanged: IrExpression, flags: IrExpression): IrExpression {
    val shouldExecuteFunction = shouldExecuteFunction

    return if (shouldExecuteFunction != null) {
      irMethodCall(
        target = irCurrentComposer(),
        function = shouldExecuteFunction,
      ).apply {
        putValueArgument(0, parametersChanged)
        putValueArgument(1, flags)
      }
    } else {
      irOrOr(
        lhs = parametersChanged,
        rhs = irNot(irIsSkipping()),
      )
    }
  }

  private fun irIsDefaultsInvalid(): IrCall =
    irMethodCall(
      target = irCurrentComposer(),
      function = defaultsInvalidProperty.getter!!,
    )

  private fun irIsArgumentValueProvided(defaultBitMaskValue: IrDefaultBitMaskValue, bitIndex: Int): IrExpression =
    irEqual(
      lhs = defaultBitMaskValue.irGetBitAtIndex(index = bitIndex),
      rhs = irIntConst(0),
    )

  private fun irIsUncertainOrUnknown(changedBitMaskValue: IrChangedBitMaskValue, slot: Int): IrExpression =
  // $changed and [Same(0b001) or Different(0b010) or Static(0b011)] 해서 0이면
    // Uncertain(0b000) 이거나 Unknown(0b100) 임
    irEqual(
      lhs = changedBitMaskValue.irIsolateBitsAtSlot(slot = slot, includeStableBit = false),
      rhs = irIntConst(0),
    )

  private fun irIsStableUncertain(changedBitMaskValue: IrChangedBitMaskValue, slot: Int): IrExpression =
  // $changed and [Same(0b001) or Different(0b010) or Static(0b011) or Unknown(0b100)](= Mask(0b111)) 해서
    // 0이면 Uncertain(0b000) 임
    irEqual(
      lhs = changedBitMaskValue.irIsolateBitsAtSlot(slot = slot, includeStableBit = true),
      rhs = irIntConst(0),
    )

  private fun irIsStable(changedBitMaskValue: IrChangedBitMaskValue, slot: Int): IrExpression =
    irEqual(
      lhs = changedBitMaskValue.irStableBitAtSlot(slot = slot),
      rhs = irIntConst(0),
    )

  private fun irBitsForSlot(bits: Int, slot: Int): IrExpression =
    irIntConst(bitsForSlot(bits = bits, slotIndex = slot))

  private fun irCurrentComposer(
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
    composerParameter: IrValueParameter = nearestComposer(),
  ): IrExpression =
    IrGetValueImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      symbol = composerParameter.symbol,
    )

  private fun Scope.BlockScope.irCurrentComposer(
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrExpression =
    irCurrentComposer(
      startOffset = startOffset,
      endOffset = endOffset,
      composerParameter = nearestComposer ?: nearestComposer(),
    )

  private fun functionSourceKey(function: IrFunction): Int =
    when (function) {
      is IrSimpleFunction -> function.sourceKey()
      is IrConstructor -> error("expected simple function, but got constructor")
    }

  private fun IrElement.sourceKey(): Int {
    var hash = functionSourceKey(currentFunctionScope.function)
    hash = 31 * hash + (startOffset - currentFunctionScope.function.startOffset)
    hash = 31 * hash + (endOffset - currentFunctionScope.function.startOffset)

    when (this) {
      // Disambiguate ?. clauses which become a "null" constant expression.
      // ?. 절이 "null" 상수 표현식으로 바뀌는 경우, 이를 명확하게 구분합니다.
      is IrConst -> {
        hash = 31 * hash + (this.value?.hashCode() ?: 1)
      }
      // Disambiguate the key for blocks and composite containers in case block offsets are
      // the same as its contents.
      //
      // 블록 오프셋이 그 내부 콘텐츠와 동일한 경우를 대비해, 블록과 복합 컨테이너의 키를
      // 명확히 구분합니다.
      is IrBlock -> {
        hash = 31 * hash + 2
      }
      is IrComposite -> {
        hash = 31 * hash + 3
      }
    }

    return hash
  }

  private fun IrElement.irSourceKey(): IrConst =
    IrConstImpl.int(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.intType,
      value = sourceKey(),
    )

  private fun irFunctionSourceKey(function: IrFunction = currentFunctionScope.function): IrConst =
    IrConstImpl.int(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.intType,
      value = functionSourceKey(function),
    )

  private fun irStartReplaceGroup(
    element: IrElement,
    scope: Scope.BlockScope,
    key: IrExpression = element.irSourceKey(),
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrExpression =
    irWithSourceInformation(
      startGroup = irStartReplaceGroup(
        currentComposer = scope.irCurrentComposer(startOffset, endOffset),
        key = key,
        startOffset = startOffset,
        endOffset = endOffset
      ),
      scope = scope,
    )

  private fun irWithSourceInformation(
    startGroup: IrExpression,
    scope: Scope.BlockScope,
  ): IrExpression =
    if (collectSourceInformation && scope.hasSourceInformation) {
      irBlock(statements = listOf(startGroup, irSourceInformation(scope = scope)))
    } else {
      startGroup
    }

  private fun irSourceInformation(scope: Scope.BlockScope): IrExpression {
    val sourceInformation =
      irCall(function = sourceInformationFunction).also {
        it.putValueArgument(0, scope.irCurrentComposer())
      }
    recordSourceParameter(call = sourceInformation, index = 1, scope = scope)

    return sourceInformation
  }

  private fun irSourceInformationMarkerStart(
    element: IrElement,
    scope: Scope.BlockScope,
    key: IrExpression = element.irSourceKey(),
  ): IrExpression =
    irCall(
      function = sourceInformationMarkerStartFunction,
      startOffset = element.startOffset,
      endOffset = element.endOffset,
    ).also {
      it.putValueArgument(0, scope.irCurrentComposer())
      it.putValueArgument(1, key)
      recordSourceParameter(call = it, index = 2, scope = scope)
    }

  private fun irSourceInformationMarkerEnd(
    element: IrElement,
    scope: Scope.BlockScope,
  ): IrExpression =
    irCall(
      function = sourceInformationMarkerEndFunction,
      startOffset = element.startOffset,
      endOffset = element.endOffset,
    ).also {
      it.putValueArgument(0, scope.irCurrentComposer())
    }

  // 원래 이름: irWithSourceInformationMarker
  private fun irWrapWithSourceInformationMarkerIfNeeded(
    expression: IrExpression,
    scope: Scope.BlockScope,
    before: List<IrStatement>,
  ): IrExpression =
    if (collectSourceInformation && scope.hasSourceInformation) {
      expression.wrap(
        before = before + listOf(irSourceInformationMarkerStart(element = expression, scope = scope)),
        after = listOf(irSourceInformationMarkerEnd(element = expression, scope = scope)),
      )
    } else if (before.isNotEmpty()) {
      expression.wrap(before = before)
    } else {
      expression
    }

  private fun irIsTraceInProgress(): IrExpression? =
    isTraceInProgressFunction?.let(::irCall)

  private fun irIfTraceInProgress(body: IrExpression): IrExpression? =
    irIsTraceInProgress()?.let { isTraceInProgress ->
      irIf(condition = isTraceInProgress, body = body)
    }

  private fun irTraceEventStart(key: IrExpression, scope: Scope.FunctionScope): IrExpression? {
    val traceEventStartFunction = traceEventStartFunction ?: return null

    val declaration = scope.function
    val startOffset = declaration.body!!.startOffset

    val fqName = declaration.kotlinFqName
    val fileName = declaration.file.name

    // FIXME: This should probably use `declaration.startOffset`, but the K2 implementation
    //        is unfinished (i.e., in K2 the start offset of an annotated function could
    //        point at the annotation instead of the start of the function).
    //
    // FIXME: 아마도 declaration.startOffset을 사용하는 것이 적절하지만, K2 구현이 아직 완료되지
    //        않아 사용하기 어렵습니다. 예를 들어 K2에서는 어노테이션이 달린 함수의 시작 오프셋이
    //        함수 본문이 아닌 어노테이션을 가리킬 수 있습니다.
    val line = declaration.file.fileEntry.getLineNumber(startOffset)
    val traceInfo = "$fqName ($fileName:$line)" // TODO(b/174715171) decide on what to log

    val dirty = scope.dirty
    val changed = scope.changedBitMaskValue
    val dirtyParams = if (dirty != null && dirty.used) dirty.declarations else changed?.declarations

    val dirty1 = dirtyParams?.getOrNull(0)?.let(::irGet) ?: irIntConst(-1)
    val dirty2 = dirtyParams?.getOrNull(1)?.let(::irGet) ?: irIntConst(-1)

    return irIfTraceInProgress(
      body = irCall(traceEventStartFunction).also {
        it.putValueArgument(0, key)
        it.putValueArgument(1, dirty1)
        it.putValueArgument(2, dirty2)
        it.putValueArgument(3, irStringConst(traceInfo))
      },
    )
  }

  private fun irTraceEventEnd(): IrExpression? =
    traceEventEndFunction?.let { irIfTraceInProgress(irCall(it)) }

  private fun irStartDefaults(element: IrElement, scope: Scope.BlockScope): IrExpression =
    irWithSourceInformation(
      startGroup = irMethodCall(
        target = irCurrentComposer(),
        function = startDefaultsFunction,
        startOffset = element.startOffset,
        endOffset = element.endOffset,
      ),
      scope = scope,
    )

  private fun irStartRestartGroup(
    element: IrElement,
    scope: Scope.BlockScope,
    key: IrExpression = element.irSourceKey(),
  ): IrExpression =
    irWithSourceInformation(
      startGroup = irSet(
        variable = nearestComposer(),
        value = irMethodCall(
          target = scope.irCurrentComposer(),
          function = startRestartGroupFunction,
          startOffset = element.startOffset,
          endOffset = element.endOffset,
        ).also {
          it.putValueArgument(0, key)
        }
      ),
      scope = scope,
    )

  private fun irEndRestartGroup(scope: Scope.BlockScope): IrCall =
    irMethodCall(target = scope.irCurrentComposer(), function = endRestartGroupFunction)

  private fun irChanged(
    value: IrExpression,
    compareInstanceForFunctionTypes: Boolean,
    compareInstanceForUnstableValues: Boolean = FeatureFlag.StrongSkipping.enabled,
  ): IrCall =
    irChanged(
      currentComposer = irCurrentComposer(),
      value = value,
      inferredStable = false,
      compareInstanceForFunctionTypes = compareInstanceForFunctionTypes,
      compareInstanceForUnstableValues = compareInstanceForUnstableValues,
    )

  private fun irSkipToGroupEnd(
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrCall =
    irMethodCall(
      target = irCurrentComposer(startOffset, endOffset),
      function = skipToGroupEndFunction,
      startOffset = startOffset,
      endOffset = endOffset,
    )

  private fun irEndReplaceGroup(
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
    scope: Scope.BlockScope,
  ): IrExpression =
    irEndReplaceGroup(
      currentComposer = scope.irCurrentComposer(startOffset, endOffset),
      startOffset = startOffset,
      endOffset = endOffset,
    )

  private fun irEndDefaults(): IrExpression =
    irMethodCall(target = irCurrentComposer(), function = endDefaultsFunction)

  private fun irStartMovableGroup(
    element: IrElement,
    joinedData: IrExpression,
    scope: Scope.BlockScope,
  ): IrExpression =
    irWithSourceInformation(
      startGroup = irMethodCall(
        target = scope.irCurrentComposer(),
        function = startMovableFunction,
        startOffset = element.startOffset,
        endOffset = element.endOffset
      ).also {
        it.putValueArgument(0, element.irSourceKey())
        it.putValueArgument(1, joinedData)
      },
      scope = scope,
    )

  private fun irEndMovableGroup(scope: Scope.BlockScope): IrExpression =
    irMethodCall(target = scope.irCurrentComposer(), function = endMovableFunction)

  private fun irEndToMarker(marker: IrExpression, scope: Scope.BlockScope): IrExpression =
    irMethodCall(
      target = scope.irCurrentComposer(),
      function = endToMarkerFunction!!,
    ).apply {
      putValueArgument(0, marker)
    }

  private fun irJoinKeyChain(keyExprs: List<IrExpression>): IrExpression =
    // 만약 keyExprs의 요소가 하나일 경우 그냥 하나의 expression이 바로 반환됨
    keyExprs.reduce { acc, expr ->
      irMethodCall(
        target = irCurrentComposer(),
        function = joinKeyFunction,
      ).apply {
        putValueArgument(0, acc)
        putValueArgument(1, expr)
      }
    }

  private fun irSafeCall(
    dispatchReceiver: IrExpression,
    callSymbol: IrFunctionSymbol,
    vararg arguments: IrExpression,
  ): IrExpression {
    val receiverCopy = irTemporary(dispatchReceiver, nameHint = "safe_receiver")

    return irBlock(
      // SAFE_CALL origin: 'this?.call()' 처럼 call의 receiver에 '?.'가 붙는 경우
      origin = IrStatementOrigin.SAFE_CALL,
      statements = listOf(
        receiverCopy,
        irIfThenElse(
          condition = irEqual(irGet(receiverCopy), irAnyNull()),
          thenPart = irAnyNull(),
          elsePart = irCall(callSymbol).apply {
            this.dispatchReceiver = irGet(receiverCopy)
            arguments.fastForEachIndexed { i, arg -> putValueArgument(i, arg) }
          },
        ),
      ),
    )
  }

  private fun irTemporary(
    value: IrExpression,
    nameHint: String? = null,
    irType: IrType = value.type,
    isVar: Boolean = false,
    exactName: Boolean = false,
  ): IrVariableImpl {
    val scope = currentFunctionScope
    val name = if (exactName && nameHint != null) nameHint else scope.getNameForTemporary(nameHint)

    return irTemporaryVariable(
      value = value,
      name = name,
      irType = irType,
      isVar = isVar,
    ).also {
      it.parent = currentFunctionScope.function.parent
    }
  }

  // 사용할 changed 표현식이 없다면 항상 false임
  private fun irIntrinsicRememberInvalid(
    isMemoizedLambda: Boolean,
    args: List<IrExpression>,
    metas: List<CallArgumentMeta>,
    changedExpr: (isMemoizedLambda: Boolean, arg: IrExpression, argInfo: CallArgumentMeta) -> IrExpression?,
  ): IrExpression =
    args
      .mapIndexedNotNull { i, arg -> changedExpr(isMemoizedLambda, arg, metas[i]) }
      .reduceOrNull { acc, changed -> irBooleanOr(lhs = acc, rhs = changed) }
      ?: irBooleanConst(false)

  private fun irIntrinsicChanged(
    isMemoizedLambda: Boolean,
    arg: IrExpression,
    argInfo: CallArgumentMeta,
  ): IrExpression? {
    val meta = argInfo.referencedParam
    val parentDirty = meta?.dirty

    return when {
      // 절대 변하지 않는 값이라면
      argInfo.isStatic -> null

      // 날 감싸는 부모 함수가 있고, 안정적인 인자이고,
      // 부모 함수에 %dirty가 있고, static하지 않은 기본 인자가 없는 경우
      //
      // MEMO 안정한 매개변수라면 changed() 동작을 $dirty 플래그 비교로 대체함
      argInfo.isReferenced &&
        argInfo.stabilityOfExpr.knownStable() &&
        parentDirty is IrChangedBitMaskVariable &&
        !meta.hasNonStaticDefault -> {
        // if it's a dirty flag, and the parameter doesn't have a default value and is _known_
        // to be stable, then we know that the value is now CERTAIN, thus we can avoid
        // calling changed completely.
        //
        // 기본 인자가 없는 파라미터이고 안정적인 값이라는 것이 확실할 경우, dirty flag일지라도
        // changed 호출을 완전히 생략할 수 있습니다.

        // invalid = invalid or (mask == different)
        irEqual(
          lhs = parentDirty.irIsolateBitsAtSlot(slot = meta.slotIndex, includeStableBit = true), /* 0b111 */
          rhs = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = meta.slotIndex)),
        )
      }

      // 날 감싸는 부모 함수가 있고, 불안정하지 않은 인자이고,
      // 부모 함수에 %dirty가 있고, static하지 않은 기본 인자가 없는 경우
      //
      // MEMO 불안정하지 않은 매개변수면 changed() 동작을 $dirty 플래그 비교로 대체하기도 하고,
      //  직접 composer.changed()를 호출하기도 함
      argInfo.isReferenced &&
        !argInfo.stabilityOfExpr.knownUnstable() &&
        parentDirty is IrChangedBitMaskVariable &&
        !meta.hasNonStaticDefault -> {
        // if it's a dirty flag, and the parameter doesn't have a default value and it might
        // be stable, then we only check changed if the value is unstable, otherwise we can
        // just check to see if the mask is different.
        //
        // dirty flag인 경우, 파라미터에 디폴트 값이 없고 안정적일 수도 있는 값이라면,
        // 값이 불안정할 때만 changed를 확인하고, 그 외에는 마스크 값이 다른지만 확인하면 됩니다.

        val maskIsStableAndDifferent =
        // %dirty의 MSB가 1이 아니라면 안정하다고 판단할 수 있음.
          // 'includeStableBit = true'이고, $dirty가 Unknown(0b100)을 포함한다면 MSB가 1일 수 있음.
          irEqual(
            lhs = parentDirty.irIsolateBitsAtSlot(slot = meta.slotIndex, includeStableBit = true) /* 0b111 */,
            rhs = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = meta.slotIndex))
          )
        val maskIsUnstableAndChanged =
          irAndAnd(
            // %dirty의 MSB가 1이라면 불안정한 상태임
            lhs = irNotEqual(
              lhs = parentDirty.irSlotAnd(
                slot = meta.slotIndex,
                bits = StabilityBits.UNSTABLE /* Unknown(0b100) */.bits,
              ),
              rhs = irIntConst(0),
            ),
            rhs = irChanged(
              value = arg,
              compareInstanceForFunctionTypes = false,
              compareInstanceForUnstableValues = isMemoizedLambda,
            ),
          )

        // invalid = invalid or ((mask == different) || (unstable && changed()))
        irOrOr(lhs = maskIsStableAndDifferent, rhs = maskIsUnstableAndChanged)
      }

      // 날 감싸는 부모 함수가 있고, 불안정하지 않은 인자이고,
      // 부모 함수에 %changed만 있는 경우
      argInfo.isReferenced &&
        !argInfo.stabilityOfExpr.knownUnstable() &&
        parentDirty != null -> {
        // if it's a changed flag or parameter with a default expression then uncertain is a
        // possible value. If it is uncertain OR unstable, then we need to call changed.
        // If it is uncertain or unstable here it will _always_ be uncertain or unstable
        // here, so this is safe. If it is not uncertain or unstable, we can just check to
        // see if its different.
        //
        // [changed 플래그이거나 기본 표현식을 가진 매개변수인 경우], 불확실하거나 불안정한
        // 값이 될 수 있습니다. 불확실하거나 불안정한 경우에는 changed()를 호출해야 합니다.
        // 여기에서 불확실하거나 불안정하다면 항상 그런 상태이므로 이는 안전합니다.
        // 불확실하지도 않고 불안정하지도 않다면 단지 값이 다른지만 확인하면 됩니다.

        // xor: 두 비트 중 하나만 1이라면 1, 둘 다 0이거나 둘 다 1이라면 0
        // unstableOrUncertain = (mask xor Static(0b011)) > Different(0b010)
        val maskIsUnstableOrUncertain =
          irIntGreater(
            // Static과 모두 겹치지 않는 비트: Uncertain(0b000), Unknown(0b100)
            //   Uncertain(0b000) xor Static(0b011) = Static(0b011)  ==> maskIsUnstableOrUncertain is true
            //   Unknown(0b100)   xor Static(0b011) = Mask(0b111)    ==> maskIsUnstableOrUncertain is true
            //
            // Static과 하나라도 겹치는 비트: Same(0b001), Different(0b010), Static(0b011), Mask(0b111)
            //   Same(0b001)      xor Static(0b011) = Different(0b010)
            //   Different(0b010) xor Static(0b011) = Same(0b001)
            //   Static(0b011)    xor Static(0b011) = Uncertain(0b000)
            //   Mask(0b111)      xor Static(0b011) = Unknown(0b100)  ==> maskIsUnstableOrUncertain is true
            lhs = irIntXor(
              lhs = parentDirty.irIsolateBitsAtSlot(slot = meta.slotIndex, includeStableBit = true), /* 0b111 */
              rhs = irIntConst(ParamState.Static /* 0b011 */.bitsForSlot(slot = meta.slotIndex)),
            ),
            // Different보다 큰 비트: Static(0b011), Unknown(0b100), Mask(0b111)
            rhs = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = meta.slotIndex)),
          )

        // invalid = invalid or ((unstableOrUncertain && changed()) || (mask == different))
        irOrOr(
          lhs = irAndAnd(
            lhs = maskIsUnstableOrUncertain,
            rhs = irChanged(
              value = arg,
              compareInstanceForFunctionTypes = false,
              compareInstanceForUnstableValues = isMemoizedLambda,
            ),
          ),
          rhs = irEqual(
            // $changed의 MSB가 1이라면 'maskIsUnstableOrUncertain = true'이므로,
            // 여기서 includeStableBit는 항상 false임
            lhs = parentDirty.irIsolateBitsAtSlot(slot = meta.slotIndex, includeStableBit = false), /* 0b011 */
            rhs = irIntConst(ParamState.Different /* 0b010 */.bitsForSlot(slot = meta.slotIndex)),
          ),
        )
      }

      else -> irChanged(
        value = arg,
        compareInstanceForFunctionTypes = false,
        compareInstanceForUnstableValues = isMemoizedLambda,
      )
    }
  }

  // MEMO typeParam이 valueParam의 타입으로 사용된 경우만 추적함
  private fun irTypeParameterStability(typeParam: IrTypeParameter): IrExpression? {
    var scope: Scope? = currentScope

    loop@ while (scope != null) {
      when (scope) {
        is Scope.FunctionScope -> {
          if (scope.isComposable) {
            val fn = scope.function
            val dirty = scope.dirty ?: scope.changedBitMaskValue

            if (dirty != null && fn.typeParameters.isNotEmpty()) {
              for (valueParam in fn.valueParameters) {
                if (valueParam.type.classifierOrNull == typeParam.symbol) {
                  val valueParamSlotIndex = scope.trackedParameters.indexOf(valueParam)
                  if (valueParamSlotIndex == -1) return null

                  // and 결과로 MSB가 1이 아니면 stable함
                  return irAnd(
                    lhs = irIntConst(StabilityBits.UNSTABLE /* Unknown(0b100) */.bitsForSlot(slot = 0)),
                    rhs = dirty.irShiftBits(fromSlot = valueParamSlotIndex, toSlot = 0),
                  )
                }
              }
            }
          }
        }

        is Scope.RootScope,
        is Scope.FileScope,
        is Scope.ClassScope,
          -> {
          break@loop
        }

        else -> {
          /* Do nothing, continue traversing */
        }
      }

      scope = scope.parent
    }

    return null
  }

  // 원래 이름: withReplaceGroupStatements
  private fun IrBlock.wrapWithReplaceGroup(
    scope: Scope.BlockScope,
    startAt: Int = 0,
  ): IrExpression {
    currentFunctionScope.metrics.recordGroup()
    scope.realizeGroup(makeEnd = { irEndReplaceGroup(scope = scope) })

    val prefixStatements = statements.subList(0, startAt)
    val suffixStatements = statements.subList(startAt, statements.size)

    return when {
      // if the scope ends with a return call, then it will get properly ended if we
      // just push the end call on the scope because of the way returns get transformed in
      // this class. As a result, here we can safely just "prepend" the start call.
      //
      // 스코프가 return 호출로 끝나는 경우, 이 클래스에서 리턴이 변환되는 방식 덕분에
      // 그냥 end 호출을 스코프에 푸시하기만 해도 적절하게 종료됩니다. 따라서 이 경우에는
      // start 호출을 앞에 "prepend"하는 방식으로 안전하게 처리할 수 있습니다.
      endsWithReturnOrJump() -> {
        IrBlockImpl(
          startOffset = startOffset,
          endOffset = endOffset,
          type = type,
          origin = origin,
          statements = listOf(
            *prefixStatements.toTypedArray(),
            irStartReplaceGroup(element = this, scope = scope),
            *suffixStatements.toTypedArray(),
          ),
        )
      }

      // otherwise, we want to push an end call for any early returns/jumps, but also add
      // an end call to the end of the group.
      //
      // 그 외의 경우에는, 조기 리턴이나 점프가 발생할 수 있는 지점마다 end 호출을 추가해야
      // 하며, 그룹의 끝에도 end 호출을 추가해야 합니다.
      else -> {
        IrBlockImpl(
          startOffset = startOffset,
          endOffset = endOffset,
          type = type,
          origin = origin,
          statements = listOf(
            *prefixStatements.toTypedArray(),
            irStartReplaceGroup(
              element = this,
              scope = scope,
              startOffset = startOffset,
              endOffset = endOffset,
            ),
            *suffixStatements.toTypedArray(),
            irEndReplaceGroup(
              startOffset = startOffset,
              endOffset = endOffset,
              scope = scope,
            ),
          ),
        )
      }
    }
  }

  // 원래 이름: asReplaceGroup
  private fun IrExpression.wrapWithReplaceGroup(scope: Scope.BlockScope): IrExpression {
    currentFunctionScope.metrics.recordGroup()

    // 현재 블록에 컴포저블 호출이 없고, return이나 점프(break/continue)도 없다면,
    if (!scope.hasComposableCalls && !scope.hasReturn && !scope.hasJump) {
      // if the scope has no composable calls, then the only important thing is that a
      // start/end call gets executed. as a result, we can just put them both at the top of
      // the group, and we don't have to deal with any of the complicated jump logic that
      // could be inside of the block.
      //
      // 스코프에 컴포저블 호출이 없다면 중요한 것은 start와 end 호출이 실행된다는 점뿐입니다.
      // 따라서 이 둘을 그룹의 맨 앞에 배치하면 되고, 블록 내부에 있을 수 있는 복잡한 점프
      // 로직은 처리할 필요가 없습니다.
      return wrap(
        // STUDY 그룹을 열자마자 바로 닫음??
        before = listOf(
          irStartReplaceGroup(
            element = this,
            scope = scope,
            startOffset = startOffset,
            endOffset = endOffset,
          ),
          irEndReplaceGroup(
            startOffset = startOffset,
            endOffset = endOffset,
            scope = scope,
          ),
        ),
      )
    }

    scope.realizeGroup(makeEnd = { irEndReplaceGroup(scope = scope) })

    return when {
      // if the scope ends with a return call, then it will get properly ended if we
      // just push the end call on the scope because of the way returns get transformed in
      // this class. As a result, here we can safely just "prepend" the start call.
      //
      // 스코프가 return 호출로 끝나는 경우, 이 클래스에서 리턴이 변환되는 방식 덕분에
      // end 호출을 스코프에 그냥 추가(=> realizeGroup(makeEnd = ..))하기만 해도 정상적으로
      // 종료됩니다. 따라서 이 경우에는 start 호출만 앞부분에 안전하게 "prepend"하면 됩니다.
      endsWithReturnOrJump() -> {
        wrap(before = listOf(irStartReplaceGroup(element = this, scope = scope)))
      }

      // otherwise, we want to push an end call for any early returns/jumps, but also add
      // an end call to the end of the group.
      //
      // 그 외의 경우에는, 조기 리턴이나 점프가 발생할 수 있는 지점마다 end 호출을 추가하고,
      // 그룹의 끝에도 end 호출을 추가해야 합니다.
      else -> {
        wrap(
          before = listOf(
            irStartReplaceGroup(
              element = this,
              scope = scope,
              startOffset = startOffset,
              endOffset = endOffset,
            ),
          ),
          after = listOf(
            irEndReplaceGroup(
              startOffset = startOffset,
              endOffset = endOffset,
              scope = scope,
            ),
          ),
        )
      }
    }
  }

  private fun IrExpression.variablePrefix(variable: IrVariable): IrBlockImpl =
    IrBlockImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = type,
      origin = null,
      statements = listOf(variable, this),
    )

  fun IrExpression.wrap(
    before: List<IrStatement> = emptyList(),
    after: List<IrStatement> = emptyList(),
  ): IrContainerExpression =
    if (after.isEmpty() || type.isNothing() || type.isUnit()) {
      wrap(
        startOffset = startOffset,
        endOffset = endOffset,
        type = type,
        before = before,
        after = after,
      )
    } else {
      val tmpVar = irTemporary(value = this, nameHint = "group")
      tmpVar.wrap(
        startOffset = startOffset,
        endOffset = endOffset,
        type = type,
        before = before,
        after = after + irGet(tmpVar),
      )
    }

  // Coalescable: 합체 가능
  // 원래 이름: asCoalescableGroup
  private fun IrExpression.wrapWithCoalescableGroup(scope: Scope.BlockScope): IrExpression {
    val metrics = currentFunctionScope.metrics
    val before = mutableStatementContainer()
    val after = mutableStatementContainer()

    // Since this expression produces a dynamic number of groups, we may need to wrap it with
    // a group directly. We don't know that for sure yet, so we provide the parent scope with
    // handlers to do that if it ends up needing to.
    //
    // 이 표현식은 동적인 개수의 그룹을 생성할 수 있기 때문에, 경우에 따라 직접 그룹으로
    // 감싸야 할 수도 있습니다. 아직 확실하진 않지만, 필요할 경우를 대비해 부모 스코프에
    // 해당 작업을 처리할 수 있는 핸들러를 제공합니다.
    encounteredCoalescableGroup(
      coalescableScope = scope,
      realizeGroup = {
        if (before.statements.isEmpty()) {
          metrics.recordGroup()
          before.statements.add(irStartReplaceGroup(element = this, scope = scope))
          after.statements.add(irEndReplaceGroup(scope = scope))
        }
      },
      makeEnd = {
        // STUDY end 그룹이 두 개?
        irEndReplaceGroup(scope = scope)
      },
    )

    return wrap(before = listOf(before), after = listOf(after))
  }

  // SourceGroup or EarlyExitGroup
  private fun IrContainerExpression.asSourceOrEarlyExitGroup(scope: Scope.FunctionScope): IrContainerExpression {
    val needsGroup = scope.hasInlineEarlyReturn || scope.isCrossinlineLambda

    if (needsGroup) currentFunctionScope.metrics.recordGroup()
    else if (!collectSourceInformation) {
      // If we are not generating source information and the lambda does not contain an
      // early exit this we don't need a group or source markers.
      //
      // 소스 정보 생성을 하지 않고, 람다가 조기 종료를 포함하지 않는 경우에는
      // 그룹이나 소스 마커를 생성할 필요가 없습니다.
      return this
    }

    // if the scope has no composable calls, then the only important thing is that a
    // start/end call gets executed. as a result, we can just put them both at the top of
    // the group, and we don't have to deal with any of the complicated jump logic that
    // could be inside of the block.
    //
    // 스코프에 컴포저블 호출이 없는 경우, 중요한 것은 start와 end 호출이 실행된다는
    // 점뿐입니다. 따라서 이 둘을 그룹의 맨 앞에 배치하면 되고, 블록 내부에 존재할 수
    // 있는 복잡한 점프 로직은 신경 쓸 필요가 없습니다.
    val makeStart = {
      if (needsGroup) {
        irStartReplaceGroup(
          element = this,
          scope = scope,
          startOffset = startOffset,
          endOffset = endOffset,
        )
      } else {
        irSourceInformationMarkerStart(element = this, scope = scope)
      }
    }
    val makeEnd = {
      if (needsGroup) irEndReplaceGroup(scope = scope)
      else irSourceInformationMarkerEnd(element = this, scope = scope)
    }

    if (!scope.hasComposableCalls && !scope.hasReturn && !scope.hasJump) {
      return wrap(before = listOf(makeStart()), after = listOf(makeEnd()))
    }

    scope.realizeGroup(makeEnd)

    return when {
      // if the scope ends with a return call, then it will get properly ended if we
      // just push the end call on the scope because of the way returns get transformed in
      // this class. As a result, here we can safely just "prepend" the start call.
      //
      // 스코프가 return 호출로 끝나는 경우, 이 클래스에서 리턴이 변환되는 방식 덕분에
      // end 호출을 스코프에 추가하기만 해도 정상적으로 종료됩니다. 따라서 이 경우에는
      // start 호출만 앞부분에 안전하게 추가(prepend)하면 됩니다.
      //
      // STUDY "it will get properly ended if we just push the end call on the scope"
      //  coalescableGroup의 'makeEnd' 로직으로 이해해 보기
      //
      // jump: break or continue
      endsWithReturnOrJump() -> wrap(before = listOf(makeStart()))

      // otherwise, we want to push an end call for any early returns/jumps, but also add
      // an end call to the end of the group.
      //
      // 그 외의 경우에는, 조기 리턴이나 점프가 발생할 수 있는 지점마다 end 호출을 추가하고,
      // 그룹의 마지막에도 end 호출을 추가해야 합니다.
      else -> wrap(before = listOf(makeStart()), after = listOf(makeEnd()))
    }
  }

  private fun mutableStatementContainer(): IrContainerExpression = mutableStatementContainer(context)

  private fun encounteredComposableCall(withGroups: Boolean) {
    var scope: Scope? = currentScope

    // it is important that we only report "withGroups: false" for the _nearest_ scope, and
    // every scope above that it effectively means there was a group even if it is false.
    //
    // "withGroups = false"를 가장 가까운 스코프에만 보고하는 것이 중요합니다. 그보다 바깥에
    // 있는 모든 상위 스코프에서는, 비록 false로 표시되더라도 사실상 그룹이 있었다고 간주됩니다.
    var groups = withGroups

    loop@ while (scope != null) {
      when (scope) {
        is Scope.FunctionScope -> {
          scope.recordComposableCall(withGroups = groups)
          groups = true
          if (!scope.isInlineLambda) {
            break@loop
          }
        }

        is Scope.BlockScope -> {
          scope.recordComposableCall(withGroups = groups)
          groups = true
        }

        is Scope.ClassScope -> {
          break@loop
        }

        else -> {
          /* Do nothing, continue traversing */
        }
      }

      scope = scope.parent
    }
  }

  private fun recordCallInSource(call: IrElement) {
    var scope: Scope? = currentScope
    var location: Scope.SourceLocation? = null

    loop@ while (scope != null) {
      when (scope) {
        is Scope.FunctionScope -> location = scope.recordSourceLocation(call = call, location = location)
        is Scope.BlockScope -> location = scope.recordSourceLocation(call = call, location = location)
        is Scope.ClassScope -> break@loop
        else -> {} /* Do nothing, continue traversing */
      }
      scope = scope.parent
    }
  }

  // "Captured" ComposableCall 이니 이 encounter는 람다 안에서만 발생함
  //   -> visitCall 콜백의 'if (parameter.isInlineLambda())' 분기에서만 발생함
  //
  // encountered: 접하다[마주치다]
  private fun encounteredCapturedComposableCall() {
    var scope: Scope? = currentScope

    loop@ while (scope != null) {
      when (scope) {
        is Scope.CaptureScope -> {
          scope.markCapturedComposableCall()
          break@loop
        }
        else -> {
          /* Do nothing, continue traversing. */
        }
      }
      scope = scope.parent
    }
  }

  private fun encounteredCoalescableGroup(
    coalescableScope: Scope.BlockScope,
    realizeGroup: () -> Unit,
    makeEnd: () -> IrExpression,
  ) {
    var scope: Scope? = currentScope

    loop@ while (scope != null) {
      when (scope) {
        is Scope.CallScope,
        is Scope.ReturnScope,
          -> {
          // Ignore
        }

        is Scope.FunctionScope -> {
          scope.markCoalescableGroup(
            scope = coalescableScope,
            realizeGroup = realizeGroup,
            makeEnd = makeEnd,
          )
          if (!scope.isInlineLambda || scope.isComposable) {
            break@loop
          }
        }

        is Scope.BlockScope -> {
          scope.markCoalescableGroup(
            scope = coalescableScope,
            realizeGroup = realizeGroup,
            makeEnd = makeEnd,
          )
          break@loop
        }

        else -> error("Unexpected scope type")
      }
      scope = scope.parent
    }
  }

  private fun encounteredReturn(
    symbol: IrReturnTargetSymbol,
    extraEndLocation: (endExpr: IrExpression) -> Unit,
  ) {
    var scope: Scope? = currentScope
    val blockScopes = mutableListOf<Scope.BlockScope>()

    // leave: 떠나다 (이미 알고 있지만 복습!)
    var leavingInlineLambda = false

    loop@ while (scope != null) {
      when (scope) {
        is Scope.FunctionScope -> {
          if (scope.function == symbol.owner) {
            // STUDY 모든 return을 early return으로 다루나??
            scope.hasAnyEarlyReturn = true

            if (!leavingInlineLambda || !rollbackGroupMarkerEnabled) {
              blockScopes.fastForEach {
                it.markReturn(extraEndLocation = extraEndLocation)
              }

              scope.markReturn(extraEndLocation = extraEndLocation)

              if (scope.isInlineLambda && scope.inComposableCall) {
                scope.hasInlineEarlyReturn = true
              }
            }

            // leavingInlineLambda == true 일 때도 실행됨
            else {
              val functionScope = scope
              val targetScope = currentScope as? Scope.BlockScope ?: functionScope
              val marker = irGet(functionScope.createMarker())

              extraEndLocation(/* endExpr = */ irEndToMarker(marker = marker, scope = targetScope))

              if (functionScope.isInlineLambda) {
                scope.hasInlineEarlyReturn = true
              } else {
                functionScope.markReturn(extraEndLocation = extraEndLocation)
              }
            }

            break@loop
          }

          if (scope.isInlineLambda && scope.inComposableCall) {
            leavingInlineLambda = true
            scope.hasInlineEarlyReturn = true
          }
        }

        is Scope.BlockScope -> {
          blockScopes.add(scope)
        }

        else -> {
          /* Do nothing, continue traversing */
        }
      }

      scope = scope.parent
    }
  }

  private fun encounteredJump(jump: IrBreakContinue, extraEndLocation: (endExpr: IrExpression) -> Unit) {
    var scope: Scope? = currentScope

    loop@ while (scope != null) {
      when (scope) {
        is Scope.ClassScope -> error("Unexpected Class Scope encountered")

        is Scope.FunctionScope -> {
          if (!scope.isInlineLambda) {
            error("Unexpected Function Scope encountered")
          }
        }

        is Scope.LoopScope -> {
          scope.markJump(jump = jump, extraEndLocation = extraEndLocation)
          if (jump.loop == scope.loop) break@loop
        }

        is Scope.BlockScope -> {
          scope.markJump(extraEndLocation = extraEndLocation)
        }

        else -> {
          /* Do nothing, continue traversing */
        }
      }

      scope = scope.parent
    }
  }

  private fun <T : Scope> IrExpression.transformWithScope(scope: T): Pair<T, IrExpression> {
    val previousScope = currentScope
    try {
      currentScope = scope
      scope.parent = previousScope
      scope.level = previousScope.level + 1
      val result = transform(this@ComposableFunctionBodyTransformer, null)
      return scope to result
    } finally {
      currentScope = previousScope
    }
  }

  private inline fun <T : Scope> withScope(scope: T, block: () -> Unit): T {
    val previousScope = currentScope
    currentScope = scope
    scope.parent = previousScope
    scope.level = previousScope.level + 1
    try {
      block()
    } finally {
      currentScope = previousScope
    }
    return scope
  }

  // withScope은 <T : Scope>를 반환하지만, inScope은 R을 반환함
  private inline fun <R> inScope(scope: Scope, block: () -> R): R {
    val previousScope = currentScope
    currentScope = scope
    scope.parent = previousScope
    scope.level = previousScope.level + 1
    try {
      return block()
    } finally {
      currentScope = previousScope
    }
  }

  private inline fun Scope.forEach(crossinline block: (scope: Scope) -> Unit) {
    var current: Scope? = this
    while (current != null) {
      block(current)
      current = current.parent
    }
  }

  /**
   * Argument information extracted from the call site and argument expression itself.
   *
   * 호출 지점과 인자 표현식 자체에서 추출된 인자 정보입니다.
   */
  data class CallArgumentMeta(
    /** stability of argument expression */
    var stabilityOfExpr: Stability = Stability.Unstable,

    /** whether argument is vararg */
    var isVararg: Boolean = false,

    /** whether default value for the arg is provided */
    var hasDefaultValue: Boolean = false,

    /** whether the expression is static */
    var isStatic: Boolean = false,

    /**
     * metadata from enclosing function parameters (NOT the function being called).
     *
     * 감싸고 있는 함수의 파라미터에서 온 메타데이터 (현재 호출 중인 함수가 아님).
     */
    // 원래 이름: paramRef
    var referencedParam: ReferencedParameter? = null,
  ) {
    // 원래 이름: isCertain
    val isReferenced get() = referencedParam != null
  }

  /**
   * Composable call information extracted from composable function parameters referenced
   * in a call argument.
   *
   * 컴포저블 함수 파라미터가 호출 인자에서 참조될 때 추출된 컴포저블 호출 정보.
   */
  // @Composable fun Parent(arg: Int) {
  //   MyComposable(arg)
  //                ^^^ 이 arg 인자의 출처인 arg 매개변수의 메타데이터
  // }
  //
  // 원래 이름: ParamMeta
  data class ReferencedParameter(
    /** Slot index in maskParam */
    // 의역: 참조된 매개변수의 slot index
    val slotIndex: Int = -1,

    /**
     * Reference to $changed or $dirty parameter with the [ParamState] mask.
     *
     * $changed 또는 $dirty 파라미터를 [ParamState] 마스크와 함께 참조한 값.
     */
    // 의역: 참조된 매개변수를 갖는 함수의 dirty 플래그
    var dirty: IrChangedBitMaskValue? = null,

    /**
     * Whether the parameter has a non-static default value.
     *
     * 이(참조된) 매개변수가 static이 아닌 기본값을 가지는지 여부.
     */
    val hasNonStaticDefault: Boolean = false,
  )

  private fun argumentMetaOf(arg: IrExpression, isProvided: Boolean): CallArgumentMeta {
    val meta = CallArgumentMeta(hasDefaultValue = isProvided)
    populateArgumentMeta(arg = arg, meta = meta)
    return meta
  }

  // populate: 채우다, 기입하다
  private fun populateArgumentMeta(arg: IrExpression, meta: CallArgumentMeta) {
    meta.stabilityOfExpr = stabilityInferencer.stabilityOfExpression(expr = arg)
    when {
      arg.isStaticExpression() -> meta.isStatic = true

      arg is IrGetValue -> {
        // @Composable fun Parent(arg: Int) {
        //   MyComposable(arg)
        //                ^^^ 여기서의 owner는 Parent 함수의 ValueParameter
        // }
        when (val owner = arg.symbol.owner) {
          is IrValueParameter -> {
            meta.referencedParam = extractReferencedParameterFromScopes(param = owner)
          }

          // @Composable fun Parent() {
          //   val arg = 1
          //   MyComposable(arg)
          //                ^^^ 여기서의 owner는 IrVariable
          // }
          is IrVariable -> {
            if (owner.isConst) {
              meta.isStatic = true
            }

            // const가 아닌 read-only 변수인 경우
            else if (!owner.isVar && owner.initializer != null) {
              populateArgumentMeta(arg = owner.initializer!!, meta = meta)
            }
          }
        }
      }

      arg is IrVararg -> {
        meta.stabilityOfExpr = stabilityInferencer.stabilityOfType(type = arg.varargElementType)
      }
    }
  }

  // 원래 이름: extractParamMetaFromScopes
  private fun extractReferencedParameterFromScopes(param: IrValueParameter): ReferencedParameter? {
    var scope: Scope? = currentScope
    val paramOwnedFn: IrDeclarationParent = param.parent

    while (scope != null) {
      when (scope) {
        is Scope.FunctionScope -> {
          if (scope.function == paramOwnedFn) {
            if (scope.isComposable) {
              val slotIndex = scope.trackedParameters.indexOf(param)
              if (slotIndex != -1) {
                return ReferencedParameter(
                  slotIndex = slotIndex,
                  dirty = scope.dirty,
                  hasNonStaticDefault = param.defaultValue?.expression?.isStaticExpression() == false,
                )
              }
            }

            return null
          }

          // scope.function != fn
          else {
            // If the capture is outside inline lambda, we don't allow meta propagation.
            // 캡처가 인라인 람다 외부에 있는 경우에는 메타 정보 전파를 허용하지 않습니다.
            if (
              !inlineLambdaInfo.isInlineLambda(lambda = scope.function) ||
              inlineLambdaInfo.isCrossinlineLambda(lambda = scope.function)
            ) {
              return null
            }
          }
        }

        // scope !is Scope.FunctionScope
        else -> {
          /* Do nothing, continue traversing */
        }
      }

      scope = scope.parent
    }

    return null
  }

  private fun recordSourceParameter(call: IrCall, index: Int, scope: Scope.BlockScope) {
    sourceInfoFixups.add(SourceInfoFixup(call = call, index = index, scope = scope))
  }

  private fun applySourceInfoFixups() {
    // Apply the fix-ups lowest scope to highest.
    // 수정 작업은 가장 낮은 스코프부터 가장 높은 스코프 순으로 적용합니다.
    sourceInfoFixups.sortBy { -it.scope.level }

    for (sourceFixup in sourceInfoFixups) {
      sourceFixup.call.putValueArgument(
        sourceFixup.index,
        irStringConst(sourceFixup.scope.sourceInformation.orEmpty()),
      )
    }

    sourceInfoFixups.clear()
  }

  // Returns true if the number of groups added are required to be fix and a group is inserted
  // to balance the groups if they are not. Currently this is only guaranteed for IrWhen nodes
  // when the group non-skipping group optimization is enabled. This avoids inserting a redundant
  // group to balance an already balanced set of groups.
  //
  // 추가된 그룹 수가 고정되어야 하고, 그룹 수가 불균형할 경우 균형을 맞추기 위해 그룹이
  // 삽입되었는지를 반환합니다. 현재 이 동작은 IrWhen 노드에서 비스킵 그룹 최적화가
  // 활성화되어 있을 때에만 보장됩니다. 이는 이미 균형 잡힌 그룹 집합에 불필요한 그룹이
  // 삽입되는 것을 방지합니다.
  //
  // STUDY 무슨 맥락으로 추가된 함수일까????
  private fun IrExpression.isGroupBalanced(): Boolean =
    when (this) {
      is IrWhen -> FeatureFlag.OptimizeNonSkippingGroups.enabled
      else -> false
    }

  private fun intrinsicRememberScope(rememberCall: IrCall): Scope.BlockScope =
    object : Scope.BlockScope("<intrinsic-remember>") {
      val rememberFunction: IrSimpleFunction = rememberCall.symbol.owner
      val currentFunction: IrFunction = currentFunctionScope.function

      override fun hasSourceInformation(sourceInformationEnabled: Boolean): Boolean =
        sourceInformationEnabled

      override fun calculateSourceInfo(sourceInformationEnabled: Boolean): String? =
      // forge a source information call to fake remember function with current file
      // location to make sure tooling can identify the following group as remember.
      //
      // 현재 파일 위치를 사용하여 가짜 remember 함수에 대한 소스 정보 호출을 생성함으로써,
        // 이후 그룹이 remember로 식별될 수 있도록 툴링이 이를 인식하게 합니다.
        if (sourceInformationEnabled) {
          buildString {
            append(rememberFunction.callInformation())
            super.calculateSourceInfo(sourceInformationEnabled = true)?.also { append(it) }
            append(":")
            append(currentFunction.file.name)
            append("#")
            // Use runtime package hash to make sure tooling can identify it as such.
            // 툴링이 이를 해당 기능으로 인식할 수 있도록, 런타임 패키지 해시를 사용합니다.
            append(rememberFunction.packageHash().toString(36))
          }
        } else {
          null
        }
    }

  sealed class Scope(val name: String) {
    var parent: Scope? = null
    var level: Int = 0

    open val isInComposable: Boolean get() = false
    open val functionScope: FunctionScope? get() = parent?.functionScope
    open val fileScope: FileScope? get() = parent?.fileScope
    open val nearestComposer: IrValueParameter? get() = parent?.nearestComposer

    val myComposer: IrValueParameter
      get() = nearestComposer ?: error("Not in a composable function")

    open class SourceLocation(val element: IrElement) {
      // STUDY 이게 뭘 뜻하는 값일까?
      open val repeatable: Boolean
        get() = false

      var used = false
        private set

      fun markUsed() {
        used = true
      }
    }

    class RootScope : Scope(name = "<root>")

    abstract class BlockScope(name: String) : Scope(name = name) {
      private val sourceLocations = mutableListOf<SourceLocation>()
      private val coalescableChildren = mutableListOf<CoalescableGroupInfo>()

      // STUDY encounteredReturn에서 추가되는 걸 보아, 이 변수명이 의미하는 end는 return 콜로 추측됨
      private val extraEndLocations = mutableListOf<(endExpr: IrExpression) -> Unit>()

      override val isInComposable: Boolean
        get() = parent?.isInComposable ?: false

      var hasDefaultsGroup = false

      var hasComposableCallsWithGroups = false
        private set

      var hasComposableCalls = false
        private set

      var hasReturn = false
        private set

      var hasJump = false
        protected set

      // realized: 깨달음/인식, 실현/달성
      //           이 맥락에서는 '실현'으로 쓰임 (실현: 꿈, 기대 따위를 실제로 이룸)
      fun realizeGroup(makeEnd: (() -> IrExpression)?) {
        realizeCoalescableChildren()
        makeEnd?.let { realizeEndCalls(it) }
      }

      open fun realizeEndCalls(makeEnd: () -> IrExpression) {
        extraEndLocations.fastForEach { expressionLambda ->
          expressionLambda.invoke(makeEnd())
        }
      }

      // Claude 설명:
      /**
       * 병합 가능한(coalescable) 자식 그룹들을 실제 IR 코드로 구체화합니다.
       *
       * Compose 컴파일러는 최적화를 위해 인접한 non-composable 코드들을 하나의 그룹으로
       * 병합할 수 있는지 판단하고, 가능한 경우 단일 그룹으로 처리합니다.
       * 이 함수는 지연 평가되던 그룹들을 실제로 생성할 시점에 호출됩니다.
       *
       * 예시:
       * ```
       * val a = 1  // 병합 가능
       * val b = 2  // 병합 가능
       * val c = 3  // 병합 가능
       *
       * Text("...") // Composable 호출 - 이 시점에 위 변수들의 그룹을 하나의 그룹으로 realize
       * ```
       *
       * 각 [CoalescableGroupInfo]는 `shouldRealize` 플래그에 따라:
       * - `true`: 실제 startGroup/endGroup 호출 코드를 생성
       * - `false`: 다른 그룹과 병합되거나 생략
       */
      // [markCoalescableGroup]으로만 CoalescaleGroup 등록 가능
      fun realizeCoalescableChildren() {
        coalescableChildren.fastForEach { groupInfo ->
          groupInfo.realize()
        }
      }

      fun shouldRealizeCoalescableChildren() {
        coalescableChildren.fastForEach { groupInfo ->
          groupInfo.shouldRealize = true
        }
      }

      fun markCoalescableGroup(
        scope: BlockScope,
        realizeGroup: () -> Unit,
        makeEnd: () -> IrExpression,
      ) {
        addProvisionalSourceLocations(locations = scope.sourceLocations)
        val groupInfo = CoalescableGroupInfo(
          scope = scope,
          realizeGroup = realizeGroup,
          makeEnd = makeEnd,
        )
        coalescableChildren.add(groupInfo)
      }

      fun recordComposableCall(withGroups: Boolean) {
        hasComposableCalls = true
        if (withGroups) {
          hasComposableCallsWithGroups = true
        }
        if (coalescableChildren.isNotEmpty()) {
          // if a call happens after the coalescable child group, then we should
          // realize the group of the coalescable child.
          //
          // coalescableChildren 그룹 이후에 호출이 발생하는 경우, 해당 자식의 그룹을
          // 실현해야 합니다.
          //
          // STUDY 왜??
          coalescableChildren.last().shouldRealize = true
        }
      }

      // Add source locations that might be out of order as well as might be
      // used before they are realized into `sourceInformation()`. This is used
      // by coalesable groups which will mark their source locations used if they
      // become realized.
      //
      // 순서가 바뀔 수 있거나 실현되기 전에 사용될 수 있는 소스 위치를 sourceInformation에
      // 추가합니다. 이는 coalesable 그룹에서 사용되며, 해당 그룹이 실현되면 자신의 소스
      // 위치를 사용된 것으로 표시합니다.
      //
      // Provisional: 임시의, 일시적인
      fun addProvisionalSourceLocations(locations: List<SourceLocation>) {
        sourceLocations += locations
      }

      fun recordSourceLocation(call: IrElement, location: SourceLocation?): SourceLocation =
        (location ?: sourceLocationOf(call = call)).also { sourceLocations.add(it) }

      fun markReturn(extraEndLocation: (endExpr: IrExpression) -> Unit) {
        hasReturn = true
        extraEndLocations.add(extraEndLocation)
      }

      fun markJump(extraEndLocation: (endExpr: IrExpression) -> Unit) {
        hasJump = true
        extraEndLocations.add(extraEndLocation)
      }

      open fun hasSourceInformation(sourceInformationEnabled: Boolean): Boolean =
        sourceInformationEnabled && sourceLocations.isNotEmpty()

      open fun calculateSourceInfo(sourceInformationEnabled: Boolean): String? =
        if (sourceInformationEnabled && sourceLocations.isNotEmpty()) {
          val unusedValidLocations =
            sourceLocations
              .filter { location ->
                !location.used &&
                  location.element.startOffset != UNDEFINED_OFFSET &&
                  location.element.endOffset != UNDEFINED_OFFSET
              }
              .distinct()

          if (unusedValidLocations.isEmpty()) {
            null
          } else {
            var markedRepeatable = false
            val fileEntry = fileScope?.declaration?.fileEntry

            unusedValidLocations.joinToString(",") { location ->
              location.markUsed()

              val lineNumber = fileEntry?.getLineNumber(location.element.startOffset)
              val offset =
                if (location.element.startOffset < location.element.endOffset)
                  "@${location.element.startOffset}L${location.element.endOffset - location.element.startOffset}"
                else
                  "@${location.element.startOffset}"

              if (location.repeatable && !markedRepeatable) {
                markedRepeatable = true
                "*$lineNumber$offset"
              } else {
                "$lineNumber$offset"
              }
            }
          }
        }
        // sourceInformationEnabled == false || sourceLocations.isEmpty() == true
        else null

      open fun sourceLocationOf(call: IrElement): SourceLocation = SourceLocation(element = call)

      // Claude says:
      //   Coalescable은 "병합 가능한"이라는 뜻입니다. coalesce(병합하다)에서 파생된 형용사죠.
      class CoalescableGroupInfo(
        private val scope: BlockScope,
        private val realizeGroup: () -> Unit,
        private val makeEnd: () -> IrExpression,
      ) {
        var shouldRealize = false
        private var realized = false

        fun realize() {
          if (realized) return
          realized = true

          if (shouldRealize) {
            scope.realizeGroup(makeEnd = makeEnd)
            realizeGroup.invoke()
          } else {
            scope.realizeCoalescableChildren()
          }
        }
      }
    }

    class FunctionScope(
      val function: IrFunction,
      private val transformer: ComposableFunctionBodyTransformer,
    ) : BlockScope(name = "fun ${function.name.asString()}") {
      val isInlineLambda: Boolean
        get() = transformer.inlineLambdaInfo.isInlineLambda(function)

      val isCrossinlineLambda: Boolean
        get() = transformer.inlineLambdaInfo.isCrossinlineLambda(function)

      val isComposable: Boolean
        get() = composerParameter != null

      val inComposableCall: Boolean
        get() = (parent as? CallScope)?.expression?.let { call ->
          with(transformer) {
            call.isComposableCall() || call.isSyntheticComposableCall()
          }
        } == true

      override val isInComposable: Boolean
        get() =
          isComposable ||
            transformer.inlineLambdaInfo.preservesComposableScope(function) &&
            parent?.isInComposable == true

      override val functionScope: FunctionScope get() = this

      var composerParameter: IrValueParameter? = null
        private set

      override val nearestComposer: IrValueParameter?
        get() = composerParameter ?: super.nearestComposer

      val markerPreamble = mutableStatementContainer(context = transformer.context)
      private val intrinsicRememberFixups = mutableListOf<IntrinsicRememberFixup>()

      var hasInlineEarlyReturn: Boolean = false
      var hasAnyEarlyReturn: Boolean = false

      var defaultBitMaskValue: IrDefaultBitMaskValue? = null
        private set

      var changedBitMaskValue: IrChangedBitMaskValue? = null
        private set

      var dirty: IrChangedBitMaskValue? = null

      var realValueParamCount: Int = 0
        private set

      // slotCount will include the dispatchReceiver, extensionReceiver and context receivers.
      // slotCount에는 dispatchReceiver, extensionReceiver, context receivers가 모두 포함됩니다.
      var slotCount: Int = 0
        private set

      var outerGroupRequired = false

      val metrics: FunctionMetrics = transformer.metricsFor(function)
      private var marker: IrVariable? = null

      private var lastTemporaryIndex: Int = 0

      private val hasExtensionReceiver: Boolean =
        function.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }

      init {
        val defaultParams = mutableListOf<IrValueParameter>()
        val changedParams = mutableListOf<IrValueParameter>()

        for (param in function.parameters) {
          if (param.kind != IrParameterKind.Regular)
            continue

          val paramName = param.name.asString()
          when {
            paramName == ComposeNames.COMPOSER_PARAMETER.identifier -> composerParameter = param

            paramName.startsWith(ComposeNames.DEFAULT_PARAMETER.identifier) -> defaultParams += param

            paramName.startsWith(ComposeNames.CHANGED_PARAMETER.identifier) -> changedParams += param

            // 'if (param.kind != IrParameterKind.Regular) continue' 로직이 있는데,
            // "context_receiver_"를 추가로 검사하는 건 왜일까?
            paramName.startsWith($$"$context_receiver_") ||
              paramName.startsWith($$"$name$for$destructuring") ||
              paramName.startsWith($$"$noName_") ||
              paramName == $$"$this" -> Unit

            else -> realValueParamCount++
          }
        }

        slotCount = realValueParamCount
        slotCount += function.contextReceiverParametersCount

        if (function.extensionReceiverParameter != null) slotCount++
        if (function.dispatchReceiverParameter != null) {
          slotCount++
        } else if (function.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) {
          slotCount++
        }

        changedBitMaskValue = if (composerParameter != null) {
          transformer.IrChangedBitMaskValueImpl(
            changedParams = changedParams,
            trackedParameterCount = slotCount, // $changed는 모든 매개변수에 영향을 받음
          )
        } else {
          null
        }
        defaultBitMaskValue = if (defaultParams.isNotEmpty()) {
          transformer.IrDefaultBitMaskValueImpl(
            defaultParams = defaultParams,
            trackedParameterCount = function.contextReceiverParametersCount + realValueParamCount,
          )
        } else {
          null
        }
      }

      // MEMO 아래 두 개 변수는 위 init 블록이 실행된 후 초기화되어야 함

      val usedParams = BooleanArray(slotCount) { false }

      // 매개변수 순서: context/extension, regular, dispatch
      val trackedParameters: List<IrValueParameter> =
        buildList {
          function.parameters.fastForEach { param ->
            if (param.kind == IrParameterKind.Context || param.kind == IrParameterKind.ExtensionReceiver) {
              add(param)
            }
          }

          // $composer, $changed, $default, $force 필터링용 카운트로 추측됨
          var parameterCount = realValueParamCount
          function.parameters.fastForEach { param ->
            if (parameterCount > 0 && param.kind == IrParameterKind.Regular) {
              parameterCount--
              add(param)
            }
          }

          function.parameters.fastForEach { param ->
            if (param.kind == IrParameterKind.DispatchReceiver) {
              add(param)
            }
          }
        }

      init {
        if (
          isComposable &&
          (
            // We are interested in any object which has skippable function body and
            // is being able to capture values from outside scope. Technically, that
            // means we almost never skip in capture-less objects, but it is still more
            // correct /not/ to skip when its dispatcher receiver changes. In most
            // cases, we memoize these objects too (e.g fun interface) so the receiver
            // should === with the previous instances most of time.
            //
            // 우리는 리컴포지션 스킵 가능한 컴포저블을 포함하고, 외부 스코프의 값을 캡처할
            // 수 있는 객체에 관심이 있습니다. 기술적으로는 캡처가 없는 객체에서는 거의 생략하지
            // 않지만, dispatcher receiver가 변경되는 경우 생략하지 않는 것이 여전히 더 정확합니다.
            // 대부분의 경우, 이러한 객체들은 (예: 함수 인터페이스처럼) memoize되기 때문에
            // 리시버는 대부분 이전 인스턴스와 동일합니다 (===).
            //
            // STUDY "that means we almost never skip in capture-less objects"
            //  캡처가 없는 객체에서는 오히려 항상 리컴포지션 스킵되어야 하는 거 아닌가??
            function.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA ||
              function.dispatchReceiverParameter
                ?.type
                ?.classOrNull
                ?.owner
                ?.isLocal == true
            )
        ) {
          // in the case of a composable lambda/anonymous object, we want to make sure
          // the dispatch receiver is always marked as "used".
          //
          // 컴포저블 람다나 익명 객체의 경우, dispatch receiver가 항상 "사용됨"으로
          // 표시해야 합니다.
          usedParams[slotCount - 1] = true
        }
      }

      fun createMarker(): IrVariable {
        val currentMarker = marker
        if (currentMarker != null) return currentMarker

        val parent = parent
        return when {
          isInlineLambda && !isComposable && parent is CallScope -> {
            parent.createMarker()
          }
          else -> {
            val newMarker = transformer.irTemporaryVariable(
              value = transformer.irCurrentMarker(composerParameter = myComposer),
              name = getNameForTemporary(nameHint = "marker"),
            )
            markerPreamble.statements.add(newMarker)
            marker = newMarker
            newMarker
          }
        }
      }

      override fun sourceLocationOf(call: IrElement): SourceLocation {
        val parent = parent
        return if (isInlineLambda && parent is BlockScope) {
          parent.sourceLocationOf(call = call)
        } else {
          super.sourceLocationOf(call = call)
        }
      }

      override fun hasSourceInformation(sourceInformationEnabled: Boolean): Boolean =
        if (sourceInformationEnabled) {
          if (function.isLambda() && !isInlineLambda)
            super.hasSourceInformation(sourceInformationEnabled = true)
          else
            true
        } else {
          function.visibility.isPublicAPI
        }

      override fun calculateSourceInfo(sourceInformationEnabled: Boolean): String? =
        if (sourceInformationEnabled) {
          callInformation() +
            parameterInformation() +
            super.calculateSourceInfo(sourceInformationEnabled = true).orEmpty() +
            ":${function.sourceFileInformation()}"
        } else {
          if (function.visibility.isPublicAPI) {
            "${callInformation()}${parameterInformation()}"
          } else {
            null
          }
        }

      // 원래 이름: defaultIndexForSlotIndex
      fun defaultBitIndexForParamIndex(index: Int): Int =
      // STUDY defaultBitMaskValue의 trackedParameterCount는
      //  'function.contextReceiverParametersCount + realValueParamCount' 인데,
        //  왜 여기서는 context receiver가 아니라 extension receiver로 비교하는 걸까??
        if (hasExtensionReceiver) index - 1 else index

      fun getNameForTemporary(nameHint: String?): String {
        val index = nextTemporaryIndex()
        return if (nameHint != null) "tmp${index}_$nameHint" else "tmp$index"
      }

      fun recordIntrinsicRememberFixup(
        isMemoizedLambda: Boolean,
        args: List<IrExpression>,
        metas: List<CallArgumentMeta>,
        call: IrCall,
      ) {
        val dirtyMeta = metas.find { it.referencedParam?.dirty is IrChangedBitMaskVariable }

        if (dirtyMeta?.referencedParam?.dirty == this.dirty) {
          intrinsicRememberFixups.add(
            IntrinsicRememberFixup(
              isMemoizedLambda = isMemoizedLambda,
              args = args,
              metas = metas,
              call = call,
            ),
          )
        } else {
          // capturing dirty is only allowed from inline function context, which doesn't
          // have dirty params. If we encounter dirty that doesn't match mask from the
          // current function, it means that we should apply the fixup higher in the tree.
          //
          // STUDY "capturing dirty is only allowed from inline function context" ????
          //
          // dirty를 캡처하는 것은 인라인 함수 컨텍스트에서만 허용되며, 해당 컨텍스트에는
          // dirty 파라미터가 없습니다. 현재 함수의 마스크와 일치하지 않는 dirty를 발견한 경우,
          // 트리의 더 상위에서 fixup을 적용해야 함을 의미합니다.

          var scope = parent
          while (scope !is FunctionScope) scope = scope!!.parent

          scope.recordIntrinsicRememberFixup(
            isMemoizedLambda = isMemoizedLambda,
            args = args,
            metas = metas,
            call = call,
          )
        }
      }

      fun applyIntrinsicRememberInvalidFixups(
        invalidExpr: (
          isMemoizedLambda: Boolean,
          List<IrExpression>,
          List<CallArgumentMeta>,
        ) -> IrExpression,
      ) {
        intrinsicRememberFixups.fastForEach { fixup ->
          val invalid = invalidExpr(fixup.isMemoizedLambda, fixup.args, fixup.metas)

          // $composer.cache(invalid, calc)
          fixup.call.putValueArgument(0, invalid)
        }
      }

      private fun nextTemporaryIndex(): Int = lastTemporaryIndex++

      private fun parameterInformation(): String = function.parameterInformation()

      private fun callInformation(): String = function.callInformation()

      private class IntrinsicRememberFixup(
        val isMemoizedLambda: Boolean,
        val args: List<IrExpression>,
        val metas: List<CallArgumentMeta>,
        val call: IrCall,
      )
    }

    class ClassScope(name: Name) : Scope(name = "class ${name.asString()}")

    class PropertyScope(name: Name) : Scope(name = "val ${name.asString()}")

    class FieldScope(name: Name) : Scope(name = "field ${name.asString()}")

    class FileScope(val declaration: IrFile) : Scope(name = "file ${declaration.name}") {
      override val fileScope: FileScope get() = this
    }

    class LoopScope(val loop: IrLoop) : BlockScope(name = "loop") {
      private val jumpEndLocations = mutableListOf<(endExpr: IrExpression) -> Unit>()

      var needsGroupPerIteration = false
        private set

      override fun realizeEndCalls(makeEnd: () -> IrExpression) {
        super.realizeEndCalls(makeEnd)
        if (needsGroupPerIteration) {
          jumpEndLocations.fastForEach { endLocationLambda ->
            endLocationLambda.invoke(/* endExpr = */ makeEnd())
          }
          jumpEndLocations.clear()
        }
      }

      fun markJump(jump: IrBreakContinue, extraEndLocation: (endExpr: IrExpression) -> Unit) {
        if (jump.loop != loop) {
          super.markJump(extraEndLocation)
        } else {
          hasJump = true

          // if there is a continue jump in the loop, it means that the repeating
          // pattern of the call graph can differ per iteration, which means that we will
          // need to create a group for each iteration or else we could end up with slot
          // table misalignment.
          //
          // 루프에 continue 점프가 있는 경우, 호출 그래프의 반복 패턴이 반복 이터레이션에 따라
          // 달라질 수 있으므로, 각 반복마다 그룹을 생성해야 합니다. 그렇지 않으면 슬롯 테이블
          // 적재가 잘못될 수 있습니다.
          if (jump is IrContinue) needsGroupPerIteration = true

          jumpEndLocations.add(extraEndLocation)
        }
      }

      override fun sourceLocationOf(call: IrElement): SourceLocation =
        object : SourceLocation(call) {
          override val repeatable: Boolean
            // the calls in the group only repeat if the loop scope doesn't create a group per iteration.
            // 그룹 내의 호출은 루프 스코프가 반복마다 그룹을 생성하지 않는 경우에만 반복됩니다.
            get() = !needsGroupPerIteration
        }
    }

    class WhenScope : BlockScope(name = "when")

    class BranchScope : BlockScope(name = "branch")

    class CaptureScope : BlockScope(name = "capture") {
      var hasCapturedComposableCall = false
        private set

      fun markCapturedComposableCall() {
        hasCapturedComposableCall = true
      }

      override fun sourceLocationOf(call: IrElement): SourceLocation =
        object : SourceLocation(element = call) {
          override val repeatable: Boolean get() = true
        }
    }

    class ParametersScope : BlockScope(name = "parameters")

    class CallScope(
      val expression: IrCall,
      private val transformer: ComposableFunctionBodyTransformer,
    ) : Scope(name = "call") {
      override val isInComposable: Boolean
        get() = parent?.isInComposable == true

      var marker: IrVariable? = null
        private set

      fun createMarker(): IrVariable =
        marker ?: transformer.irTemporaryVariable(
          value = transformer.irCurrentMarker(composerParameter = myComposer),
          name = getNameForTemporary(nameHint = "marker"),
        )
          .also { marker = it }

      private fun getNameForTemporary(nameHint: String?): String =
        functionScope?.getNameForTemporary(nameHint = nameHint) ?: error("Expected to be in a function")
    }

    class ReturnScope(val expression: IrReturn) : BlockScope(name = "return") {
      override fun sourceLocationOf(call: IrElement): SourceLocation =
        when (val parent = parent) {
          is BlockScope -> parent.sourceLocationOf(call = call)
          else -> super.sourceLocationOf(call = call)
        }
    }
  }

  inner class IrDefaultBitMaskValueImpl(
    private val defaultParams: List<IrValueParameter>,
    private val trackedParameterCount: Int,
  ) : IrDefaultBitMaskValue {
    init {
      val actual = defaultParams.size
      val expected = defaultParamCount(valueParamCount = trackedParameterCount)

      require(actual == expected) {
        "Function with $trackedParameterCount params had $actual default params but expected $expected"
      }
    }

    override fun irGetBitAtIndex(index: Int): IrExpression {
      require(index <= trackedParameterCount) { "index > trackedParameterCount" }
      return irAnd(
        lhs = irGet(defaultParams[defaultParamIndex(index = index)]),
        rhs = irIntConst(0b1 shl defaultBitIndex(index = index))
      )
    }

    // $default 중에 0 비트는 실제 인자가 제공되었음이고, 1 비트는 실제 인자가
    // 제공되지 않았으니 기본 인자를 사용해야 함을 의미함.
    //
    // HasAnyProvided: $default 중 0 비트가 있고,
    // Unstable: 인자가 제공된 매개변수들 중 unstable한 매개변수가 있음
    override fun irHasAnyProvidedAndUnstable(unstable: BooleanArray): IrExpression {
      require(trackedParameterCount == unstable.size) { "trackedParametersCount != unstable.size" }

      val expressions = defaultParams.mapIndexed { defaultParamIndex, defaultParam ->
        val realParamIndexStart = defaultParamIndex /* (0부터 시작) */ * BITS_COUNT_PER_INT
        val realParamIndexEndInclusive = min(realParamIndexStart + BITS_COUNT_PER_INT, trackedParameterCount)

        val unstableMask = bitMask(*unstable.sliceArray(realParamIndexStart until realParamIndexEndInclusive))

        // $default and unstableMask will be different from unstableMask
        // if any parameters were *provided* AND *unstable*.
        //
        // $default와 unstableMask는 오직 최소한 하나의 파라미터가 제공되었고
        // 동시에 불안정(unstable) 할 경우에만 unstableMask와 다르게 됩니다.
        irNotEqual(
          // lhs로 계산되는 비트는 [인자 값이 제공되지 않았고, unstable한 인자](1 비트들) 임.
          lhs = irAnd(lhs = irGet(defaultParam), rhs = irIntConst(unstableMask)),

          // rhs 중 1인 비트는 unstable한 인자임.
          rhs = irIntConst(unstableMask),

          // lhs와 rhs가 같다면 [인자 값이 제공되지 않았고, unstable한 인자] == [unstable한 인자]가 됨.
          // 만약 lhs와 rhs가 다르다면 [인자 값이 제공되지 않았고] 부분이 다른 경우임.
          //
          // => "HasAnyProvided and Unstable"과 일치하는 상황
        )
      }

      return if (expressions.size == 1)
        expressions.single()
      else
        expressions.reduce { lhs, rhs -> irOrOr(lhs, rhs) }
    }

    override fun putAsValueArgumentIn(fn: IrFunctionAccessExpression, paramIndex: Int) {
      defaultParams.fastForEachIndexed { defaultParamIndex, defaultParam ->
        fn.putValueArgument(paramIndex + defaultParamIndex, irGet(defaultParam))
      }
    }
  }

  open inner class IrChangedBitMaskValueImpl(
    private val changedParams: List<IrValueDeclaration>,
    private val trackedParameterCount: Int,
  ) : IrChangedBitMaskValue {
    protected fun changedParamIndexForSlot(slot: Int): Int = slot / SLOTS_COUNT_PER_INT

    init {
      val actual = changedParams.size
      // passing in 0 for thisParams because slot count includes them.
      // slotCount에 thisParams가 포함되어 있기 때문에 thisParams에는 0을 전달합니다.
      val expected = changedParamCount(realValueParamCount = trackedParameterCount, thisParamCount = 0)
      require(actual == expected) {
        "Function with $trackedParameterCount params had $actual changed params but expected $expected"
      }
    }

    override var used: Boolean = false

    override val declarations: List<IrValueDeclaration>
      get() = changedParams

    override fun irGetLowBit(): IrExpression {
      used = true
      return irAnd(lhs = irGet(changedParams[0]), rhs = irIntConst(0b1))
    }

    // isolate: 격리하다, 분리하다
    // MEMO includeStableBit가 true일 때만 MSB가 1이 될 수 있음.
    //  만약 MSB가 1이면 unstable임을 의미함. irStableBitAtSlot() 참고!
    override fun irIsolateBitsAtSlot(slot: Int, includeStableBit: Boolean): IrExpression {
      used = true

      return irAnd(
        lhs = irGet(changedParams[changedParamIndexForSlot(slot = slot)]),
        rhs = irBitsForSlot(
          // Mask  (0b111) = Same(0b001) or Different(0b010) or Static(0b011) or Unknown(0b100)
          // Static(0b011) = Same(0b001) or Different(0b010) or Static(0b011)
          bits = if (includeStableBit) ParamState.Mask.bits /* 0b111 */ else ParamState.Static.bits, /* 0b011 */
          slot = slot,
        )
      )
    }

    override fun irStableBitAtSlot(slot: Int): IrExpression {
      used = true

      // ParamState.[Mask|Unknown] 제외하고는 모두 Stable한 상황임
      return irAnd(
        lhs = irGet(changedParams[changedParamIndexForSlot(slot = slot)]),
        // ParamState에서 [Mask|Unknown]만 MSB가 1임.
        //
        // 이 연산은 and로 진행되므로, irStableBitAtSlot() 값이 0이라면
        // 안정한 슬롯으로 볼 수 있음.
        rhs = irIntConst(ParamState.Unknown /* 0b100 */.bitsForSlot(slot = slot)),
      )
    }

    override fun irSlotAnd(slot: Int, bits: Int): IrExpression {
      used = true

      return irAnd(
        lhs = irGet(changedParams[changedParamIndexForSlot(slot = slot)]),
        rhs = irBitsForSlot(bits = bits, slot = slot),
      )
    }

    // The restart flag is always in the first parameter flags.
    // (or the implied changed parameter for 0 parameters)
    //
    // 재시작 플래그는 항상 첫 번째 파라미터 플래그에 있거나,
    // 파라미터가 0개일 경우에는 암시적인 변경 플래그에 존재합니다.
    override fun irRestartFlags(): IrExpression =
      // LSB가 제공됐다면 강제 리컴포지션임
      irAnd(lhs = irGet(changedParams[0]), rhs = irIntConst(0b1))

    override fun irHasDifferences(usedParams: BooleanArray): IrExpression {
      used = true
      require(usedParams.size == trackedParameterCount) { "usedParams.size != trackedParametersCount" }

      if (trackedParameterCount == 0) {
        // for 0 slots (no params), we can create a shortcut expression of just checking the
        // low-bit for non-zero. Since all of the higher bits will also be 0, we can just
        // simplify this to check if dirty is non-zero.
        //
        // 슬롯이 0개일 경우(즉, 파라미터가 없을 경우), 단순히 하위 비트가 0이 아닌지만
        // 확인하는 단축 표현식을 생성할 수 있습니다. 모든 상위 비트도 0이기 때문에,
        // 단순히 dirty 값이 0이 아닌지만 확인하면 됩니다.
        //
        // (부모 컴포저블의 매개변수 상태 정보($dirty)가 자식 컴포저블의 $changed로 제공됨)
        return irNotEqual(lhs = irGet(changedParams[0]), rhs = irIntConst(0b0))
      }

      val expressions = changedParams.mapIndexed { changedParamIndex, changedParam ->
        val realParamIndexStart = changedParamIndex /* (0부터 시작) */ * SLOTS_COUNT_PER_INT
        val realParamIndexEndInclusive = min(realParamIndexStart + SLOTS_COUNT_PER_INT, trackedParameterCount)

        // makes an int with each slot having 0b101 mask and the low bit being 0.
        // so for 3 slots, we would get 0b 101 101 101 0. This pattern is useful because
        // we can and + xor it with our $changed bitmask and it will only be non-zero
        // if any of the slots were DIFFERENT or UNCERTAIN or UNSTABLE. We _only_ use
        // this pattern for the slots where the body of the function actually uses that
        // parameter, otherwise we pass in 0b000 which will transfer none of the bits to the rhs.
        //
        // 각 슬롯에 0b101 마스크를 설정하고, 가장 하위 비트는 0으로 설정한 정수를 생성합니다.
        // 예를 들어 슬롯이 3개일 경우 0b 101 101 101 0이 됩니다. 이 패턴은 $changed 비트마스크와
        // AND 및 XOR 연산을 했을 때, 슬롯 중 하나라도 변경되었거나(=DIFFERENT), 불확실하거나(=UNCERTAIN),
        // 불안정한(=UNSTABLE) 경우에만 0이 아닌 값을 반환하므로 유용합니다. 이 패턴은 함수 본문에서
        // 해당 파라미터를 실제로 사용하는 슬롯에만 적용되며, 그렇지 않은 경우에는 0b000을 전달하여
        // 우변(right-hand side)에 아무 비트도 전달되지 않도록 합니다.

        val lhsMask = if (FeatureFlag.StrongSkipping.enabled) 0b001 /* Same */ else 0b101 /* Same or Unknown */
        val lhs = (realParamIndexStart until realParamIndexEndInclusive).fold(0b000) { mask, realParamSlotIndex ->
          if (usedParams[realParamSlotIndex]) {
            mask or bitsForSlot(bits = lhsMask, slotIndex = realParamSlotIndex)
          } else {
            mask
          }
        }

        // we _only_ use this pattern for the slots where the body of the function
        // actually uses that parametser, otherwise we pass in 0b000 which will transfer
        // none of the bits to the rhs.
        //
        // 이 패턴은 함수 본문에서 해당 파라미터를 실제로 사용하는 슬롯에만 사용하며,
        // 그렇지 않은 경우에는 0b000을 전달하여 우변으로 아무 비트도 전달되지 않도록 합니다.
        val rhs = (realParamIndexStart until realParamIndexEndInclusive).fold(0b000) { mask, realParamSlotIndex ->
          if (usedParams[realParamSlotIndex]) {
            mask or bitsForSlot(bits = 0b001 /* Same */, slotIndex = realParamSlotIndex)
          } else {
            mask
          }
        }

        // we use this pattern with the low bit set to 1 in the "and", and the low bit set to 0
        // for the "xor". This means that if the low bit was set, we will get 1 in the resulting
        // low bit. Since we use this calculation to determine if we need to run the body of the
        // function, this is exactly what we want.
        //
        // 이 패턴은 and 연산에서는 하위 비트를 1로 설정하고, xor 연산에서는 하위 비트를 0으로 설정하여
        // 사용합니다. 이는 하위 비트가 설정되어 있으면, 결과의 하위 비트가 1이 되도록 하기 위함입니다.
        // 우리는 이 계산을 함수 본문을 실행할 필요가 있는지를 판단하는 데 사용하기 때문에, 이것이
        // 우리가 원하는 정확한 동작입니다.

        // if the rhs is 0, that means that none of the parameters ended up getting used
        // in the body of the function which means we can simplify the expression quite a
        // bit. In this case we just care about if the low bit is non-zero.
        //
        // 오른쪽 값(rhs)이 0이라는 것은 함수 본문에서 어떤 파라미터도 사용되지 않았다는 의미이므로,
        // 식을 꽤 단순화할 수 있습니다. 이 경우 우리는 하위 비트가 0이 아닌지만 확인하면 됩니다.
        if (rhs == 0) {
          // LSB가 제공됐다면(= 0이 아님) 강제 리컴포지션임 -> 'irHasDifferences' is true
          irNotEqual(
            lhs = irAnd(lhs = irGet(changedParam), rhs = irIntConst(0b1)),
            rhs = irIntConst(0b0),
          )
        } else {
          // ($changed and (0b 101 ... 101 1)) != (0b 001 ... 001 0)
          //
          // $changed가 ParamState.Same으로만 구성되지 않았다면, 'irHasDifferences'는 true임
          irNotEqual(
            // $changed에서 ParamState.Same, ParamState.Unknown, LSB만 남기는 작업
            lhs = irAnd(
              lhs = irGet(changedParam),
              rhs = irIntConst(
                // if (FeatureFlag.StrongSkipping.enabled) 0b001 /* Same */ else 0b101 /* Same or Unknown */
                // 강력 건너뛰기가 활성화된 경우, Uncertain과 Unknown도 changed로 비교하여 Same이 될 수 있음
                lhs
                  or 0b1, // LSB를 무조건 1로 설정함 -> 근데 $changed에 and 연산이라, $changed의 LSB를 따라가게 됨
              )
            ),
            // ParamState.Same으로만 구성된 비트 (LSB 없음)
            rhs = irIntConst(rhs /* 0b001_0 (Same, LSB가 항상 0) */),
          )
        }
      }

      return if (expressions.size == 1)
        expressions.first()
      else
        expressions.reduce { lhs, rhs -> irOrOr(lhs = lhs, rhs = rhs) }
    }

    override fun irCopyToDirtyVariable(
      nameHint: String?,
      isVar: Boolean,
      exactName: Boolean,
    ): IrChangedBitMaskVariable {
      used = true
      val variables = changedParams.mapIndexed { index, param ->
        IrVariableImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          // We label "dirty" as a defined variable instead of a temporary, so that it
          // is properly stored in the locals table and discoverable by debuggers. The
          // dirty variable encodes information that could be useful for tooling to
          // interpret.
          //
          // "dirty"를 임시변수가 아닌 정의된 변수로 라벨링하는 이유는, 디버거에서
          // 지역 변수 테이블에 올바르게 저장되고 탐색될 수 있도록 하기 위함입니다.
          // 이 변수는 툴링(tooling)이 해석하는 데 유용할 수 있는 정보를 인코딩하고 있습니다.
          origin = IrDeclarationOrigin.DEFINED,
          symbol = IrVariableSymbolImpl(),
          name = Name.identifier(if (index == 0) $$"$dirty" else $$"$dirty$$index"),
          type = param.type,
          isVar = isVar,
          isConst = false,
          isLateinit = false,
        ).apply {
          parent = currentFunctionScope.function.parent
          initializer = irGet(param)
        }
      }
      return IrChangedBitMaskVariableImpl(
        variables = variables,
        trackedParameterCount = trackedParameterCount,
      )
    }

    override fun putAsValueArgumentInWithLowBit(
      fn: IrFunctionAccessExpression,
      paramIndex: Int,
      lowBit: Boolean,
    ) {
      used = true
      changedParams.fastForEachIndexed { changedParamIndex, changedParam ->
        fn.putValueArgument(
          paramIndex + changedParamIndex,
          if (changedParamIndex == 0) {
            irUpdateChangedFlags(
              // lowBit가 true라면 changedParam의 LSB를 1로 설정함
              expression = irIntOr(
                lhs = irGet(changedParam),
                rhs = irIntConst(if (lowBit) 0b1 else 0b0),
              ),
            )
          } else {
            irUpdateChangedFlags(expression = irGet(changedParam))
          },
        )
      }
    }

    private fun irUpdateChangedFlags(expression: IrExpression): IrExpression {
      val updateChangedFlagsFunction = updateChangedFlagsFunction ?: return expression

      return irCall(updateChangedFlagsFunction).also { it.putValueArgument(0, expression) }
    }

    override fun irShiftBits(fromSlot: Int, toSlot: Int): IrExpression {
      used = true

      val fromSlotAdjusted = fromSlot % SLOTS_COUNT_PER_INT
      val toSlotAdjusted = toSlot % SLOTS_COUNT_PER_INT

      val bitsToShiftLeft = (toSlotAdjusted - fromSlotAdjusted) * BITS_COUNT_PER_SLOT
      val fromSlotValue = irGet(changedParams[changedParamIndexForSlot(slot = fromSlot)])

      if (bitsToShiftLeft == 0) return fromSlotValue

      val int = context.irBuiltIns.intType
      val shiftLeft = int.binaryOperator(name = OperatorNameConventions.SHL, paramType = int)
      val shiftRight = int.binaryOperator(name = OperatorNameConventions.SHR, paramType = int)

      return irCall(
        symbol = if (bitsToShiftLeft > 0) shiftLeft else shiftRight,
        origin = null,
        dispatchReceiver = fromSlotValue,
        extensionReceiver = null,
        /* args = */ irIntConst(abs(bitsToShiftLeft)),
      )
    }
  }

  inner class IrChangedBitMaskVariableImpl(
    private val variables: List<IrVariable>,
    trackedParameterCount: Int,
  ) :
    IrChangedBitMaskVariable,
    IrChangedBitMaskValueImpl(
      changedParams = variables,
      trackedParameterCount = trackedParameterCount,
    ) {
    override fun getDirtyVariables(): List<IrStatement> = variables

    override fun irOrSetBitsAtSlot(slot: Int, value: IrExpression): IrExpression {
      used = true

      val variable = variables[changedParamIndexForSlot(slot)]
      return irSet(
        variable = variable,
        value = irIntOr(lhs = irGet(variable), rhs = value),
      )
    }

    override fun irSetSlotUncertain(slot: Int): IrExpression {
      used = true

      val variable = variables[changedParamIndexForSlot(slot)]
      return irSet(
        variable = variable,
        value = irAnd(
          lhs = irGet(variable),
          // ParamState.Mask == 111 (2)
          // ParamState.Uncertain == 000 (2)
          //
          // ParamState.Mask.bitsForSlot(slot = 2) ==> 111 000 000 0
          // ParamState.Mask.bitsForSlot(slot = 2).inv() ==> 000 111 111 1
          //
          // ParamState.Uncertain.bitsForSlot(slot = 2) ==> 000 000 000 0
          //
          // Mask.inv()와 Uncertain은 동일한 비트이지만, bitsForSlot을 했을 때는
          // 추가되는 선행 비트들에 차이가 생김.
          //
          // 이 로직은 '$dirty and 0b000_111_111_1'으로 동작함. and 연산이므로
          // 현재 슬롯은 다 0으로 지우고, 나머지 비트들은 그대로 남기는 듯!
          //
          // "현재 슬롯은 다 0으로 지우고" -> 결국 ParamState.Uncertain와 동일한
          //                                  비트로 남게 됨!!
          rhs = irIntConst(ParamState.Mask.bitsForSlot(slot = slot).inv()),
        ),
      )
    }
  }

  private class SourceInfoFixup(val call: IrCall, val index: Int, val scope: Scope.BlockScope)
}

private fun String.replacePrefix(prefix: String, replacement: String): String =
  if (startsWith(prefix)) replacement + substring(prefix.length) else this

private fun IrFunction.isLambda(): Boolean =
// There is probably a better way to determine this, but if there is, it isn't obvious.
  // 더 나은 방법이 있을 가능성은 있지만, 있다 해도 명확하진 않습니다.
  name == SpecialNames.ANONYMOUS

inline fun <A, B, C> forEachWith(a: List<A>, b: List<B>, c: List<C>, fn: (A, B, C) -> Unit) {
  for (i in a.indices) {
    fn(a[i], b[i], c[i])
  }
}

inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
  for (i in indices) {
    val item = get(i)
    action(item)
  }
}

inline fun <T> List<T>.fastForEachIndexed(action: (index: Int, T) -> Unit) {
  for (i in indices) {
    val item = get(i)
    action(i, item)
  }
}

inline fun <T> Array<out T>.fastForEachIndexed(action: (index: Int, T) -> Unit) {
  for (i in indices) {
    val item = get(i)
    action(i, item)
  }
}

private fun IrType.isClassType(fqName: FqNameUnsafe, hasQuestionMark: Boolean? = null): Boolean {
  if (this !is IrSimpleType) return false
  if (hasQuestionMark != null && this.isMarkedNullable() == hasQuestionMark) return false
  return classifier.isClassWithFqName(fqName)
}

private fun IrType.isNullableClassType(fqName: FqNameUnsafe): Boolean =
  this.isClassType(fqName = fqName, hasQuestionMark = true)

fun IrType.isNullableUnit(): Boolean = this.isNullableClassType(StandardNames.FqNames.unit)
fun IrType.isUnitOrNullableUnit(): Boolean = this.isUnit() || this.isNullableUnit()

internal object UNINITIALIZED_VALUE

private fun mutableStatementContainer(context: IrPluginContext): IrContainerExpression =
// NOTE(lmr): It's important to use IrComposite here so that we don't introduce any new scopes.
  //          새로운 스코프를 도입하지 않도록 하기 위해 여기서는 반드시 IrComposite를 사용하는 것이 중요합니다.
  IrCompositeImpl(
    startOffset = UNDEFINED_OFFSET,
    endOffset = UNDEFINED_OFFSET,
    type = context.irBuiltIns.unitType,
  )

private fun IrFunction.callInformation(): String =
  buildString {
    append('C')
    if (isInline) append('C')
    if (!isLambda()) {
      append('(')
      append(name.asString())
      append(')')
    }
  }

// Parameter information is an index from the sorted order of the parameters to the
// actual order. This is used to reorder the fields of the lambda class generated for
// restart lambdas into parameter order. If all the parameters are in sorted order
// with no inline classes then no additional information is necessary. This means
// that parameter-less or single parameter functions with no inline classes never
// need additional information and two parameter functions are only 50% likely to
// need ordering information which is, if needed, very short ("1"). The encoding is as
// follows,
//
//   parameters: (parameter|run) ("," parameter | run)*
//   parameter: sorted-index [":" inline-class]
//   sorted-index: <number>
//   inline-class: <chars not "," or "!">
//   run: "!" <number>
//
//   where
//     sorted-index:  the index of the parameter's name in the sorted list of
//                    parameter names,
//     inline-class:  the fully qualified name of the inline class using "c#" as a
//                    short-hand for "androidx.compose.".
//     run:           The number of parameter that are in sequence assuming the
//                    previously selected parameters are removed from the sorted order.
//                    For example, "!5" at the beginning of the list is equivalent to
//                    "0,1,2,3,4" and "3!4" is equivalent to "3,0,1,2,4". If there
//                    are 9 parameters "3,4!2,6,8" is equivalent to "3,4,0,1,6,8,2,
//                    5,6,7".
//
// There is an implied "!n" (where n is the number of remaining parameters) at the end
// of the parameter information that implies the rest of the parameters are in order.
// If the parameter information is missing it implies "P()" which implies all the
// parameters are in sorted order.
//
//
//
// 매개변수 정보는 정렬된 순서의 인덱스를 실제 순서로 매핑한 것입니다. 이 정보는
// 재시작 람다(restart lambda)를 위해 생성된 람다 클래스의 필드를 매개변수 순서로
// 재정렬하는 데 사용됩니다. 모든 매개변수가 정렬된 순서로 되어 있고 인라인 클래스가
// 없다면 추가 정보는 필요하지 않습니다. 즉, 매개변수가 없거나 인라인 클래스가 없는
// 단일 매개변수 함수는 추가 정보가 전혀 필요하지 않으며, 두 개의 매개변수를 가진
// 함수는 절반의 확률로만 정렬 정보가 필요하고, 필요하더라도 그 길이는 매우 짧습니다(예: "1").
// 인코딩 방식은 다음과 같습니다:
//
//    parameters: (parameter | run) ("," parameter | run)*
//    parameter: sorted-index [":" inline-class]
//    sorted-index: <숫자>
//    inline-class: <"," 또는 "!"를 제외한 문자열>
//    run: "!" <숫자>
//
// 각 항목의 의미:
//
//    - sorted-index: 정렬된 파라미터 이름 목록에서 해당 파라미터의 이름이 위치한 인덱스
//    - inline-class: 인라인 클래스의 fully qualified name. "androidx.compose."는 "c#"로 축약 가능.
//    - run:          앞에서 선택된 매개변수들을 정렬된 순서에서 제거한 후, 나머지 매개변수들 중에서
//                    연속된 개수를 의미합니다. 예를 들어, "!5"가 리스트의 맨 앞에 있으면 "0,1,2,3,4"와 같고,
//                    "3!4"는 "3,0,1,2,4"와 같습니다. 매개변수가 9개 있을 경우 "3,4!2,6,8"은
//                    "3,4,0,1,6,8,2,5,6,7"과 같습니다.
//
// 파라미터 정보의 끝에는 암묵적으로 "!n"(n은 남은 파라미터 수)을 포함하고 있으며,
// 이는 나머지 파라미터들이 정렬된 순서대로 있다는 것을 의미합니다. 파라미터 정보가
// 생략된 경우 "P()"를 의미하며, 이는 모든 파라미터가 정렬된 순서에 있다는 뜻입니다.
private fun IrFunction.parameterInformation(): String {
  val builder = StringBuilder("P(")
  val parameters = valueParameters.filter { parameter ->
    !parameter.name.asString().startsWith('$')
  }
  val sortIndex = mapOf(
    *parameters
      .mapIndexed { index, parameter -> index to parameter }
      .sortedBy { it.second.name.asString() }
      .mapIndexed { sortIndex, originalIndex -> originalIndex.first to sortIndex }
      .toTypedArray(),
  )

  val expectedIndexes = MutableList(parameters.size) { it }
  var run = 0
  var parameterEmitted = false

  fun emitRun(originalIndex: Int) {
    if (run > 0) {
      builder.append('!')
      if (originalIndex < parameters.size - 1) {
        builder.append(run)
      }
      run = 0
    }
  }

  parameters.fastForEachIndexed { originalIndex, parameter ->
    if (
      expectedIndexes.first() == sortIndex[originalIndex] &&
      !parameter.type.isInlineClassType()
    ) {
      run++
      expectedIndexes.removeAt(0)
    } else {
      emitRun(originalIndex)

      if (originalIndex > 0) builder.append(',')
      val index = sortIndex[originalIndex] ?: error("missing index $originalIndex")

      builder.append(index)
      expectedIndexes.remove(index)

      if (parameter.type.isInlineClassType()) {
        parameter.type.getClass()?.fqNameWhenAvailable?.let { fqName ->
          builder.append(':')
          builder.append(fqName.asString().replacePrefix("androidx.compose.", "c#"))
        }
      }

      parameterEmitted = true
    }
  }
  builder.append(')')

  return if (parameterEmitted) builder.toString() else ""
}

private fun IrFunction.packageName(): String? {
  var parent = parent
  while (true) {
    when (parent) {
      is IrPackageFragment -> return parent.packageFqName.asString()
      is IrDeclaration -> parent = parent.parent
      else -> break
    }
  }
  return null
}

private fun IrFunction.packageHash(): Int =
  packageName()?.fold(0) { hash, current -> hash * 31 + current.code }?.absoluteValue ?: 0

private fun IrFunction.sourceFileInformation(): String {
  val hash = packageHash()
  if (hash != 0) return "${file.name}#${hash.toString(36)}"
  return file.name
}
