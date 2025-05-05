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

import androidx.compose.compiler.plugins.kotlin.COMPOSE_PLUGIN_ID
import androidx.compose.compiler.plugins.kotlin.ComposeCallableIds
import androidx.compose.compiler.plugins.kotlin.ComposeClassIds
import androidx.compose.compiler.plugins.kotlin.ComposeFqNames
import androidx.compose.compiler.plugins.kotlin.ComposeMetadata
import androidx.compose.compiler.plugins.kotlin.ComposeNames
import androidx.compose.compiler.plugins.kotlin.FeatureFlag
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.FunctionMetrics
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.ComposeWritableSlices
import androidx.compose.compiler.plugins.kotlin.analysis.KnownStableConstructs
import androidx.compose.compiler.plugins.kotlin.analysis.Stability
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.analysis.isUncertain
import androidx.compose.compiler.plugins.kotlin.analysis.knownStable
import androidx.compose.compiler.plugins.kotlin.analysis.knownUnstable
import androidx.compose.compiler.plugins.kotlin.irTrace
import androidx.compose.compiler.plugins.kotlin.lower.hiddenfromobjc.hiddenFromObjCClassId
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrMetadataSourceOwner
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.declarations.inlineClassRepresentation
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhileLoopImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.interpreter.hasAnnotation
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.DeepCopyTypeRemapper
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.remapTypeParameters
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.kotlin.computeJvmDescriptor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.exceptions.rethrowIntellijPlatformExceptionIfNeeded

