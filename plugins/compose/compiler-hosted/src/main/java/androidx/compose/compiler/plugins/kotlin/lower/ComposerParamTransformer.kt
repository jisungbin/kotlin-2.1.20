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

import androidx.compose.compiler.plugins.kotlin.ComposeNames
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import kotlin.math.min
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.defaultValueForType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.remapTypeParameters
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.JvmStandardClassIds
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.util.OperatorNameConventions

class ComposerParamTransformer(
  context: IrPluginContext,
  stabilityInferencer: StabilityInferencer,
  metrics: ModuleMetrics,
  featureFlags: FeatureFlags,
) : AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags),
  ModuleLoweringPass {
  // Used to identify module fragment in case of incremental compilation.
  // 증분 컴파일의 경우 모듈 조각을 식별하는 데 사용됩니다.
  private var currentModule: IrModuleFragment? = null
  private val inlineLambdaInfo = ComposeInlineLambdaLocator(context)

  private val composerType = composerIrClass.defaultType

  private val transformedFunctions = mutableSetOf<IrSimpleFunction>()
  private val transformedFunctionMap = mutableMapOf<IrSimpleFunction, IrSimpleFunction>()

  override fun lower(irModule: IrModuleFragment) {
    currentModule = irModule
    inlineLambdaInfo.scan(irModule)

    irModule.transformChildrenVoid(this)

    val typeRemapper = ComposableTypeRemapper(context = context, composerType = composerType)
    val transformer = ComposableTypeTransformer(context = context, typeRemapper = typeRemapper)

    // for each declaration, we remap types to ensure that @Composable lambda types are realized.
    // 각 선언에 대해 @Composable 람다 타입이 구현되도록 타입을 다시 매핑합니다.
    irModule.transformChildrenVoid(transformer)

    // just go through and patch all of the parents to make sure things are properly wired up.
    // 모든 부모를 살펴보고 패치를 적용하여 제대로 연결되었는지 확인하기만 하면 됩니다.
    irModule.patchDeclarationParents()
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement =
    super.visitSimpleFunction(declaration.copyFunctionWithComposerParamIfNeeded())

  override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference): IrExpression {
    expression.getter = expression.getter.owner.copyFunctionWithComposerParamIfNeeded().symbol
    expression.setter = expression.setter?.owner?.copyFunctionWithComposerParamIfNeeded()?.symbol
    return super.visitLocalDelegatedPropertyReference(expression)
  }

  override fun visitPropertyReference(expression: IrPropertyReference): IrExpression {
    expression.getter = expression.getter?.owner?.copyFunctionWithComposerParamIfNeeded()?.symbol
    expression.setter = expression.setter?.owner?.copyFunctionWithComposerParamIfNeeded()?.symbol
    return super.visitPropertyReference(expression)
  }

  override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
    if (declaration.getter.isComposableDelegatedAccessor()) {
      declaration.getter.annotations += createComposableAnnotation()
    }

    if (declaration.setter?.isComposableDelegatedAccessor() == true) {
      declaration.setter!!.annotations += createComposableAnnotation()
    }

    return super.visitLocalDelegatedProperty(declaration)
  }

  // Transform `@Composable fun foo(params): RetType` into `fun foo(params, $composer: Composer): RetType`
  private fun IrSimpleFunction.copyFunctionWithComposerParamIfNeeded(): IrSimpleFunction {
    // don't transform functions that themselves were produced by this function. (ie, if we
    // call this with a function that has the synthetic composer parameter, we don't want to
    // transform it further).
    //
    // 함수는 이 함수에 의해 생성된 함수를 변환하지 않습니다.
    // (즉, synthetic한 $composer 매개변수가 있는 함수로 이 함수를 호출하면 더 이상 변환하지 않습니다.)
    if (transformedFunctions.contains(this)) return this

    // if not a composable function, nothing we need to do
    if (!hasComposableAnnotation()) return this

    // we don't bother transforming expect functions. They exist only for type resolution and
    // don't need to be transformed to have a composer parameter.
    //
    // expect 함수는 변환하지 않습니다.
    // 유형 확인을 위해서만 존재하며 $composer 매개변수를 갖기 위해 변환할 필요가 없습니다.
    if (isExpect) return this

    // cache the transformed function with composer parameter
    return transformedFunctionMap[this] ?: copyFunctionWithComposerParam()
  }

  private fun IrSimpleFunction.copyFunctionWithComposerParam(): IrSimpleFunction {
    assert(parameters.lastOrNull()?.name != ComposeNames.COMPOSER_PARAMETER) {
      "Attempted to add composer param to $this, but it has already been added."
    }

    return copy().also { copied ->
      val oldFn = this

      // it's important to add these here before we recurse into the body in
      // order to avoid an infinite loop on circular/recursive calls.
      //
      // 순환/재귀 호출에서 무한 루프를 피하려면 본문에 재귀하기 전에 여기에
      // 추가하는 것이 중요합니다.
      transformedFunctions.add(copied)
      transformedFunctionMap[oldFn] = copied

      // The overridden symbols might also be composable functions, so we want to make sure
      // and transform them as well.
      //
      // 재정의된 심볼도 컴포저블 함수일 수 있으므로 이를 확인하고 변환할 수 있습니다.
      copied.overriddenSymbols = overriddenSymbols.map {
        it.owner.copyFunctionWithComposerParamIfNeeded().symbol
      }

      // if we are transforming a composable property, the jvm signature of the
      // corresponding getters and setters have a composer parameter. Since Kotlin uses the
      // lack of a parameter to determine if it is a getter, this breaks inlining for
      // composable property getters since it ends up looking for the wrong jvmSignature.
      // In this case, we manually add the appropriate "@JvmName" annotation so that the
      // inliner doesn't get confused.
      //
      // STUDY 인라이닝이랑 `@JvmName`이랑 무슨 연관일까?
      //
      // 컴포저블 프로퍼티를 변환하는 경우 해당 getter 및 setter의 jvm 시그니처에는 $composer
      // 매개변수가 있습니다. Kotlin는 매개변수가 없는 것을 확인하여 getter인지 여부를 판단하기
      // 때문에 잘못된 jvm 시그니처를 찾게 되어 컴포저블 프로퍼티 getter의 인라이닝이 중단됩니다.
      // 이 경우 인라이너가 혼동하지 않도록 적절한 “@JvmName” 어노테이션을 수동으로 추가합니다.
      copied.correspondingPropertySymbol?.let { propertySymbol ->
        if (!copied.hasAnnotation(DescriptorUtils.JVM_NAME)) {
          val propertyName = propertySymbol.owner.name.identifier
          val name = if (copied.isGetter) JvmAbi.getterName(propertyName) else JvmAbi.setterName(propertyName)
          copied.annotations += jvmNameAnnotation(name)
        }
      }

      val valueParametersMapping = oldFn.parameters.zip(copied.parameters).toMap()

      val currentParamsSize = copied.valueParameters.size
      val realParamsCount = currentParamsSize - copied.contextReceiverParametersCount

      // $composer
      val composerParam = copied.addValueParameter {
        name = ComposeNames.COMPOSER_PARAMETER
        type = composerType.makeNullable()
        origin = IrDeclarationOrigin.DEFINED
        isAssignable = true
      }

      // $changed[n]
      val changed = ComposeNames.CHANGED_PARAMETER.identifier
      repeat(
        changedParamCount(
          realValueParamsCount = realParamsCount,
          thisParamsCount = copied.thisParamCount,
        ),
      ) { i ->
        copied.addValueParameter(
          name = if (i == 0) changed else "$changed$i",
          type = context.irBuiltIns.intType,
        )
      }

      // $default[n]
      if (oldFn.requiresDefaultParameter()) {
        val defaults = ComposeNames.DEFAULT_PARAMETER.identifier
        repeat(defaultParamCount(currentParamsSize)) { i ->
          copied.addValueParameter(
            name = if (i == 0) defaults else "$defaults$i",
            type = context.irBuiltIns.intType,
            origin = IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION,
          )
        }
      }

      copied.makeStubForDefaultValueClassIfNeeded()?.also {
        when (val parent = copied.parent) {
          is IrClass -> parent.addChild(it)
          is IrFile -> parent.addChild(it)
          else -> {} // ignore
        }
      }

      // update parameter types so they are ready to accept the default values.
      // 매개변수 타입을 업데이트하여 기본값을 사용할 수 있도록 준비합니다.
      copied.valueParameters.fastForEach { param ->
        if (copied.hasDefaultExpressionDefinedForValueParameter(param.indexInOldValueParameters)) {
          param.type = param.type.defaultParameterType()
        }
      }

      inlineLambdaInfo.scan(copied)

      copied.transformChildrenVoid(object : IrElementTransformerVoid() {
        var isNestedScope = false
        override fun visitGetValue(expression: IrGetValue): IrGetValue {
          val newParam = valueParametersMapping[expression.symbol.owner]
          return if (newParam != null) {
            IrGetValueImpl(
              startOffset = expression.startOffset,
              endOffset = expression.endOffset,
              type = expression.type,
              symbol = newParam.symbol,
              origin = expression.origin,
            )
          } else expression
        }

        override fun visitReturn(expression: IrReturn): IrExpression {
          if (expression.returnTargetSymbol == oldFn.symbol) {
            // update the return statement to point to the new function, or else
            // it will be interpreted as a non-local return.
            //
            // 반환 문을 새 함수를 가리키도록 업데이트하지 않으면 비로컬 반환으로 해석됩니다.
            return super.visitReturn(
              IrReturnImpl(
                startOffset = expression.startOffset,
                endOffset = expression.endOffset,
                type = expression.type,
                returnTargetSymbol = copied.symbol,
                value = expression.value,
              )
            )
          }
          return super.visitReturn(expression)
        }

        override fun visitFunction(declaration: IrFunction): IrStatement {
          val wasNested = isNestedScope
          try {
            // we don't want to pass the composer parameter in to composable calls
            // inside of nested scopes.... *unless* the scope was inlined.
            //
            // STUDY 이게 정확히 무슨 상황이지??
            //
            // 중첩된 스코프의 컴포저블 호출에 $composer 매개변수를 전달하고 싶지 않습니다...
            // 중첩된 스코프가 인라인이 아니라면요.
            isNestedScope =
              wasNested ||
                !inlineLambdaInfo.isInlineLambda(declaration) ||
                declaration.hasComposableAnnotation()

            return super.visitFunction(declaration)
          } finally {
            isNestedScope = wasNested
          }
        }

        override fun visitCall(expression: IrCall): IrExpression {
          val expr =
            if (!isNestedScope)
              expression.copyCallWithComposerParamIfNeeded(composerParam)
            else
            // STUDY `isNestedScope=true`인 IrCall은 어떻게 ComposerParamTransform을 해줄까?
              expression
          return super.visitCall(expr)
        }
      })
    }
  }

  private fun IrCall.copyCallWithComposerParamIfNeeded(composerParam: IrValueParameter): IrCall {
    val newOwner = when {
      symbol.owner.isComposableDelegatedAccessor() -> {
        if (!symbol.owner.hasComposableAnnotation()) {
          symbol.owner.annotations += createComposableAnnotation()
        }
        symbol.owner.copyFunctionWithComposerParamIfNeeded()
      }

      isComposableLambdaInvoke() -> symbol.owner.lambdaInvokeWithComposerParam()

      symbol.owner.hasComposableAnnotation() -> symbol.owner.copyFunctionWithComposerParamIfNeeded()

      // Not a composable call
      else -> return this
    }

    return IrCallImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = type,
      symbol = newOwner.symbol,
      typeArgumentsCount = typeArguments.size,
      origin = origin,
      superQualifierSymbol = superQualifierSymbol,
    ).also { copied ->
      copied.copyAttributes(this)
      copied.copyTypeArgumentsFrom(this)

      copied.dispatchReceiver = dispatchReceiver
      copied.extensionReceiver = extensionReceiver

      // MEMO $default 매개변수 비트마스킹의 정체
      val argumentsMissing = mutableListOf<Boolean>()
      repeat(valueArgumentsCount) { i ->
        val arg = getValueArgument(i)
        val param = newOwner.valueParameters[i]
        val hasDefault = newOwner.hasDefaultExpressionDefinedForValueParameter(i)

        argumentsMissing.add(arg == null && hasDefault)

        if (arg != null) {
          copied.putValueArgument(i, arg)
        } else if (hasDefault) {
          copied.putValueArgument(i, jvmDefaultArgumentValueFor(param))
        } else {
          // do nothing
        }
      }

      val realValueParamsCount = valueArgumentsCount - newOwner.contextReceiverParametersCount
      var composerParamIndex = valueArgumentsCount

      copied.putValueArgument(
        composerParamIndex++,
        IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, composerParam.symbol),
      )

      // $changed[n]
      repeat(
        changedParamCount(
          realValueParamsCount = realValueParamsCount,
          thisParamsCount = newOwner.thisParamCount,
        ),
      ) {
        if (composerParamIndex < newOwner.valueParameters.size) {
          // STUDY $changed가 0으로 고정되어도 괜찮은 상황은?
          copied.putValueArgument(composerParamIndex++, irIntConst(0))
        } else {
          error("1. expected value parameter count to be higher: ${this.dumpSrc()}")
        }
      }

      // $default[n]
      repeat(defaultParamCount(valueArgumentsCount)) { i ->
        val start = i * BITS_PER_INT
        val end = min(start + BITS_PER_INT, valueArgumentsCount)
        if (composerParamIndex < newOwner.valueParameters.size) {
          val argumentsMissingBits = argumentsMissing.toBooleanArray().sliceArray(start until end)
          copied.putValueArgument(composerParamIndex++, irIntConst(bitMask(*argumentsMissingBits)))
        } else if (argumentsMissing.any { it }) {
          error("2. expected value parameter count to be higher: ${this.dumpSrc()}")
        }
      }
    }
  }

  private fun IrSimpleFunction.lambdaInvokeWithComposerParam(): IrSimpleFunction {
    val argCount = valueParameters.size

    // 람다는 기본 매개변수가 없으므로 $default 매개변수를 추가하지 않음
    val extraParamsCount =
      1 + // $composer
        changedParamCount(realValueParamsCount = argCount, thisParamsCount = 0)

    val newFnClass = context.function(argCount + extraParamsCount).owner
    val newInvoke = newFnClass.functions.first { it.name == OperatorNameConventions.INVOKE }

    return newInvoke
  }

  private fun IrSimpleFunction.copy(): IrSimpleFunction =
    context.irFactory.createSimpleFunction(
      startOffset = startOffset,
      endOffset = endOffset,
      origin = origin,
      name = name,
      visibility = visibility,
      isInline = isInline,
      isExpect = isExpect,
      returnType = returnType,
      modality = modality,
      symbol = IrSimpleFunctionSymbolImpl(),
      isTailrec = isTailrec,
      isSuspend = isSuspend,
      isOperator = isOperator,
      isInfix = isInfix,
      isExternal = isExternal,
      containerSource = containerSource,
    ).also { fn ->
      fn.copyAttributes(this)

      val propertySymbol = correspondingPropertySymbol
      if (propertySymbol != null) {
        fn.correspondingPropertySymbol = propertySymbol
        if (propertySymbol.owner.getter == this) {
          propertySymbol.owner.getter = fn
        }
        if (propertySymbol.owner.setter == this) {
          propertySymbol.owner.setter = fn
        }
      }

      fn.parent = parent
      fn.copyTypeParametersFrom(this)

      fun IrType.remapTypeParameters(): IrType = remapTypeParameters(source = this@copy, target = fn)

      fn.returnType = returnType.remapTypeParameters()

      fn.dispatchReceiverParameter = dispatchReceiverParameter?.copyTo(fn)
      fn.extensionReceiverParameter = extensionReceiverParameter?.copyTo(fn)
      fn.valueParameters = valueParameters.map { param ->
        // Composable lambdas will always have `IrGet`s of all of their parameters
        // generated, since they are passed into the restart lambda. This causes an
        // interesting corner case with "anonymous parameters" of composable functions.
        // If a parameter is anonymous (using the name `_`) in user code, you can usually
        // make the assumption that it is never used, but this is technically not the
        // case in composable lambdas. The synthetic name that kotlin generates for
        // anonymous parameters has an issue where it is not safe to dex, so we sanitize
        // the names here to ensure that dex is always safe.
        //
        // sanitize: 불쾌한 부분을 제거하다, 위생 처리하다
        //
        // STUDY "컴포저블 람다는 restartable 람다로 전달되기 때문에 [항상 모든 매개변수의 `IrGet`이 생성]됩니다."
        //  -> 이 작동이 restartable 람다와는 무슨 연관일까?
        //
        // 컴포저블 람다는 restartable 람다로 전달되기 때문에 항상 모든 매개변수의 `IrGet`이
        // 생성됩니다. 이로 인해 컴포저블 함수의 '익명 매개변수'라는 흥미로운 코너 케이스가
        // 발생합니다. 사용자 코드에서 매개 변수가 익명(`_`라는 이름 사용)인 경우 일반적으로
        // 해당 매개 변수가 사용되지 않는다고 가정할 수 있지만 컴포저블 람다에서는 기술적으로
        // 그렇지 않습니다. 익명 매개 변수에 대해 kotlin이 생성하는 synthetic 이름은 덱스에 안전하지
        // 않은 문제가 있으므로 여기에서 이름을 보완하여(sanitize) 항상 덱스가 안전하도록 보장합니다.
        val newName = dexSafeName(param.name)
        param.copyTo(
          irFunction = fn,
          name = newName,
          isAssignable = param.defaultValue != null,
          defaultValue = param.defaultValue?.copyWithNewTypeParams(source = this, target = fn),
        )
      }
      fn.contextReceiverParametersCount = contextReceiverParametersCount
      fn.annotations = annotations.toList()
      fn.metadata = metadata
      fn.body = moveBodyTo(fn)?.copyWithNewTypeParams(source = this, target = fn)
    }

  private fun jvmDefaultArgumentValueFor(param: IrValueParameter): IrExpression? =
    param.type.defaultValueForJvmDefaultArgument().let {
      // STUDY 여기서는 왜 IrComposite를 사용했을까?
      IrCompositeImpl(
        startOffset = it.startOffset,
        endOffset = it.endOffset,
        type = it.type,
        origin = IrStatementOrigin.DEFAULT_VALUE,
        statements = listOf(it),
      )
    }

  // TODO There is an equivalent function in IrUtils, but we can't use it because it
  //  expects a JvmBackendContext. That implementation uses a special "unsafe coerce" builtin
  //  method, which is only available on the JVM backend. On the JS and Native backends we
  //  don't have access to that so instead we are just going to construct the inline class
  //  itself and hope that it gets lowered properly.
  //
  // IrUtils에도 이와 동등한 함수가 있지만, JvmBackendContext를 기대하기 때문에 사용할 수 없습니다.
  // 이 구현은 JVM 백엔드에서만 사용할 수 있는 특수한 "unsafe coerce" 내장 메서드를 사용합니다.
  // JS 및 네이티브 백엔드에서는 이 메서드에 액세스할 수 없으므로 대신 인라인 클래스 자체를 구성하고
  // 제대로 낮아지기를 바랄 것입니다.
  //
  // default parameter로의 기본값이 아니라, Kotlin -> Java에서 생성되는 default 매개변수의 기본값
  private fun IrType.defaultValueForJvmDefaultArgument(
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrExpression {
    if (this !is IrSimpleType || isMarkedNullable() || !isInlineClassType()) {
      return if (isMarkedNullable()) {
        IrConstImpl.constNull(startOffset, endOffset, context.irBuiltIns.nothingNType)
      } else {
        IrConstImpl.defaultValueForType(startOffset, endOffset, type = this)
      }
    }

    val underlyingType = unboxTypeIfInlineOrDefault()
    return unsafeCoerceIntrinsicCall(
      argument = IrConstImpl.defaultValueForType(startOffset, endOffset, type = underlyingType),
      from = underlyingType,
      to = this,
    )
  }

  // we only add a default mask parameter if one of the parameters has a default
  // expression. Note that if this is a "fake override" method, then only the overridden
  // symbols will have the default value expressions.
  //
  // 매개변수 중 하나에 기본 표현식이 있는 경우에만 $default 마스크 매개변수를 추가합니다.
  // 'fake override' 메서드인 경우 재정의된 심볼에만 기본값 표현식이 적용됩니다.
  private fun IrSimpleFunction.requiresDefaultParameter(): Boolean =
    valueParameters.any { it.defaultValue != null } ||
      overriddenSymbols.any { it.owner.requiresDefaultParameter() }

  // TODO overriddenSymbols까지 검사한다는 이름으로 변경하면 공부에 편할 듯?
  private fun IrSimpleFunction.hasDefaultExpressionDefinedForValueParameter(index: Int): Boolean {
    // checking for default value isn't enough, you need to ensure that none of the overrides
    // have it as well...
    //
    // 기본값을 확인하는 것만으로는 충분하지 않으며 오버라이드에도 기본값이 없는지 확인해야 합니다...
    if (valueParameters[index].defaultValue != null) return true

    return overriddenSymbols.any {
      // ComposableFunInterfaceLowering copies extension receiver as a value
      // parameter, which breaks indices for overrides. fun interface cannot
      // have parameters defaults, however, so we can skip here if mismatch is detected.
      //
      // ComposableFunInterfaceLowering은 extension receiver를 값 매개변수로 복사하는데,
      // 이는 오버라이드에 대한 인덱스를 끊습니다. 그러나 fun interface는 매개변수
      // 기본값을 가질 수 없으므로 불일치가 감지되면 여기를 건너뛸 수 있습니다.
      it.owner.valueParameters.size == valueParameters.size &&
        it.owner.hasDefaultExpressionDefinedForValueParameter(index)
    }
  }

  /**
   * Creates stubs for @Composable function with value class parameters that have a default and
   * are wrapping a non-primitive instance.
   * Before Compose compiler 1.5.12, not specifying such parameter resulted in a nullptr exception
   * (b/330655412) at runtime, caused by Kotlin compiler inserting checkParameterNotNull.
   *
   * Converting such parameters to be nullable caused a binary compatibility issue because
   * nullability changed the value class mangle on a function signature. This stub creates a
   * binary compatible function to support old compilers while redirecting to a new function.
   */
  // 원시 타입이 아닌 값을 감싸는 value class를 매개변수로 갖고, 해당 매개변수에 기본 값이 있는
  // @Composable 함수의 스텁을 만듭니다. Compose 컴파일러 1.5.12 이전에는 이러한 매개변수를
  // 지정하지 않으면 런타임에 Kotlin 컴파일러가 checkParameterNotNull을 삽입하여 NPE(b/330655412)가
  // 발생했습니다.
  //
  // 이러한 매개변수를 nullable하게 변환하면 함수 서명 중 value class 매개변수 이름의 mangle이
  // 변경되므로 바이너리 호환성 문제가 발생했습니다. 이 스텁은 새로운 함수로 리디렉션하면서
  // 이전 컴파일러를 지원하기 위해 바이너리 호환 함수를 생성합니다.
  private fun IrSimpleFunction.makeStubForDefaultValueClassIfNeeded(): IrSimpleFunction? {
    if (!isPublicComposableFunction()) {
      return null
    }

    var makeStub = false
    for (i in valueParameters.indices) {
      val param = valueParameters[i]
      if (
        hasDefaultExpressionDefinedForValueParameter(i) &&
        param.type.isInlineClassType() &&
        !param.type.isNullable() &&
        param.type.unboxTypeIfInlineOrDefault().let { !it.isPrimitiveType() && !it.isNullable() }
      ) {
        makeStub = true
        break
      }
    }

    if (!makeStub) return null

    val source = this
    return makeStub().also { stub ->
      transformedFunctionMap[stub] = stub
      transformedFunctions += stub

      stub.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
        statements.add(
          irReturn(
            target = stub.symbol,
            value = irCall(source).apply {
              dispatchReceiver = stub.dispatchReceiverParameter?.let { irGet(it) }
              extensionReceiver = stub.extensionReceiverParameter?.let { irGet(it) }
              stub.typeParameters.fastForEachIndexed { index, param ->
                typeArguments[index] = param.defaultType
              }
              stub.valueParameters.fastForEachIndexed { index, param ->
                putValueArgument(index, irGet(param))
              }
            },
            type = stub.returnType,
          )
        )
      }
    }
  }

  private fun IrSimpleFunction.isPublicComposableFunction(): Boolean =
    hasComposableAnnotation() && (visibility.isPublicAPI || isPublishedApi())

  private fun createComposableAnnotation(): IrConstructorCall =
    IrConstructorCallImpl(
      startOffset = SYNTHETIC_OFFSET,
      endOffset = SYNTHETIC_OFFSET,
      type = composableIrClass.defaultType,
      symbol = composableIrClass.primaryConstructor!!.symbol,
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0,
    )

  private fun jvmNameAnnotation(name: String): IrConstructorCall {
    val clazz: IrClassSymbol = getTopLevelClass(JvmStandardClassIds.Annotations.JvmName)
    val clazzCtor: IrConstructorSymbol = clazz.constructors.first { it.owner.isPrimary }
    val clazzType: IrSimpleType = clazz.createType(hasQuestionMark = false, arguments = emptyList())

    return IrConstructorCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = clazzType,
      symbol = clazzCtor,
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0,
    ).also {
      it.putValueArgument(
        0,
        IrConstImpl.string(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = builtIns.stringType,
          value = name,
        )
      )
    }
  }
}