abstract class AbstractComposeLowering(
  val context: IrPluginContext,
  val metrics: ModuleMetrics,
  val stabilityInferencer: StabilityInferencer,
  private val featureFlags: FeatureFlags,
) : IrElementTransformerVoid(), ModuleLoweringPass {
  protected val builtIns = context.irBuiltIns

  protected val composerIrClass =
    context.referenceClass(ComposeClassIds.Composer)?.owner
      ?: error("Cannot find the Composer class in the classpath")

  protected val composableIrClass =
    context.referenceClass(ComposeClassIds.Composable)?.owner
      ?: error("Cannot find the Composable annotation class in the classpath")

  private val changedFunction =
    composerIrClass.functions
      .first {
        it.name.identifier == "changed" &&
          it.valueParameters.first().type.isNullableAny()
      }

  private val changedInstanceFunction =
    composerIrClass.functions
      .firstOrNull {
        it.name.identifier == "changedInstance" &&
          it.valueParameters.first().type.isNullableAny()
      }
      ?: changedFunction

  private val startReplaceFunction by guardedLazy {
    composerIrClass.functions.firstOrNull {
      it.name.identifier == "startReplaceGroup" && it.valueParameters.size == 1
    }
      ?: composerIrClass.functions.first {
        it.name.identifier == "startReplaceableGroup" && it.valueParameters.size == 1
      }
  }

  private val endReplaceFunction by guardedLazy {
    composerIrClass.functions.firstOrNull {
      it.name.identifier == "endReplaceGroup" && it.valueParameters.isEmpty()
    }
      ?: composerIrClass.functions.first {
        it.name.identifier == "endReplaceableGroup" && it.valueParameters.isEmpty()
      }
  }

  private val changedPrimitiveFunctions by guardedLazy {
    composerIrClass
      .functions
      .filter { it.name.identifier == "changed" }
      .mapNotNull { fn ->
        fn.valueParameters.first()
          .type
          .toPrimitiveType()
          ?.let { primitive -> primitive to fn }
      }
      .toMap()
  }

  fun getTopLevelClass(classId: ClassId): IrClassSymbol {
    return getTopLevelClassOrNull(classId)
      ?: error("Class not found in the classpath: ${classId.asSingleFqName()}")
  }

  fun getTopLevelClassOrNull(classId: ClassId): IrClassSymbol? {
    return context.referenceClass(classId)
  }

  fun getTopLevelFunction(callableId: CallableId): IrSimpleFunctionSymbol {
    return getTopLevelFunctionOrNull(callableId)
      ?: error("Function not found in the classpath: ${callableId.asSingleFqName()}")
  }

  fun getTopLevelFunctionOrNull(callableId: CallableId): IrSimpleFunctionSymbol? {
    return context.referenceFunctions(callableId).firstOrNull()
  }

  fun getTopLevelFunctions(callableId: CallableId): List<IrSimpleFunctionSymbol> {
    return context.referenceFunctions(callableId).toList()
  }

  fun getTopLevelPropertyGetter(callableId: CallableId): IrFunctionSymbol {
    val propertySymbol = context.referenceProperties(callableId).firstOrNull()
      ?: error("Property was not found ${callableId.asSingleFqName()}")
    return propertySymbol.owner.getter!!.symbol
  }

  val FeatureFlag.enabled get() = featureFlags.isEnabled(this)

  fun metricsFor(function: IrFunction): FunctionMetrics =
    context.irTrace[ComposeWritableSlices.FUNCTION_METRICS, function]
      ?: metrics.makeFunctionMetrics(function).also {
        context.irTrace.record(ComposeWritableSlices.FUNCTION_METRICS, function, it)
      }

  fun IrType.unboxTypeIfInlineOrDefault() = unboxTypeIfInlineOrNull() ?: this

  // 원시타입이 아니라면 nullable로 만듦 -> default parameter의 구현이 nullable parameter로 되나?
  fun IrType.defaultParameterType(): IrType {
    val type = this
    val constructorAccessible =
      !type.isPrimitiveType() && type.classOrNull?.owner?.primaryConstructor != null

    return when {
      type.isPrimitiveType() -> type
      type.isInlineClassType() -> {
        when {
          context.platform.isJvm() || constructorAccessible -> {
            if (type.unboxTypeIfInlineOrDefault().isPrimitiveType()) {
              type
            } else {
              type.makeNullable()
            }
          }
          else -> {
            // k/js and k/native: private constructors of value classes can be not accessible.
            // Therefore it won't be possible to create a "fake" default argument for calls.
            // Making it nullable allows to pass null.
            type.makeNullable()
          }
        }
      }
      else -> type.makeNullable()
    }
  }

  fun IrType.replaceArgumentsWithStarProjections(): IrType =
    when (this) {
      is IrSimpleType -> IrSimpleTypeImpl(
        classifier = classifier,
        hasQuestionMark = isMarkedNullable(),
        arguments = List(arguments.size) { IrStarProjectionImpl },
        annotations = annotations,
        abbreviation = abbreviation,
      )
      else -> this
    }

  // NOTE(lmr): This implementation mimics the kotlin-provided unboxInlineClass method, except
  // this one makes sure to bind the symbol if it is unbound, so is a bit safer to use.
  //
  // mimics: 모방하다, 흉내내다
  //
  // 이 구현은 kotlin에서 제공하는 unboxInlineClass 메서드를 모방하지만, 심볼이 바인딩되지 않은
  // 경우 반드시 바인딩하므로 사용하기에 조금 더 안전합니다.
  fun IrType.unboxTypeIfInlineOrNull(): IrType? {
    val klass = classOrNull?.owner ?: return null
    val representation = klass.inlineClassRepresentation ?: return null
    if (!isInlineClassType()) return null

    // TODO: Apply type substitutions
    // underlying: 근본적인, (다른 것의) 밑에 있는
    val underlyingType = representation.underlyingType.unboxTypeIfInlineOrDefault()
    if (!isNullable()) return underlyingType
    if (underlyingType.isNullable() || underlyingType.isPrimitiveType())
      return null
    return underlyingType.makeNullable()
  }

  protected fun IrExpression.coerceUnboxedTypeCallIfInlineOrDefault(): IrExpression {
    if (!type.isNullable() && type.isInlineClassType()) {
      if (context.platform.isJvm()) {
        return unsafeCoerceIntrinsicCall(
          argument = this,
          from = type,
          to = type.unboxTypeIfInlineOrDefault()
        ).coerceUnboxedTypeCallIfInlineOrDefault()
      }
    }
    return this
  }

  fun IrAnnotationContainer.hasComposableAnnotation(): Boolean {
    return hasAnnotation(ComposeFqNames.Composable)
  }

  fun IrCall.isInvoke(): Boolean {
    if (origin == IrStatementOrigin.INVOKE)
      return true
    val function = symbol.owner
    return function.name == OperatorNameConventions.INVOKE &&
      function.parentClassOrNull?.defaultType?.let {
        it.isFunction() || it.isSyntheticComposableFunction()
      } ?: false
  }

  fun IrCall.isComposableLambdaInvoke(): Boolean {
    if (!isInvoke()) return false
    // ComposerParamTransformer replaces composable function types of the form
    // `@Composable Function1<T1, T2>` with ordinary functions with extra parameters, e.g.,
    // `Function3<T1, Composer, Int, T2>`. After this lowering runs we have to check the
    // `attributeOwnerId` to recover the original type.
    //
    // ComposerParamTransformer는 `@Composable Function1<T1, T2>` 형식의 컴포저블 함수 유형을
    // 추가 파라미터가 있는 일반 함수(예: `Function3<T1, Composer, Int, T2>`)로 대체합니다.
    // 이렇게 낮추기를 실행한 후에는 원래 유형을 복구하기 위해 `attributeOwnerId`를 확인해야 합니다.
    //
    // `composableLambdaReceiver.invoke()`니까 `composableLambdaReceiver`를 가져와야 함
    val receiver = dispatchReceiver?.let { it.attributeOwnerId as? IrExpression ?: it }
    return receiver?.type?.let {
      it.hasComposableAnnotation() || it.isSyntheticComposableFunction()
    } ?: false
  }

  fun IrCall.isComposableCall(): Boolean {
    return symbol.owner.hasComposableAnnotation() || isComposableLambdaInvoke()
  }

  fun IrCall.isSyntheticComposableCall(): Boolean {
    return context.irTrace[ComposeWritableSlices.IS_SYNTHETIC_COMPOSABLE_CALL, this] == true
  }

  fun IrCall.isComposableSingletonGetter(): Boolean {
    return context.irTrace[ComposeWritableSlices.IS_COMPOSABLE_SINGLETON, this] == true
  }

  fun IrClass.isComposableSingletonClass(): Boolean {
    return context.irTrace[ComposeWritableSlices.IS_COMPOSABLE_SINGLETON_CLASS, this] == true
  }

  // 오직 [unstable: 1 000]의 조합만 남기는?
  //
  // NOTE unstable: 1 000 -> 8
  //      stable: 0 000 -> 0
  fun Stability.irStabilityBitsExpression(
    resolveTypeParameter: (IrTypeParameter) -> IrExpression? = { null },
    reportUnknownStability: (IrClass) -> Unit = {},
  ): IrExpression? =
    when (this) {
      is Stability.Combined -> {
        // [unstable: 1 000]만 남기면 됨 -> null은 무시함
        val exprs = elements.mapNotNull { it.irStabilityBitsExpression(resolveTypeParameter, reportUnknownStability) }
        when {
          exprs.size != elements.size -> null
          exprs.isEmpty() -> irIntConst(StabilityBits.STABLE.bitsForSlot(0) /* -> 0 000 */)
          exprs.size == 1 -> exprs.first()
          // [unstable: 1 000] or [stable: 0 000] => [unstable: 1 000]
          else -> exprs.reduce { a, b -> irIntOr(a, b) }
        }
      }

      is Stability.Certain ->
        if (stable)
          irIntConst(StabilityBits.STABLE.bitsForSlot(0) /* -> 0 000 */)
        else
          null

      is Stability.Parameter -> resolveTypeParameter(typeParameter)

      is Stability.Runtime -> {
        val stabilityExpr = declaration.getRuntimeStabilityValue()
        if (stabilityExpr == null) reportUnknownStability(declaration)
        stabilityExpr
      }

      is Stability.Unknown -> null
    }

  // set the bit at a certain index
  private fun Int.withBit(index: Int, value: Boolean): Int =
    if (value) {
      this or (1 shl index)
    } else {
      this and (1 shl index).inv()
    }

  // create a bitmask with the following bits
  protected fun bitMask(vararg values: Boolean): Int =
    values.foldIndexed(0) { index, acc, bit -> acc.withBit(index, bit) }

  protected fun irGetBit(param: IrDefaultBitMaskValue, index: Int): IrExpression {
    // value and (1 shl index) != 0
    return irNotEqual(
      lhs = param.irIsolateBitAtIndex(index),
      rhs = irIntConst(0)
    )
  }

  protected fun irSet(variable: IrValueDeclaration, value: IrExpression): IrExpression {
    return IrSetValueImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.unitType,
      symbol = variable.symbol,
      value = value,
      origin = null
    )
  }

  protected fun irCall(
    symbol: IrFunctionSymbol,
    origin: IrStatementOrigin? = null,
    dispatchReceiver: IrExpression? = null,
    extensionReceiver: IrExpression? = null,
    vararg args: IrExpression,
  ): IrCallImpl {
    return IrCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = symbol.owner.returnType,
      symbol = symbol as IrSimpleFunctionSymbol,
      typeArgumentsCount = symbol.owner.typeParameters.size,
      origin = origin
    ).also {
      if (dispatchReceiver != null) it.dispatchReceiver = dispatchReceiver
      if (extensionReceiver != null) it.extensionReceiver = extensionReceiver
      args.forEachIndexed { index, arg ->
        it.putValueArgument(index, arg)
      }
    }
  }

  protected fun IrType.binaryOperator(name: Name, paramType: IrType): IrFunctionSymbol =
    context.symbols.getBinaryOperator(name = name, lhsType = this, rhsType = paramType)

  protected fun irAnd(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
    return irCall(
      symbol = lhs.type.binaryOperator(name = OperatorNameConventions.AND, paramType = rhs.type),
      origin = null,
      dispatchReceiver = lhs,
      extensionReceiver = null,
      rhs, // argument
    )
  }

  protected fun irIntOr(lhs: IrExpression, rhs: IrExpression): IrExpression {
    if (rhs is IrConst && rhs.value == 0) return lhs
    if (lhs is IrConst && lhs.value == 0) return rhs
    val int = context.irBuiltIns.intType
    return irCall(
      symbol = int.binaryOperator(name = OperatorNameConventions.OR, paramType = int),
      origin = null,
      dispatchReceiver = lhs,
      extensionReceiver = null,
      rhs, // argument
    )
  }

  protected fun irBooleanOr(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
    val boolean = context.irBuiltIns.booleanType
    return irCall(
      symbol = boolean.binaryOperator(name = OperatorNameConventions.OR, paramType = boolean),
      origin = null,
      dispatchReceiver = lhs,
      extensionReceiver = null,
      rhs, // argument
    )
  }

  protected fun irOrOr(lhs: IrExpression, rhs: IrExpression): IrExpression =
    IrWhenImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      origin = IrStatementOrigin.OROR,
      type = context.irBuiltIns.booleanType,
      branches = listOf(
        IrBranchImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          condition = lhs,
          result = irBooleanConst(true),
        ),
        IrElseBranchImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          condition = irBooleanConst(true),
          result = rhs,
        )
      ),
    )

  protected fun irAndAnd(lhs: IrExpression, rhs: IrExpression): IrExpression =
    IrWhenImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      origin = IrStatementOrigin.ANDAND,
      type = context.irBuiltIns.booleanType,
      branches = listOf(
        IrBranchImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          condition = lhs,
          result = rhs,
        ),
        IrElseBranchImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          condition = irBooleanConst(true),
          result = irBooleanConst(false),
        )
      ),
    )

  protected fun irIntXor(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
    val int = context.irBuiltIns.intType
    return irCall(
      symbol = int.binaryOperator(OperatorNameConventions.XOR, int),
      origin = null,
      dispatchReceiver = lhs,
      extensionReceiver = null,
      rhs, // argument
    )
  }

  protected fun irIntGreater(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
    val int = context.irBuiltIns.intType
    val greater = context.irBuiltIns.greaterFunByOperandType[int.classifierOrFail]
    return irCall(
      symbol = greater!!,
      origin = IrStatementOrigin.GT,
      dispatchReceiver = null,
      extensionReceiver = null,
      lhs, // argument
      rhs, // argument
    )
  }

  protected fun irReturn(
    target: IrReturnTargetSymbol,
    value: IrExpression,
    type: IrType = value.type,
  ): IrExpression =
    IrReturnImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = type,
      returnTargetSymbol = target,
      value = value,
    )

  protected fun irReturnVar(
    target: IrReturnTargetSymbol,
    value: IrVariable,
  ): IrExpression =
    IrReturnImpl(
      startOffset = value.initializer?.startOffset ?: UNDEFINED_OFFSET,
      endOffset = value.initializer?.endOffset ?: UNDEFINED_OFFSET,
      type = value.type,
      returnTargetSymbol = target,
      value = irGet(value),
    )

  /** Compare [lhs] and [rhs] using structural equality (`==`). */
  protected fun irEqual(lhs: IrExpression, rhs: IrExpression): IrExpression =
    irCall(
      symbol = context.irBuiltIns.eqeqSymbol,
      origin = null,
      dispatchReceiver = null,
      extensionReceiver = null,
      lhs, // argument
      rhs, // argument
    )

  protected fun irNot(value: IrExpression): IrExpression =
    irCall(
      symbol = context.irBuiltIns.booleanNotSymbol,
      dispatchReceiver = value,
    )

  /** Compare [lhs] and [rhs] using structural inequality (`!=`). */
  protected fun irNotEqual(lhs: IrExpression, rhs: IrExpression): IrExpression =
    irNot(irEqual(lhs, rhs))

  protected fun irIntConst(value: Int): IrConst =
    IrConstImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.intType,
      kind = IrConstKind.Int,
      value = value,
    )

  protected fun irStringConst(value: String): IrConst =
    IrConstImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.stringType,
      kind = IrConstKind.String,
      value = value,
    )

  protected fun irBooleanConst(value: Boolean): IrConst =
    IrConstImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.booleanType,
      kind = IrConstKind.Boolean,
      value = value,
    )

  protected fun irAnyNull(): IrConst =
    IrConstImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.anyNType,
      kind = IrConstKind.Null,
      value = null,
    )

  protected fun irWhileLoop(
    elementType: IrType,
    subject: IrExpression,
    loopBody: (IrValueDeclaration) -> IrExpression,
  ): IrBlock {
    val getIteratorFunction =
      subject.type.classOrNull!!.owner.functions.single { it.name.asString() == "iterator" }

    val iteratorSymbol = getIteratorFunction.returnType.classOrNull!!
    val iteratorType =
      if (iteratorSymbol.owner.typeParameters.isNotEmpty()) {
        iteratorSymbol.typeWith(elementType)
      } else {
        iteratorSymbol.defaultType
      }

    val nextSymbol = iteratorSymbol.owner.functions.single { it.name.asString() == "next" }
    val hasNextSymbol = iteratorSymbol.owner.functions.single { it.name.asString() == "hasNext" }

    val iteratorCall = IrCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = iteratorType,
      symbol = getIteratorFunction.symbol,
      typeArgumentsCount = getIteratorFunction.symbol.owner.typeParameters.size,
      origin = IrStatementOrigin.FOR_LOOP_ITERATOR,
    ).also {
      it.dispatchReceiver = subject
    }
    val iteratorVar = irTemporaryVariable(
      value = iteratorCall,
      isVar = false,
      name = "tmp0_iterator",
      irType = iteratorType,
      origin = IrDeclarationOrigin.FOR_LOOP_ITERATOR,
    )

    return irBlock(
      type = builtIns.unitType,
      origin = IrStatementOrigin.FOR_LOOP,
      statements = listOf(
        iteratorVar,
        IrWhileLoopImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = builtIns.unitType,
          origin = IrStatementOrigin.FOR_LOOP_INNER_WHILE,
        ).apply {
          val iteratorNextVar = irTemporaryVariable(
            value = IrCallImpl(
              symbol = nextSymbol.symbol,
              origin = IrStatementOrigin.FOR_LOOP_NEXT,
              startOffset = UNDEFINED_OFFSET,
              endOffset = UNDEFINED_OFFSET,
              typeArgumentsCount = nextSymbol.symbol.owner.typeParameters.size,
              type = elementType,
            ).also {
              it.dispatchReceiver = irGet(iteratorVar)
            },
            origin = IrDeclarationOrigin.FOR_LOOP_VARIABLE,
            isVar = false,
            name = "value",
            irType = elementType,
          )
          condition = irCall(
            symbol = hasNextSymbol.symbol,
            origin = IrStatementOrigin.FOR_LOOP_HAS_NEXT,
            dispatchReceiver = irGet(iteratorVar),
          )
          body = irBlock(
            type = builtIns.unitType,
            origin = IrStatementOrigin.FOR_LOOP_INNER_WHILE,
            statements = listOf(iteratorNextVar, loopBody(iteratorNextVar)),
          )
        }
      )
    )
  }

  protected fun irTemporaryVariable(
    value: IrExpression,
    name: String,
    irType: IrType = value.type,
    isVar: Boolean = false,
    origin: IrDeclarationOrigin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
  ): IrVariableImpl =
    IrVariableImpl(
      startOffset = value.startOffset,
      endOffset = value.endOffset,
      origin = origin,
      symbol = IrVariableSymbolImpl(),
      name = Name.identifier(name),
      type = irType,
      isVar = isVar,
      isConst = false,
      isLateinit = false,
    ).apply {
      initializer = value
    }

  protected fun irGet(type: IrType, symbol: IrValueSymbol): IrExpression =
    IrGetValueImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = type,
      symbol = symbol,
    )

  protected fun irGet(variable: IrValueDeclaration): IrExpression =
    irGet(type = variable.type, symbol = variable.symbol)

  protected fun irGetField(field: IrField): IrGetField =
    IrGetFieldImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      symbol = field.symbol,
      type = field.type,
    )

  protected fun irIf(condition: IrExpression, body: IrExpression): IrExpression =
    IrWhenImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = context.irBuiltIns.unitType,
      origin = IrStatementOrigin.IF,
    ).also {
      it.branches.add(IrBranchImpl(condition = condition, result = body))
    }

  protected fun irIfThenElse(
    type: IrType = context.irBuiltIns.unitType,
    condition: IrExpression,
    thenPart: IrExpression,
    elsePart: IrExpression,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrWhen =
    IrWhenImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = type,
      origin = IrStatementOrigin.IF,
    ).apply {
      branches.add(
        IrBranchImpl(
          startOffset = startOffset,
          endOffset = endOffset,
          condition = condition,
          result = thenPart,
        ),
      )
      branches.add(
        irElseBranch(
          expression = elsePart,
          startOffset = startOffset,
          endOffset = endOffset,
        ),
      )
    }

  protected fun irWhen(
    type: IrType = context.irBuiltIns.unitType,
    origin: IrStatementOrigin? = null,
    branches: List<IrBranch>,
  ): IrWhen =
    IrWhenImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = type,
      origin = origin,
      branches = branches,
    )

  protected fun irBranch(
    condition: IrExpression,
    result: IrExpression,
  ): IrBranch =
    IrBranchImpl(condition = condition, result = result)

  protected fun irElseBranch(
    expression: IrExpression,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrElseBranch =
    IrElseBranchImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      condition = irBooleanConst(true),
      result = expression,
    )

  protected fun irBlock(
    type: IrType = context.irBuiltIns.unitType,
    origin: IrStatementOrigin? = null,
    statements: List<IrStatement>,
  ): IrBlock =
    IrBlockImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = type,
      origin = origin,
      statements = statements,
    )

  protected fun irComposite(
    type: IrType = context.irBuiltIns.unitType,
    origin: IrStatementOrigin? = null,
    statements: List<IrStatement>,
  ): IrComposite =
    IrCompositeImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = type,
      origin = origin,
      statements = statements,
    )

  protected fun irLambdaExpression(
    startOffset: Int,
    endOffset: Int,
    returnType: IrType,
    body: (IrSimpleFunction) -> Unit,
  ): IrExpression {
    val function = context.irFactory.buildFun {
      this.startOffset = SYNTHETIC_OFFSET
      this.endOffset = SYNTHETIC_OFFSET
      this.returnType = returnType
      origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
      name = SpecialNames.ANONYMOUS
      visibility = DescriptorVisibilities.LOCAL
    }.also(body)

    return IrFunctionExpressionImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = context.function(function.valueParameters.size).typeWith(
        function.valueParameters.map { it.type } + listOf(function.returnType),
      ),
      origin = IrStatementOrigin.LAMBDA,
      function = function,
    )
  }

  private fun IrClass.getRuntimeStabilityValue(): IrExpression? {
    if (context.platform.isJvm()) {
      // STUDY 필드가 중복으로 생성되어도 괜찮나?
      val stableField = this.makeStabilityField()
      return irGetField(stableField)
    }
    return null
  }

  // STUDY 정확한 역할이 뭘까? 일단 [unstable: 1 000] 비트의 조합만 남기는 걸로 보인다.
  internal fun IrClass.makeStabilityField(): IrField =
    context.irFactory.buildField {
      startOffset = SYNTHETIC_OFFSET
      endOffset = SYNTHETIC_OFFSET
      name = ComposeNames.STABILITY_FLAG
      isStatic = true
      isFinal = true
      type = context.irBuiltIns.intType
      visibility = DescriptorVisibilities.PUBLIC
    }
      .also { field ->
        field.parent = this
        this.declarations += field
      }

  // STUDY Static vs Stable
  fun IrExpression.isStaticExpression(): Boolean =
    when (this) {
      // A constant by definition is static
      is IrConst -> true

      // We want to consider all enum values as static
      is IrGetEnumValue -> true

      // Getting a companion object or top level object can be considered static if the
      // type of that object is Stable. (`Modifier` for instance is a common example)
      is IrGetObjectValue -> {
        if (symbol.owner.isCompanion)
          true
        else
          stabilityInferencer.stabilityOfType(irType = type).knownStable()
      }

      is IrConstructorCall -> isStaticConstructor()

      is IrCall -> isStaticCall()

      is IrGetValue -> {
        when (val owner = symbol.owner) {
          is IrVariable -> {
            // If we have an immutable variable whose initializer is also static,
            // then we can determine that the variable reference is also static.
            !owner.isVar && owner.initializer?.isStaticExpression() == true
          }

          else -> false
        }
      }

      is IrFunctionExpression,
      is IrTypeOperatorCall,
        ->
        context.irTrace[ComposeWritableSlices.IS_STATIC_FUNCTION_EXPRESSION, this] ?: false

      is IrGetField ->
        // K2 sometimes produces `IrGetField` for reads from constant properties
        symbol.owner.correspondingPropertySymbol?.owner?.isConst == true

      is IrBlock -> {
        // Check the slice in case the block was generated as expression
        // (e.g. inlined intrinsic remember call)
        context.irTrace[ComposeWritableSlices.IS_STATIC_EXPRESSION, this] ?: false
      }

      else -> false
    }

  private fun IrConstructorCall.isStaticConstructor(): Boolean {
    // special case constructors of inline classes as static if their underlying
    // value is static.
    if (type.isInlineClassType()) {
      return stabilityInferencer.stabilityOfType(type.unboxTypeIfInlineOrDefault()).knownStable() &&
        getValueArgument(0)?.isStaticExpression() == true
    }

    // If a type is @Immutable, then calls to its constructor are static if all of
    // the provided arguments are static.
    if (symbol.owner.parentAsClass.hasAnnotationSafe(ComposeFqNames.Immutable)) {
      return areAllArgumentsStatic()
    }

    return false
  }

  private fun IrStatementOrigin?.isGetProperty() = this == IrStatementOrigin.GET_PROPERTY

  private fun IrStatementOrigin?.isSpecialCaseMath() =
    this in setOf(
      IrStatementOrigin.PLUS,
      IrStatementOrigin.MUL,
      IrStatementOrigin.MINUS,
      IrStatementOrigin.ANDAND,
      IrStatementOrigin.OROR,
      IrStatementOrigin.DIV,
      IrStatementOrigin.EQ,
      IrStatementOrigin.EQEQ,
      IrStatementOrigin.EQEQEQ,
      IrStatementOrigin.GT,
      IrStatementOrigin.GTEQ,
      IrStatementOrigin.LT,
      IrStatementOrigin.LTEQ
    )

  private fun IrCall.isStaticCall(): Boolean {
    val function = symbol.owner
    val fqName = function.kotlinFqName
    return when {
      origin.isGetProperty() -> {
        // If we are in a GET_PROPERTY call, then this should usually resolve to
        // non-null, but in case it doesn't, just return false
        val prop = function.correspondingPropertySymbol?.owner ?: return false

        // if the property is a top level constant, then it is static.
        if (prop.isConst) return true

        val typeIsStable = stabilityInferencer.stabilityOfType(type).knownStable()
        val dispatchReceiverIsStatic = dispatchReceiver?.isStaticExpression() != false
        val extensionReceiverIsStatic = extensionReceiver?.isStaticExpression() != false

        // if we see that the property is read-only with a default getter and a
        // stable return type, then reading the property can also be considered
        // static if this is a top level property or the subject is also static.
        //
        // 프로퍼티가 기본 게터와 안정적인 반환 유형으로 읽기 전용인 경우, 프로퍼티가
        // 최상위 프로퍼티이거나 subject도 정적인 경우 프로퍼티를 읽는 것도 정적인
        // 것으로 간주할 수 있습니다.
        if (
          !prop.isVar &&
          prop.getter?.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
          typeIsStable &&
          dispatchReceiverIsStatic && extensionReceiverIsStatic
        ) {
          return true
        }

        val getterIsStable =
          prop.hasAnnotation(ComposeFqNames.Stable) || function.hasAnnotation(ComposeFqNames.Stable)

        if (
          getterIsStable &&
          typeIsStable &&
          dispatchReceiverIsStatic && extensionReceiverIsStatic
        ) {
          return true
        }

        false
      }

      origin.isSpecialCaseMath() -> {
        // special case mathematical operators that are in the stdlib. These are
        // immutable operations so the overall result is static if the operands are
        // also static.
        //
        // operand: 피연산자
        //
        // stdlib에 있는 특수한 수학 연산자입니다. 이러한 연산자는 변경 불가능한
        // 연산이므로 피연산자가 정적이면 전체 결과도 정적입니다.
        val isStableOperator =
          fqName.topLevelName() == "kotlin" || function.hasAnnotation(ComposeFqNames.Stable)

        val typeIsStable = stabilityInferencer.stabilityOfType(type).knownStable()
        if (!typeIsStable) return false

        if (!isStableOperator) {
          return false
        }

        getArgumentsWithIr().all { it.second.isStaticExpression() }
      }

      origin == null -> {
        if (fqName == ComposeFqNames.remember) {
          // if it is a call to remember with 0 input arguments, then we can consider
          // the value static if the result type of the `calculate` lambda is stable
          val syntheticRememberParams =
            1 + // composer param
              1 // changed param
          val expectedArgumentsCount = 1 + syntheticRememberParams // 1 for `calculate` lambda
          if (
            valueArgumentsCount == expectedArgumentsCount &&
            stabilityInferencer.stabilityOfType(type).knownStable()
          ) {
            return true
          }
        } else if (
          fqName == ComposeFqNames.composableLambda ||
          fqName == ComposeFqNames.rememberComposableLambda
        ) {
          // calls to this function are generated by the compiler, and this
          // function behaves similar to a remember call in that the result will
          // _always_ be the same and the resulting type is _always_ stable, so
          // thus it is static.
          //
          // thus: 이렇게 하여, 이와 같이, 따라서, 그러므로
          return true
        }

        if (context.irTrace[ComposeWritableSlices.IS_COMPOSABLE_SINGLETON, this] == true) {
          return true
        }

        // normal function call. If the function is marked as Stable and the result
        // is Stable, then the static-ness of it is the static-ness of its arguments.
        // For functions that we have an exception for, skip these checks. We've already
        // assumed the stability here and can go straight to checking their arguments.
        //
        // -ness: 어떠한 '성질', '상태', 성격'을 나타냄
        //
        // 일반 함수 호출입니다. 함수가 Stable로 표시되고 결과가 Stable인 경우 함수의 정적
        // 상태는 해당 인수의 정적 상태입니다. 이 규칙이 아닌 함수는 이러한 검사를 건너뜁니다.
        // 여기서는 이미 안정성을 가정했으므로 인수를 바로 확인할 수 있습니다.
        if (fqName.asString() !in KnownStableConstructs.stableFunctions) {
          val isStable = symbol.owner.hasAnnotation(ComposeFqNames.Stable)
          if (!isStable) return false

          val typeIsStable = stabilityInferencer.stabilityOfType(type).knownStable()
          if (!typeIsStable) return false
        }

        areAllArgumentsStatic()
      }

      else -> false
    }
  }

  private fun IrMemberAccessExpression<*>.areAllArgumentsStatic(): Boolean =
    // getArguments includes the receivers!
    getArgumentsWithIr().all { (_ /* valueParameter */, argExpression) ->
      when (argExpression) {
        // vacuum: 진공, 공백 -> 인데.. [어떠한 외부 문맥이나 제약 없이, 오직 그 자체만 놓고 보면]으로 의역됨
        // -ness: 어떠한 '성질', '상태', 성격'을 나타냄
        // capable: ~할 수 있는, ~할 능력이 있는
        //
        // In a vacuum, we can't assume varargs are static because they're backed by
        // arrays. Arrays aren't stable types due to their implicit mutability and
        // lack of built-in equality checks. But in this context, because the static-ness of
        // an argument is meaningless unless the function call that owns the argument is
        // stable and capable of being static. So in this case, we're able to ignore the
        // array implementation detail and check whether all of the parameters sent in the
        // varargs are static on their own.
        //
        // 오직 그 자체만 고려하면, vararg가 배열로 구현되어 있기 때문에 정적이라고 가정할 수
        // 없습니다. 배열은 암시적 가변성과 내장된 동등성 검사가 없기 때문에 안정적인 타입이
        // 아닙니다. 하지만 이 맥락에서는 인수를 소유한 함수 호출이 안정적이고 정적이 아니라면
        // 인수의 정적성은 무의미합니다. 따라서 이 경우 배열 구현 세부 사항을 무시하고 변수로
        // 전송된 모든 매개변수가 자체적으로 정적인지 확인할 수 있습니다.
        is IrVararg -> argExpression.elements.all { varargElement ->
          (varargElement as? IrExpression)?.isStaticExpression() ?: false
        }

        else -> argExpression.isStaticExpression()
      }
    }

  protected fun dexSafeName(name: Name): Name =
    if (name.isSpecial || name.asString().contains(unsafeSymbolsRegex)) {
      val sanitized = name.asString().replace(unsafeSymbolsRegex, "\\$")
      Name.identifier(sanitized)
    } else {
      name
    }

  fun unsafeCoerceIntrinsicCall(argument: IrExpression, from: IrType, to: IrType): IrCallImpl =
    IrCallImpl.fromSymbolOwner(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = to,
      symbol = unsafeCoerceIntrinsic!!,
    ).apply {
      typeArguments[0] = from
      typeArguments[1] = to
      putValueArgument(0, argument)
    }

  // Construct a reference to the JVM specific <unsafe-coerce> intrinsic.
  // This code should be kept in sync with the declaration in JvmSymbols.kt.
  //
  // JVM에만 있는 <unsafe-coerce> intrinsic의 참조를 구성합니다.
  // 이 코드는 JvmSymbols.kt의 선언과 항상 동기화되어야 합니다.
  //
  // 바이트코드 변환 구현체:  org.jetbrains.kotlin.backend.jvm.intrinsics.UnsafeCoerce
  //
  // 동일한 타입인데 IrType만 다른 경우, 바이트코드에서는 dst 타입을 사용하도록 강제 지정됨
  @OptIn(ObsoleteDescriptorBasedAPI::class)
  private val unsafeCoerceIntrinsic: IrSimpleFunctionSymbol? by lazy {
    if (context.platform.isJvm()) {
      context.irFactory.buildFun {
        name = Name.special("<unsafe-coerce>")
        origin = IrDeclarationOrigin.IR_BUILTINS_STUB
      }.apply {
        @Suppress("DEPRECATION")
        parent = IrExternalPackageFragmentImpl.createEmptyExternalPackageFragment(
          context.moduleDescriptor,
          FqName("kotlin.jvm.internal")
        )
        val src = addTypeParameter("T", context.irBuiltIns.anyNType)
        val dst = addTypeParameter("R", context.irBuiltIns.anyNType)
        addValueParameter("v", src.defaultType)
        returnType = dst.defaultType
      }.symbol
    } else {
      null
    }
  }

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  fun IrSimpleFunction.sourceKey(): Int {
    val info = context.irTrace[ComposeWritableSlices.DURABLE_FUNCTION_KEY, this]
    if (info != null) {
      info.used = true
      return info.key
    }
    val signature = symbol.descriptor.computeJvmDescriptor(withName = false)
    val name = fqNameForIrSerialization
    return "$name$signature".hashCode()
  }

  /*
   * Delegated accessors are generated with IrReturn(IrCall(<delegated function>)) structure.
   * To verify the delegated function is Composable, this function is unpacking it and
   * checks annotation on the symbol owner of the call.
   */
  fun IrFunction.isComposableDelegatedAccessor(): Boolean =
    origin == IrDeclarationOrigin.DELEGATED_PROPERTY_ACCESSOR &&
      body?.let {
        val returnStatement = it.statements.singleOrNull() as? IrReturn
        val callStatement = returnStatement?.value as? IrCall
        val target = callStatement?.symbol?.owner
        target?.hasComposableAnnotation()
      } == true

  private val cacheFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.cache).first {
      it.owner.valueParameters.size == 2 && it.owner.extensionReceiverParameter != null
    }.owner
  }

  fun irCache(
    currentComposer: IrExpression,
    startOffset: Int,
    endOffset: Int,
    returnType: IrType,
    invalid: IrExpression,
    calculation: IrExpression,
  ): IrCall {
    val symbol = cacheFunction.symbol
    return IrCallImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = returnType,
      symbol = symbol,
      typeArgumentsCount = symbol.owner.typeParameters.size,
    ).apply {
      extensionReceiver = currentComposer
      typeArguments[0] = returnType
      putValueArgument(0, invalid)
      putValueArgument(1, calculation)
    }
  }

  fun irChanged(
    currentComposer: IrExpression,
    value: IrExpression,
    inferredStable: Boolean,
    compareInstanceForFunctionTypes: Boolean,
    compareInstanceForUnstableValues: Boolean,
  ): IrExpression {
    // Compose has a unique opportunity to avoid inline class boxing for changed calls, since
    // we know that the only thing that we are detecting here is "changed or not", we can
    // just as easily pass in the underlying value, which will avoid boxing to check for
    // equality on recompositions. As a result here we want to pass in the underlying
    // property value for inline classes, not the instance itself. The inline class lowering
    // will turn this into just passing the wrapped value later on. If the type is already
    // boxed, then we don't want to unnecessarily _unbox_ it. Note that if Kotlin allows for
    // an overridden equals method of inline classes in the future, we may have to avoid the
    // boxing in a different way.
    //
    // Compose는 changed 호출에서 인라인 클래스의 박싱을 피할 수 있는 특별한 기회를 갖습니다.
    // 여기서 우리가 감지하려 하는 건 “변경되었는지 여부”뿐이므로, 인라인 클래스 인스턴스
    // 전체를 전달하는 대신 그 내부에 감싸인(underlying) 값을 그대로 넘겨 주면 재구성 시
    // 동등성(equality) 비교를 위해 박싱할 필요가 없어집니다. 따라서 이 지점에서는 인라인
    // 클래스의 인스턴스가 아니라 감싸인(underlying) 값을 전달하도록 하고, 이후 인라인 클래스
    // lowering 과정에서 자동으로 래핑된 값만 전달되도록 바뀝니다. 만약 이미 타입이 박싱되어
    // 있다면 불필요한 언박싱을 수행하지 않아야 합니다. 참고로, 앞으로 코틀린이 인라인 클래스에
    // 대해 equals 메서드 오버라이드를 허용하게 되면, 박싱 회피 방식을 다른 방식으로 변경해야
    // 할 수도 있습니다.
    val expr = value.coerceUnboxedTypeCallIfInlineOrDefault().ordinalIfEnum()
    val type = expr.type
    val stability = stabilityInferencer.stabilityOfExpression(value)

    val primitiveChangedFun = type.toPrimitiveType().let { changedPrimitiveFunctions[it] }

    return when {
      !compareInstanceForUnstableValues -> {
        val changedFun =
          primitiveChangedFun ?: when {
            type.isFunction() && compareInstanceForFunctionTypes -> changedInstanceFunction
            else -> changedFunction
          }
        irMethodCall(
          target = currentComposer,
          function = changedFun,
        )
          .also { it.putValueArgument(0, expr) }
      }
      else -> {
        val changedFun = when {
          primitiveChangedFun != null -> primitiveChangedFun
          compareInstanceForFunctionTypes && type.isFunction() -> changedInstanceFunction
          inferredStable -> changedFunction
          stability.knownStable() -> changedFunction
          stability.knownUnstable() -> changedInstanceFunction // strong skipping mode
          stability.isUncertain() -> changedInstanceFunction // strong skipping mode
          else -> error("Cannot determine descriptor for irChanged")
        }
        irMethodCall(
          target = currentComposer,
          function = changedFun
        )
          .also { it.putValueArgument(0, expr) }
      }
    }
  }

  private val irEnumOrdinal =
    context.irBuiltIns.enumClass.owner.properties.single { it.name.asString() == "ordinal" }.getter!!

  private val protobufEnumClassId = ClassId.fromString("com/google/protobuf/Internal/EnumLite")

  private fun IrExpression.ordinalIfEnum(): IrExpression {
    val cls = type.classOrNull?.owner
    return when (cls?.kind) {
      ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> {
        val ordinalFunction = when {
          cls.isSubclassOf(protobufEnumClassId) -> {
            // For protobuf enums, we need to use the `getNumber` method instead of `ordinal`
            cls.functions.single {
              it.name.asString() == "getNumber" &&
                it.parameters.size == 1 &&
                it.parameters[0].kind == IrParameterKind.DispatchReceiver
            }
          }
          else -> irEnumOrdinal
        }
        if (type.isNullable()) {
          val enumValue = irTemporaryVariable(value = this, name = "tmpEnum")
          irBlock(
            type = context.irBuiltIns.intType,
            statements = listOf(
              enumValue,
              irIfThenElse(
                type = context.irBuiltIns.intType,
                condition = irEqual(irGet(enumValue), irAnyNull()),
                thenPart = irIntConst(-1),
                elsePart = irCall(symbol = ordinalFunction.symbol, dispatchReceiver = irGet(enumValue))
              )
            ),
          )
        } else {
          irCall(symbol = ordinalFunction.symbol, dispatchReceiver = this)
        }
      }
      else -> this
    }
  }

  fun irStartReplaceGroup(
    currentComposer: IrExpression,
    key: IrExpression,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrExpression =
    irMethodCall(
      target = currentComposer,
      function = startReplaceFunction,
      startOffset = startOffset,
      endOffset = endOffset,
    ).also {
      it.putValueArgument(0, key)
    }

  fun irEndReplaceGroup(
    currentComposer: IrExpression,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrExpression =
    irMethodCall(
      target = currentComposer,
      function = endReplaceFunction,
      startOffset = startOffset,
      endOffset = endOffset
    )

  fun IrStatement.wrap(
    startOffset: Int = this.startOffset,
    endOffset: Int = this.endOffset,
    type: IrType,
    before: List<IrStatement> = emptyList(),
    after: List<IrStatement> = emptyList(),
  ): IrContainerExpression =
    IrBlockImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = type,
      origin = null,
      statements = before + this + after,
    )

  fun irMethodCall(
    target: IrExpression,
    function: IrFunction,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrCall =
    irCall(
      function = function,
      startOffset = startOffset,
      endOffset = endOffset,
    ).apply {
      dispatchReceiver = target
    }

  fun irCall(
    function: IrFunction,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrCall =
    IrCallImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = function.returnType,
      symbol = function.symbol as IrSimpleFunctionSymbol,
      typeArgumentsCount = function.symbol.owner.typeParameters.size,
    )

  private fun IrType.toPrimitiveType(): PrimitiveType? =
    when {
      isInt() -> PrimitiveType.INT
      isBoolean() -> PrimitiveType.BOOLEAN
      isFloat() -> PrimitiveType.FLOAT
      isLong() -> PrimitiveType.LONG
      isDouble() -> PrimitiveType.DOUBLE
      isByte() -> PrimitiveType.BYTE
      isChar() -> PrimitiveType.CHAR
      isShort() -> PrimitiveType.SHORT
      else -> null
    }

  internal fun IrFunction.copyParametersFrom(original: IrFunction) {
    val newFunction = this

    // here generic value parameters will be applied
    newFunction.copyTypeParametersFrom(original)

    // ..but we need to remap the return type as well
    newFunction.returnType = newFunction.returnType.remapTypeParameters(source = original, target = newFunction)

    newFunction.valueParameters = original.valueParameters.map {
      val name = dexSafeName(it.name)
      it.copyTo(
        irFunction = newFunction,
        name = name,
        type = it.type.remapTypeParameters(original, newFunction),
        // remapping the type parameters explicitly
        defaultValue = it.defaultValue?.copyWithNewTypeParams(original, newFunction),
      )
    }

    newFunction.dispatchReceiverParameter = original.dispatchReceiverParameter?.copyTo(newFunction)
    newFunction.extensionReceiverParameter = original.extensionReceiverParameter?.copyWithNewTypeParams(original, newFunction)

    newFunction.valueParameters.forEach {
      it.defaultValue?.transformDefaultValue(originalFunction = original, newFunction = newFunction)
    }
  }

  /**
   *  Expressions for default values can use other parameters.
   *  In such cases we need to ensure that default values expressions use parameters of the new
   *  function (new/copied value parameters).
   *
   *  Example:
   *  fun Foo(a: String, b: String = a) {...}
   */
  private fun IrExpressionBody.transformDefaultValue(
    originalFunction: IrFunction,
    newFunction: IrFunction,
  ) {
    transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        val original = super.visitGetValue(expression)
        val valueParameter = (expression.symbol.owner as? IrValueParameter) ?: return original
        val parameterIndex = valueParameter.indexInOldValueParameters

        if (valueParameter.parent != originalFunction) {
          return super.visitGetValue(expression)
        }

        if (parameterIndex < 0) {
          when (valueParameter) {
            originalFunction.dispatchReceiverParameter -> {
              return IrGetValueImpl(
                startOffset = expression.startOffset,
                endOffset = expression.endOffset,
                symbol = newFunction.dispatchReceiverParameter!!.symbol,
                origin = expression.origin
              )
            }
            originalFunction.extensionReceiverParameter -> {
              return IrGetValueImpl(
                expression.startOffset,
                expression.endOffset,
                newFunction.extensionReceiverParameter!!.symbol,
                expression.origin
              )
            }
            else -> {
              error("Cannot copy parameter: ${valueParameter.dump()}")
            }
          }
        }

        return IrGetValueImpl(
          startOffset = expression.startOffset,
          endOffset = expression.endOffset,
          symbol = newFunction.valueParameters[parameterIndex].symbol,
          origin = expression.origin,
        )
      }
    })
  }

  protected var IrDeclaration.composeMetadata: ComposeMetadata?
    get() =
      context.metadataDeclarationRegistrar
        .getCustomMetadataExtension(irDeclaration = this, pluginId = COMPOSE_PLUGIN_ID)
        ?.let(::ComposeMetadata)
    set(value) {
      if (value != null && this.hasFirDeclaration()) {
        context.metadataDeclarationRegistrar
          .addCustomMetadataExtension(irDeclaration = this, pluginId = COMPOSE_PLUGIN_ID, data = value.data)
      }
    }

  protected val IrFunction.hasNonRestartableAnnotation: Boolean
    get() = hasAnnotation(ComposeFqNames.NonRestartableComposable)

  protected val IrFunction.hasReadOnlyAnnotation: Boolean
    get() = hasAnnotation(ComposeFqNames.ReadOnlyComposable)

  protected val IrFunction.hasExplicitGroups: Boolean
    get() = hasAnnotation(ComposeFqNames.ExplicitGroupsComposable)

  protected val IrFunction.hasNonSkippableAnnotation: Boolean
    get() = hasAnnotation(ComposeFqNames.NonSkippableComposable)

  private val jvmSyntheticIrClass =
    if (context.platform.isJvm()) {
      getTopLevelClass(ClassId(StandardClassIds.BASE_JVM_PACKAGE, Name.identifier("JvmSynthetic"))).owner
    } else {
      null
    }

  private val hiddenFromObjCIrClass: IrClass? =
    if (context.platform.isNative()) {
      getTopLevelClass(hiddenFromObjCClassId).owner
    } else {
      null
    }

  private val deprecationLevelIrClass = getTopLevelClass(ClassId.fromString("kotlin/DeprecationLevel")).owner
  private val deprecatedIrClass = getTopLevelClass(ClassId.fromString("kotlin/Deprecated"))
  private val hiddenDeprecationLevel =
    deprecationLevelIrClass.declarations
      .filterIsInstance<IrEnumEntry>()
      .single { it.name.toString() == "HIDDEN" }
      .symbol

  private fun jvmSynthetic() =
    jvmSyntheticIrClass?.let {
      IrConstructorCallImpl.fromSymbolOwner(
        type = it.defaultType,
        constructorSymbol = it.constructors.first().symbol,
      )
    }

  private fun hiddenFromObjC() =
    hiddenFromObjCIrClass?.let {
      IrConstructorCallImpl.fromSymbolOwner(
        type = it.defaultType,
        constructorSymbol = it.constructors.first().symbol,
      )
    }

  private fun hiddenDeprecated(@Suppress("SameParameterValue") message: String): IrConstructorCallImpl =
    IrConstructorCallImpl.fromSymbolOwner(
      type = deprecatedIrClass.defaultType,
      constructorSymbol = deprecatedIrClass.constructors.first { it.owner.isPrimary },
    ).also {
      it.arguments[0] = IrConstImpl.string(
        startOffset = SYNTHETIC_OFFSET,
        endOffset = SYNTHETIC_OFFSET,
        type = context.irBuiltIns.stringType,
        value = message,
      )
      it.arguments[2] = IrGetEnumValueImpl(
        startOffset = SYNTHETIC_OFFSET,
        endOffset = SYNTHETIC_OFFSET,
        type = deprecationLevelIrClass.defaultType,
        symbol = hiddenDeprecationLevel,
      )
    }

  protected fun IrSimpleFunction.makeStub(): IrSimpleFunction {
    val source = this
    val copy = source.deepCopyWithSymbols(initialParent = parent)
    copy.attributeOwnerId = copy
    copy.isDefaultParamStub = true
    copy.annotations += listOfNotNull(
      jvmSynthetic(),
      hiddenFromObjC(),
      hiddenDeprecated("Binary compatibility stub for default parameters"),
    )
    copy.body = null
    return copy
  }
}

private val unsafeSymbolsRegex = "[ <>]".toRegex()

fun IrFunction.composerParam(): IrValueParameter? {
  for (param in valueParameters.asReversed()) {
    if (param.isComposerParam()) return param
    if (!param.name.asString().startsWith('$')) return null
  }
  return null
}

fun IrValueParameter.isComposerParam(): Boolean =
  name == ComposeNames.COMPOSER_PARAMETER && type.classFqName == ComposeFqNames.Composer

// FIXME: There is a `functionN` factory in `IrBuiltIns`, but it currently produces unbound symbols.
//        We can switch to this and remove this function once KT-54230 is fixed.
fun IrPluginContext.function(arity: Int): IrClassSymbol =
  referenceClass(ClassId(FqName("kotlin"), Name.identifier("Function$arity")))!!

@OptIn(ObsoleteDescriptorBasedAPI::class)
fun IrAnnotationContainer.hasAnnotationSafe(fqName: FqName): Boolean =
  annotations.any {
    // compiler helper getAnnotation fails during remapping in [ComposableTypeRemapper], so we
    // use this impl
    fqName == it.annotationClass?.descriptor?.fqNameSafe
  }

// workaround for KT-45361
val IrConstructorCall.annotationClass
  get() = type.classOrNull

fun IrDeclaration.hasFirDeclaration(): Boolean = ((this as? IrMetadataSourceOwner)?.metadata as? FirMetadataSource)?.fir != null

inline fun <T> includeFileNameInExceptionTrace(file: IrFile, body: () -> T): T {
  try {
    return body()
  } catch (e: Exception) {
    rethrowIntellijPlatformExceptionIfNeeded(e)
    throw Exception("IR lowering failed at: ${file.name}", e)
  }
}

fun FqName.topLevelName() = asString().substringBefore(".")

private fun IrClass.isSubclassOf(classId: ClassId) =
  superTypes.any { it.classOrNull?.owner?.classId == classId }

internal inline fun <reified T : IrElement> T.copyWithNewTypeParams(
  source: IrFunction,
  target: IrFunction,
): T {
  val typeParamsAwareSymbolRemapper = object : DeepCopySymbolRemapper() {
    init {
      for ((original, new) in source.typeParameters.zip(target.typeParameters)) {
        typeParameters[original.symbol] = new.symbol
      }
    }
  }
  val typeRemapper = DeepCopyTypeRemapper(typeParamsAwareSymbolRemapper)
  val typeParamRemapper = object : TypeRemapper by typeRemapper {
    override fun remapType(type: IrType): IrType {
      return typeRemapper.remapType(type.remapTypeParameters(source, target))
    }
  }

  val deepCopy =
    DeepCopyPreservingMetadata(symbolRemapper = typeParamsAwareSymbolRemapper, typeRemapper = typeParamRemapper)
  typeRemapper.deepCopy = deepCopy

  acceptVoid(typeParamsAwareSymbolRemapper)
  return transform(deepCopy, null).patchDeclarationParents(target) as T
}
