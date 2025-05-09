/*
 * Copyright 2021 The Android Open Source Project
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
import androidx.compose.compiler.plugins.kotlin.ComposeFqNames
import androidx.compose.compiler.plugins.kotlin.ComposeNames
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.ComposeWritableSlices
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.inference.ApplierInferencer
import androidx.compose.compiler.plugins.kotlin.inference.ErrorReporter
import androidx.compose.compiler.plugins.kotlin.inference.Item
import androidx.compose.compiler.plugins.kotlin.inference.LazyScheme
import androidx.compose.compiler.plugins.kotlin.inference.LazySchemeStorage
import androidx.compose.compiler.plugins.kotlin.inference.NodeAdapter
import androidx.compose.compiler.plugins.kotlin.inference.NodeKind
import androidx.compose.compiler.plugins.kotlin.inference.Open
import androidx.compose.compiler.plugins.kotlin.inference.Scheme
import androidx.compose.compiler.plugins.kotlin.inference.Token
import androidx.compose.compiler.plugins.kotlin.inference.TypeAdapter
import androidx.compose.compiler.plugins.kotlin.inference.deserializeScheme
import androidx.compose.compiler.plugins.kotlin.inference.mergeWith
import androidx.compose.compiler.plugins.kotlin.irTrace
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.interpreter.getLastOverridden
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.toBuilder
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isClassWithFqName
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.ir.util.isTypeParameter
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * This transformer walks the IR tree to infer the applier annotations such as ComposableTarget,
 * ComposableOpenTarget, and ComposableInferredTarget.
 */
// 이 트랜스포머는 IR 트리를 탐색하여 ComposableTarget, ComposableOpenTarget, ComposableInferredTarget과
// 같은 Applier 어노테이션을 추론합니다.
class ComposableTargetAnnotationsTransformer(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
) : AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags) {
  // Composable 함수는 [applier]라는 이름을 가진 Applier를 기대하도록 선언됩니다. [applier] 값은
  // 임의의 문자열일 수 있지만, diagnostic 메시지에 사용될 설명(descriptive name)이 포함된
  // @ComposableTargetMarker 어노테이션을 가지는 클래스의 정규화된 이름이어야 합니다.
  //
  // [applier] 값은 diagnostic 메시지에 사용되지만, 표시된 어노테이션*을 참조하는 경우 클래스 이름
  // 대신 [ComposableTargetMarker.description]이 사용됩니다.
  //
  //// *표시된 어노테이션이 뭘까? (원문: marked annotation)
  //
  // 컴포즈 컴파일러 플러그인은 대부분의 경우 컴포저블 함수에 대해 @ComposableTarget 또는 이와 동등한
  // @ComposableInferredTarget을 유추할 수 있습니다. 예를 들어 컴포저블 함수가 다른 컴포저블 함수를
  // 호출하는 경우 둘 다 같은 컴포저블 함수 그룹*에 속해야 합니다(즉, 동일한 applier 값을 선언하거나
  // 추론한 경우). 즉, 호출된 함수가 이미 한 그룹에 속해 있는 것으로 확인된 경우 이를 호출하는 함수도
  // 같은 그룹에 속해야 합니다. 두 함수가 서로 다른 그룹에서 호출되는 경우 컴파일러 플러그인은 어떤
  // 그룹이 수신되었고 어떤 그룹이 예상되었는지를 설명하는 diagnostic 메시지를 생성합니다.
  //
  // Composer가 Composition에 변경 사항을 적용하기 위해 사용해야 하는 Applier의 인스턴스는 Composer의
  // 그룹에 해당합니다. Composer는 런타임에 검사되어 컴포저블 함수에 의해 예상되는 Applier가 런타임에
  // 제공된 Applier인지 확인합니다. 이 어노테이션과 컴포즈 컴파일러 플러그인이 수행하는 해당 유효성
  // 검사는 컴파일 시 Applier 타입 불일치를 감지하고, 컴포저블 함수를 호출했을 때 Applier 검사에 실패할
  // 경우 diagnostic 메시지를 발행할 수 있습니다.
  //
  // 대부분의 경우 이 어노테이션은 유추될 수 있습니다. 그러나 이 어노테이션은 ComposeNode를 직접
  // 호출하는 컴포저블 함수, 인터페이스 함수(플러그인이 어노테이션을 유추할 수 있는 본문을 포함하지
  // 않는)와 같은 추상 메서드, SubComposition에서 컴포저블 람다를 사용할 때, 또는 컴포저블 람다가 클래스
  // 필드 또는 전역 변수에 저장되어 있는 경우에 필요합니다.
  //
  // 이러한 경우 어노테이션이 없을 때 컴파일러가 Applier 검사를 무시하고 Applier가 잘못 호출되어도
  // diagnostic을 생성하지 않습니다.
  //
  //// such: 앞에 이미 언급한, 그런[그러한]
  //
  // Params:
  // applier - 컴포저블 호출 검사 중에 사용되는 Applier의 이름입니다. 일반적으로 컴파일러에서 유추합니다.
  // 임의의 문자열 값일 수 있지만 @ComposableTargetMarker로 어노테이션된 클래스의 정규화된 이름일 것으로
  // 예상됩니다.
  //
  // annotation class ComposableTarget(val applier: String)
  private val ComposableTargetClass = getTopLevelClassOrNull(ComposeClassIds.ComposableTarget)

  // 컴포저블은 특정 Applier를 강제하지 않는다고 선언합니다. 컴포저블 함수가 강제하는 Applier에 대한
  // 자세한 내용은 [ComposableTarget]을 참조하세요.
  //
  // [ComposableOpenTarget]은 컴포저블의 Composer가 사용할 Applier의 타입을 받는 타입 매개변수처럼
  // 동작합니다. 즉, 어떤 Applier를 기대할지가 런타임에서 동적으로 계산됩니다.
  //
  // 컴포저블 함수에서 동일한 [ComposableOpenTarget.index]를 가진 모든 컴포저블의 Applier는 서로 동일한
  // 구현체를 사용해야 합니다. 예를 들어 [CompositionLocalProvider]는 content 매개변수를 직접 호출하지만
  // 어떤 Applier라도 될 수 있기 때문에 [ComposableOpenTarget]을 사용하여 [CompositionLocalProvider]의
  // 호출자와 동일한 Applier를 가져야 한다고 선언할 수 있습니다.
  //
  // ```
  // @ComposableOpenTarget(index = 0)
  // @Composable fun CompositionLocalProvider(
  //   vararg values: ProvidedValue<*>,
  //   content: @Composable @ComposableOpenTarget(index = 0) () -> Unit,
  // ) {
  //   currentComposer.startProviders(values)
  //   content()
  //   currentComposer.endProviders()
  // }
  // ```
  //
  // 컴포즈 컴파일러 플러그인에 의해 자동으로 추론되므로 @ComposableOpenTarget은 명시적으로 필요하지
  // 않습니다. 이 값이 추론되는 자세한 과정은 [ComposableTarget]을 참조하세요.
  //
  // Params:
  // index - Applier 타입에 제약이 없거나, 제약을 동적으로 추가할 임의의 인덱스. 음수 인덱스는 모든
  // 컴포저블이 서로 다른 Applier를 가질 수 있습니다. 이 인덱스가 컴포저블 함수 선언에서 한 번만
  // 사용될 때는 음수 인덱스로 지정했거나, @ComposableOpenTarget이 없는 것과 동일합니다.
  //
  // ```
  // @ComposableOpenTarget(index = 0)
  // @Composable fun MyComposable(
  //   aContent: @Composable @ComposableOpenTarget(index = 0) () -> Unit,
  //   bContent: @Composable @ComposableOpenTarget(index = 1) () -> Unit,
  //   cContent: @Composable @ComposableOpenTarget(index = 1) () -> Unit,
  // )
  // ```
  //
  // 위 코드에서 `MyComposable`의 Applier와 `MyComposable.aContent`의 Applier가 서로 동일해야 하고,
  // `MyComposable.bContent`와 `MyComposable.cContent`의 Applier도 서로 동일해야 합니다.
  //
  // annotation class ComposableOpenTarget(val index: Int)
  private val ComposableOpenTargetClass = getTopLevelClassOrNull(ComposeClassIds.ComposableOpenTarget)

  // 컴포즈 컴파일러 플러그인에 의해 자동으로 적용되는 어노테이션입니다. 명시적으로 사용하지 마세요.
  //
  // @ComposableInferredTarget 어노테이션은 하나 이상의 컴포저블 람다 매개변수가 있는 컴포저블 함수의
  // 대상(target)을 추론할 때 생성되는 @ComposableTarget 및 @ComposableOpenTarget의 축약된 형태입니다.
  //
  // 이는 플러그인에서만 생성하도록 되어 있으며 직접 사용해서는 안 됩니다. 대신 @ComposableOpenTarget 및
  // @ComposableTarget을 사용하세요.
  //
  // 코틀린 컴파일러 플러그인은 컴포저블 람다와 같은 람다 타입에 어노테이션을 추가하는 걸 허용하지
  // 않습니다. 그래서 컴포즈 컴파일러 플러그인에서만 이 어노테이션을 사용할 수 있고, 이 어노테이션은
  // 어떤 어노테이션이 추가되었는지를 기록합니다.
  //
  //// -> 마지막 문단은 [InferenceFunctionDeclaration] KDoc 참고하면 이해됨
  //
  // annotation class ComposableInferredTarget(val scheme: String)
  private val ComposableInferredTargetClass = getTopLevelClassOrNull(ComposeClassIds.ComposableInferredTarget)

  /**
   * A map of element to the owning function of the element.
   *
   * [IrElement]를 소유하고 있는 [IrFunction]의 [Map].
   */
  private val ownerMap = mutableMapOf<IrElement, IrFunction>()

  /**
   * Map of a parameter symbol to its function and parameter index.
   *
   * 매개변수 심볼을 해당 매개변수를 정의한 함수와 매개변수 인덱스에 매핑합니다.
   * 오직 컴포저블 타입인 매개변수만 처리됩니다.
   */
  private val parameterOwners = mutableMapOf<IrSymbol, Pair<IrFunction, Int>>()

  /**
   * A map of variables to their corresponding inference node.
   *
   * 변수와 해당 변수의 추론 노드의 맵입니다.
   * 오직 컴포저블 타입인 변수만 처리됩니다.
   */
  private val variableDeclarations = mutableMapOf<IrSymbol, InferenceVariable>()

  private var currentFile: IrFile? = null
  private var currentFunction: IrFunction? = null

  private fun lineInfoOf(element: IrElement?): String {
    val file = currentFile
    if (element != null && file != null) {
      return " " +
        "${file.name}:" +
        "${file.fileEntry.getLineNumber(element.startOffset) + 1}:" +
        "${file.fileEntry.getColumnNumber(element.startOffset) + 1}"
    }
    return ""
  }

  private val inferencer = ApplierInferencer(
    typeAdapter = object : TypeAdapter<InferenceFunction> {
      val current = mutableMapOf<InferenceFunction, Scheme>()

      override fun declaredSchemaOf(type: InferenceFunction): Scheme =
        type.toDeclaredScheme().also { type.recordSchemeToMetrics(it) }

      override fun currentInferredSchemeOf(type: InferenceFunction): Scheme? =
        if (type.schemeIsUpdatable) current[type] ?: declaredSchemaOf(type) else null

      override fun updatedInferredScheme(type: InferenceFunction, scheme: Scheme) {
        type.recordSchemeToMetrics(scheme)
        type.updateSchemeToAnnotation(scheme)
        current[type] = scheme
      }
    },
    nodeAdapter = object : NodeAdapter<InferenceFunction, InferenceNode> {
      override fun containerOf(node: InferenceNode): InferenceNode =
        ownerMap[node.element]
          ?.let { inferenceNodeOf(element = it, transformer = this@ComposableTargetAnnotationsTransformer) }
          ?: (node as? InferenceResolvedParameter)?.referenceContainer
          ?: node

      override fun kindOf(node: InferenceNode): NodeKind = node.kind

      override fun schemeParameterIndexOf(
        node: InferenceNode,
        container: InferenceNode,
      ): Int = node.parameterIndex(container)

      override fun typeOf(node: InferenceNode): InferenceFunction? = node.function

      override fun referencedContainerOf(node: InferenceNode): InferenceNode? = node.referenceContainer
    },
    lazySchemeStorage = object : LazySchemeStorage<InferenceNode> {
      // The transformer is transitory so we can just store this in a map.
      // transformer는 일시적이기 때문에 이 맵에 저장하면 됩니다.
      val map = mutableMapOf<InferenceNode, LazyScheme>()

      override fun getLazyScheme(node: InferenceNode): LazyScheme? = map[node]
      override fun storeLazyScheme(node: InferenceNode, value: LazyScheme) {
        map[node] = value
      }
    },
    errorReporter = object : ErrorReporter<InferenceNode> {
      override fun reportCallError(node: InferenceNode, expected: String, received: String) {
        // Ignored, should be reported by the front-end
      }

      override fun reportParameterError(
        node: InferenceNode,
        index: Int,
        expected: String,
        received: String,
      ) {
        // Ignored, should be reported by the front-end
      }

      override fun log(node: InferenceNode?, message: String) {
        val element = node?.element
        if (!metrics.isEmpty) metrics.log("applier inference${lineInfoOf(element)}: $message")
      }
    },
  )

  val IrType.isOrHasComposableLambda: Boolean
    get() =
      isComposableLambda ||
        isSamComposable ||
        (this as? IrSimpleType)?.arguments?.any { it.typeOrNull?.isOrHasComposableLambda == true } == true

  val List<IrConstructorCall>.composableTarget: Item
    get() =
      run {
        firstOrNull { it.isComposableTarget }
          ?.firstConstParameterOrNull<String>()
          ?.let { Token(it) }
      } ?: run {
        firstOrNull { it.isComposableOpenTarget }
          ?.firstConstParameterOrNull<Int>()
          ?.let { Open(it) }
      } ?: run {
        firstOrNull { it.isComposableTargetMarker }?.let { constructor ->
          val fqName = constructor.symbol.owner.parentAsClass.fqNameWhenAvailable
          fqName?.let { Token(it.asString()) }
        }
      } ?: Open(index = -1, isUnspecified = true)

  val IrFunction.composableInferredTargetScheme: Scheme?
    get() =
      annotations
        .firstOrNull { it.isComposableInferredTarget }
        ?.firstConstParameterOrNull<String>()
        ?.let { deserializeScheme(it) }

  override fun lower(irModule: IrModuleFragment) {
    // Only transform if the attributes being inferred are in the runtime.
    // 유추되는 어노테이션이 런타임에 있는 경우에만 transform합니다.
    if (
      ComposableTargetClass != null &&
      ComposableInferredTargetClass != null &&
      ComposableOpenTargetClass != null
    ) {
      irModule.transformChildrenVoid(this)
    }
  }

  override fun visitFile(declaration: IrFile): IrFile =
    includeFileNameInExceptionTrace(declaration) {
      currentFile = declaration
      super.visitFile(declaration).also { currentFile = null }
    }

  override fun visitFunction(declaration: IrFunction): IrStatement {
    if (
      declaration.hasComposableTargetSpecified() ||
      (!declaration.isComposable && !declaration.hasComposableParameter()) ||
      declaration.hasOverlyWideParameters() ||
      declaration.hasTypeParameter()
    ) {
      return super.visitFunction(declaration)
    }

    val oldOwner = currentFunction
    currentFunction = declaration

    var currentParameter = 0
    fun recordParameter(parameter: IrValueParameter) {
      if (parameter.type.isOrHasComposableLambda) {
        parameterOwners[parameter.symbol] = declaration to currentParameter++
      }
    }

    declaration.valueParameters.forEach { recordParameter(it) }
    declaration.extensionReceiverParameter?.let { recordParameter(it) }

    val result = super.visitFunction(declaration)

    currentFunction = oldOwner

    return result
  }

  override fun visitVariable(declaration: IrVariable): IrStatement {
    if (declaration.type.isOrHasComposableLambda) {
      currentFunction?.let { ownerMap[declaration] = it }

      val initializerNode = declaration.initializer
      if (initializerNode != null) {
        val initializer =
          resolveExpressionToInferenceNodeOrNull(expression = initializerNode)
            ?: InferenceElementExpression(
              element = initializerNode,
              transformer = this@ComposableTargetAnnotationsTransformer,
            )
        val variable = InferenceVariable(element = declaration, transformer = this)

        variableDeclarations[declaration.symbol] = variable
        inferencer.visitVariable(variable = variable, initializer = initializer)
      }
    }

    return super.visitVariable(declaration)
  }

  override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
    val result = super.visitLocalDelegatedProperty(declaration)
    if (declaration.type.isOrHasComposableLambda) {
      // Find the inference variable for the delegate which should have been created
      // when visiting the delegate node. If not, then ignore this declaration.
      //
      // 델리게이트 노드를 방문할 때 생성되었어야 하는 델리게이트에 대한 추론 변수를 찾습니다.
      // 그렇지 않은 경우 이 선언을 무시하세요.
      val variable = variableDeclarations[declaration.delegate.symbol] ?: return result

      // Allow the variable to be found from the getter as this is what is used to access
      // the variable, not the delegate directly.
      //
      // 델리게이트가 직접 변수에 액세스하는 것이 아니라 게터에서 변수를 찾을 수 있도록
      // 허용합니다.
      variableDeclarations[declaration.getter.symbol] = variable
    }
    return result
  }

  override fun visitCall(expression: IrCall): IrExpression {
    val owner = currentFunction

    if (
      owner == null ||
      (!expression.isComposableCall() && !expression.hasComposableArguments()) ||
      when (expression.symbol.owner.fqNameWhenAvailable) {
        ComposeFqNames.getCurrentComposerFullName, ComposeFqNames.composableLambdaFullName -> true
        else -> false
      }
    ) {
      // owner function이 없거나, 컴포저블 함수가 아니거나, compose intrinsic 함수일 때
      return super.visitCall(expression)
    }

    ownerMap[expression] = owner

    val result = super.visitCall(expression)
    val target = (
      if (
        expression.isInvoke() ||
        expression.dispatchReceiver?.type?.isSamComposable == true
      ) {
        expression.dispatchReceiver?.let { resolveExpressionToInferenceNodeOrNull(expression = it) }
      } else {
        resolveExpressionToInferenceNodeOrNull(expression = expression)
      }
      ) ?: InferenceCallTargetNode(element = expression, transformer = this)

    if (target.isOverlyWide()) return result

    val composableArguments =
      expression.valueArguments
        .filterIndexed { index, argument ->
          argument?.let { argument ->
            argument.isComposableLambda ||
              argument.isComposableParameter || (
              if (
              // There are three cases where the expression type is not good enough here,
              // one, the type is a default parameter and there is no actual expression
              // and, two, when the expression is a SAM conversion where the type is
              // too specific (it is the class) and we need the SAM interface, and three
              // the value is null for a nullable type.
              //
              // 여기서 표현식 타입이 좋지 않은 경우는 세 가지로, 첫째, 타입이 기본 매개변수이고
              // 실제 표현식이 없는 경우, 둘째, 표현식이 SAM 변환으로 타입이 너무 구체적이어서(클래스)
              // SAM 인터페이스가 필요한 경우, 셋째, 값이 nullable 타입에 대해 null인 경우입니다.
                (argument is IrContainerExpression && argument.origin == IrStatementOrigin.DEFAULT_VALUE) ||
                argument is IrBlock ||
                argument.isNullConst()
              ) {
                // If the parameter is a default value, grab the type from the function
                // being called.
                //
                // 매개변수가 기본값인 경우 callee symbol에서 타입을 가져옵니다.
                expression.symbol.owner.valueParameters.let { parameters ->
                  if (index < parameters.size) parameters[index].type else null
                }
              } else argument.type
              )
              ?.isOrHasComposableLambda == true
          } == true
        }
        .filterNotNull()
        .toMutableList()

    fun recordArgument(argument: IrExpression?) {
      if (
        argument != null && (
          argument.isComposableLambda ||
            argument.isComposableParameter ||
            argument.type.isOrHasComposableLambda
          )
      ) {
        composableArguments.add(argument)
      }
    }

    recordArgument(expression.extensionReceiver)

    inferencer.visitCall(
      call = inferenceNodeOf(element = expression, transformer = this@ComposableTargetAnnotationsTransformer),
      target = target,
      arguments = composableArguments.map { argument ->
        resolveExpressionToInferenceNodeOrNull(expression = argument)
          ?: inferenceNodeOf(element = argument, transformer = this@ComposableTargetAnnotationsTransformer)
      },
    )

    return result
  }

  fun IrCall.hasTransformedLambda(): Boolean =
    context.irTrace[ComposeWritableSlices.HAS_TRANSFORMED_LAMBDA, this] == true

  fun IrElement.transformedLambda(): IrFunctionExpression =
    findTransformedLambda() ?: error("Could not find the lambda for ${dump()}")

  // If this function throws an error it is because the IR transformation for singleton functions
  // changed. This function should be updated to account for the change.
  //
  // 이 함수가 오류를 발생시키는 경우 싱글톤 함수에 대한 IR 변환이 변경되었기 때문입니다.
  // 이 함수는 변경 사항을 고려하여 업데이트해야 합니다.
  fun IrCall.singletonFunctionExpression(): IrFunctionExpression =
    symbol.owner.body?.findTransformedLambda() ?: error("Could not find the singleton lambda for ${dump()}")

  fun IrFunction.hasComposableTargetSpecified(): Boolean =
    annotations.any {
      it.isComposableTarget ||
        it.isComposableOpenTarget ||
        it.isComposableInferredTarget ||
        it.isComposableTargetMarker
    }

  fun IrType.toScheme(defaultTarget: Item): Scheme =
    when {
      this is IrSimpleType && isFunction() -> arguments
      else -> emptyList()
    }.let { typeArguments ->
      val target = annotations.composableTarget.let { target ->
        if (target.isUnspecified) defaultTarget else target
      }

      fun toScheme(argument: IrTypeArgument): Scheme? =
        if (argument is IrTypeProjection && argument.type.isOrHasComposableLambda)
          argument.type.toScheme(defaultTarget = defaultTarget)
        else
          null

      val parameters =
        typeArguments
          .takeOrEmpty(typeArguments.size - 1)
          .mapNotNull { argument -> toScheme(argument = argument) }

      val result =
        typeArguments
          .lastOrNull()
          ?.let { argument -> toScheme(argument = argument) }

      Scheme(target = target, parameters = parameters, result = result)
    }

  fun updatedAnnotations(annotations: List<IrConstructorCall>, target: Item): List<IrConstructorCall> =
    removeComposableTargetAnnotations(annotations) + target.toComposableTargetAnnotations()

  fun updatedAnnotations(annotations: List<IrConstructorCall>, scheme: Scheme): List<IrConstructorCall> =
    removeComposableTargetAnnotations(annotations) + scheme.toComposableInferredTargetAnnotations()

  fun inferenceFunctionOf(function: IrFunction): InferenceFunctionDeclaration =
    InferenceFunctionDeclaration(function = function, transformer = this)

  fun inferenceFunctionTypeOf(type: IrType): InferenceFunctionType =
    InferenceFunctionType(type = type, transformer = this)

  /**
   * A function is composable if it has a composer parameter added by the
   * [ComposerParamTransformer] or it still has the @Composable annotation which
   * can be because it is external and hasn't been transformed as the symbol remapper
   * only remaps what is referenced as a symbol this method might not have been
   * referenced directly in this module.
   *
   * [ComposerParamTransformer]에 의해 추가된 $composer 파라미터가 있거나 @Composable 어노테이션이
   * 있는 경우 함수는 컴포저블이 가능하며, 이는 SymbolRemapper가 이 모듈에서 직접 참조되지 않은
   * 심볼로 참조된 것만 리매핑하기 때문에 외부에 있고 변환되지 않았을 수 있기 때문일 수 있습니다.
   */
  private val IrFunction.isComposable: Boolean
    get() =
      valueParameters.any { it.name == ComposeNames.COMPOSER_PARAMETER } ||
        annotations.hasAnnotation(ComposeFqNames.Composable)

  private val IrType.isComposable: Boolean
    get() = isComposableLambda || isSamComposable

  private val IrType.isSamComposable: Boolean
    get() = samOwnerOrNull()?.isComposable == true

  private val IrType.isComposableLambda: Boolean
    get() =
      (this.classFqName == ComposeFqNames.composableLambdaType) || // composableLambdaType: ComposerLambdaMemoization transforming result
        (this as? IrSimpleType)
          ?.arguments
          ?.any { it.typeOrNull?.classFqName == ComposeFqNames.Composer } == true

  private val IrElement?.isComposableLambda: Boolean
    get() = when (this) {
      is IrFunctionExpression -> function.isComposable
      is IrCall -> isComposableSingletonGetter() || hasTransformedLambda()
      is IrGetField -> symbol.owner.initializer?.findTransformedLambda() != null
      else -> false
    }

  private val IrElement?.isComposableParameter: Boolean
    get() = when (this) {
      is IrGetValue -> parameterOwners[symbol] != null && type.isComposable
      else -> false
    }

  /**
   * Resolve references to local variables and parameters.
   */
  private fun resolveExpressionToInferenceNodeOrNull(expression: IrElement?): InferenceNode? =
    when (expression) {
      // parameter reference call
      is IrGetValue ->
        // Get the inference node for referencing a local variable or parameter if this
        // expression does.
        //
        // 이 표현식이 로컬 변수 또는 매개변수를 참조하는 경우 추론 노드를 가져옵니다.
        inferenceParameterOrNull(getValue = expression) ?: variableDeclarations[expression.symbol]

      // local variable call
      is IrCall ->
        // If this call is a call to the getter of a local delegate get the inference
        // node of the delegate.
        //
        // 주어진 호출이 로컬 델리게이트의 getter 호출인 경우 델리게이트의 추론 노드를 가져옵니다.
        variableDeclarations[expression.symbol]
      else -> null
    }

  private fun inferenceParameterOrNull(getValue: IrGetValue): InferenceResolvedParameter? =
    parameterOwners[getValue.symbol]?.let { (owner, parameterIndex) ->
      InferenceResolvedParameter(
        element = getValue,
        function = inferenceFunctionOf(function = owner),
        container = inferenceNodeOf(element = owner, transformer = this),
        index = parameterIndex,
      )
    }

  private fun IrFunction.hasComposableParameter(): Boolean =
    valueParameters.any { it.type.isComposable }

  private fun IrCall.hasComposableArguments(): Boolean =
    valueArguments.any { argument ->
      argument?.type?.let { type -> type.isOrHasComposableLambda || type.isSamComposable } == true
    }

  private fun IrElement.findTransformedLambda(): IrFunctionExpression? =
    when (this) {
      is IrCall -> valueArguments.firstNotNullOfOrNull { it?.findTransformedLambda() }
      is IrGetField -> symbol.owner.initializer?.findTransformedLambda()
      is IrBody -> statements.firstNotNullOfOrNull { it.findTransformedLambda() }
      is IrReturn -> value.findTransformedLambda()
      is IrFunctionExpression -> if (isTransformedLambda()) this else null
      else -> null
    }

  private fun IrFunctionExpression.isTransformedLambda(): Boolean =
    context.irTrace[ComposeWritableSlices.IS_TRANSFORMED_LAMBDA, this] == true

  private fun Scheme.toComposableInferredTargetAnnotations(): List<IrConstructorCall> =
    if (ComposableInferredTargetClass != null)
      listOf(annotation(ComposableInferredTargetClass).also { it.putValueArgument(0, irStringConst(serialize())) })
    else
      emptyList()

  private fun Item.toComposableTargetAnnotations(): List<IrConstructorCall> =
    toComposableTargetAnnotation()?.let { listOf(it) }.orEmpty()

  private fun Item.toComposableTargetAnnotation(): IrConstructorCall? =
    if (ComposableTargetClass != null && ComposableOpenTargetClass != null) {
      when (this) {
        is Token -> annotation(symbol = ComposableTargetClass).also { it.putValueArgument(0, irStringConst(value)) }
        is Open ->
          if (index < 0)
            null
          else
            annotation(symbol = ComposableOpenTargetClass).also { it.putValueArgument(0, irIntConst(index)) }
      }
    } else null

  private fun annotation(symbol: IrClassSymbol): IrConstructorCall =
    IrConstructorCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = symbol.defaultType,
      symbol = symbol.constructors.first(),
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0,
      origin = null,
    )

  private fun removeComposableTargetAnnotations(
    annotations: List<IrConstructorCall>,
  ): List<IrConstructorCall> =
    annotations.filter {
      !it.isComposableTarget &&
        !it.isComposableOpenTarget &&
        !it.isComposableInferredTarget
    }
}

/**
 * An [InferenceFunction] is an abstraction to allow inference to translate a type into a scheme
 * and update the declaration of a type if inference determines a more accurate scheme.
 *
 * InferenceFunction은 추론이 타입을 스키마로 변환하고 추론이 더 정확한 스키마를 결정할 경우
 * 타입 선언을 업데이트할 수 있도록 하는 추상화입니다.
 */
sealed class InferenceFunction(val transformer: ComposableTargetAnnotationsTransformer) {
  /**
   * The name of the function. This is only supplied for debugging.
   */
  abstract val name: String

  /**
   * Can the scheme be updated. If not, tell inference not to track it.
   *
   * 스키마를 업데이트할 수 있나요? 그렇지 않은 경우 추론에 추적하지 않도록 하세요.
   */
  abstract val schemeIsUpdatable: Boolean

  /**
   * Record a scheme for the function in metrics (if applicable).
   *
   * metrics에 함수의 Scheme을 기록합니다(해당되는 경우).
   */
  open fun recordSchemeToMetrics(scheme: Scheme) {}

  /**
   * The scheme has changed so the corresponding attributes should be updated to match the
   * scheme provided.
   *
   * Scheme이 변경되었으므로 제공된 Scheme과 일치하도록 해당 속성(attribute)을 업데이트해야
   * 합니다.
   */
  abstract fun updateSchemeToAnnotation(scheme: Scheme)

  /**
   * Return a declared scheme for the function.
   */
  abstract fun toDeclaredScheme(defaultTarget: Item = Open(0)): Scheme

  /**
   * Return true if this is a type with overly wide parameter types such as Any or
   * unconstrained or insufficiently constrained type parameters.
   *
   * overly: 너무, 몹시
   *
   * Any 또는 제약되지 않거나 불충분하게 제약된 타입 매개변수와 같이 지나치게 넓은
   * 매개변수 타입이 있는 타입인 경우 true를 반환합니다.
   */
  open fun isOverlyWide(): Boolean = false

  /**
   * Helper routine to produce an updated annotations list.
   *
   * 헬퍼 루틴을 사용하여 업데이트된 어노테이션 목록을 생성합니다.
   */
  fun updatedAnnotations(annotations: List<IrConstructorCall>, target: Item): List<IrConstructorCall> =
    transformer.updatedAnnotations(annotations = annotations, target = target)

  /**
   * Helper routine to produce an updated annotations list.
   *
   * 헬퍼 루틴을 사용하여 업데이트된 어노테이션 목록을 생성합니다.
   */
  fun updatedAnnotations(annotations: List<IrConstructorCall>, scheme: Scheme): List<IrConstructorCall> =
    transformer.updatedAnnotations(annotations = annotations, scheme = scheme)
}

/**
 * An [InferenceFunctionDeclaration] refers to the type implied by a function declaration.
 *
 * Storing [Scheme] information is complicated by the current IR transformer limitation that
 * annotations added to types are not serialized. Instead of updating the parameter types
 * directly (for example adding a annotation to the IrType of the parameter declaration) the
 * [Scheme] is serialized into a string and stored on the function declaration.
 *
 * [InferenceFunctionDeclaration]은 함수 선언이 암시하는 유형을 나타냅니다.
 *
 * 타입에 추가된 어노테이션은 직렬화되지 않는다는 현재 IR 트랜스포머의 제한으로 인해 스키마 정보
 * 저장은 복잡합니다. 매개변수 타입을 직접 업데이트하는 대신(예: 매개변수 선언의 IrType에 어노테이션을 추가)
 * Scheme을 문자열로 직렬화하여 함수 선언에 저장합니다.
 */
class InferenceFunctionDeclaration(
  val function: IrFunction,
  transformer: ComposableTargetAnnotationsTransformer,
) : InferenceFunction(transformer) {
  override val name: String get() = function.name.toString()
  override val schemeIsUpdatable: Boolean get() = true

  private val Scheme.shouldSerialize
    get(): Boolean = parameters.isNotEmpty()

  override fun recordSchemeToMetrics(scheme: Scheme) {
    if (!scheme.allAnonymous()) {
      with(transformer) {
        metricsFor(function).recordScheme(scheme.toString())
      }
    }
  }

  override fun updateSchemeToAnnotation(scheme: Scheme) {
    if (scheme.shouldSerialize) {
      function.annotations = updatedAnnotations(annotations = function.annotations, scheme = scheme)
    } else {
      function.annotations = updatedAnnotations(annotations = function.annotations, target = scheme.target)
      parameters().zip(scheme.parameters) { parameter, parameterScheme ->
        parameter.updateSchemeToAnnotation(parameterScheme)
      }
    }
  }

  override fun toDeclaredScheme(defaultTarget: Item): Scheme =
    with(transformer) {
      function.composableInferredTargetScheme ?: function.toScheme(defaultTarget)
    }

  private fun IrFunction.toScheme(defaultTarget: Item): Scheme =
    with(transformer) {
      val target = function.annotations.composableTarget.let { target ->
        if (target.isUnspecified && function.fileOrNull == null) {
          defaultTarget
        } else if (target.isUnspecified) {
          // Default to the target specified at the file scope, if one.
          // 기본값은 파일 범위에 지정된 대상(있는 경우)으로 설정합니다.
          function.file.annotations.composableTarget
        } else target
      }

      // STUDY 함수 본문이 있다면 왜 Open Applier 일까? (LazyScheme이라 그런가?)
      val effectiveDefault = if (function.body == null) defaultTarget else Open(index = -1, isUnspecified = true)

      val result = function.returnType.let { resultType ->
        if (resultType.isOrHasComposableLambda) resultType.toScheme(defaultTarget = effectiveDefault) else null
      }

      Scheme(
        target = target,
        parameters = parameters().map { it.toDeclaredScheme(effectiveDefault) },
        result = result,
      ).let { scheme ->
        ancestorScheme(defaultTarget = defaultTarget)
          ?.let { scheme.mergeWith(schemes = listOf(it)) }
          ?: scheme
      }
    }

  private fun parameters(): List<InferenceFunction> =
    with(transformer) {
      function.valueParameters
        .filter { it.type.isOrHasComposableLambda }
        .map { parameter -> InferenceFunctionParameter(transformer = transformer, parameter = parameter) }
        .let { parameters ->
          function.extensionReceiverParameter
            ?.let { receiver ->
              if (receiver.type.isOrHasComposableLambda) {
                parameters + listOf(InferenceFunctionParameter(transformer = transformer, parameter = receiver))
              } else parameters
            }
            ?: parameters
        }
    }

  private fun Scheme.allAnonymous(): Boolean =
    target.isAnonymous &&
      (result == null || result.allAnonymous()) &&
      parameters.all { it.allAnonymous() }

  private fun IrFunction.ancestorScheme(defaultTarget: Item): Scheme? =
    if (this is IrSimpleFunction && this.overriddenSymbols.isNotEmpty()) {
      getLastOverridden().toScheme(defaultTarget = defaultTarget)
    } else null

  override fun hashCode(): Int = function.hashCode() * 31
  override fun equals(other: Any?): Boolean = other is InferenceFunctionDeclaration && other.function == function
}

/**
 * The type of a function parameter. The parameter of a function needs to update where
 * it came from.
 *
 * 함수 매개변수의 유형입니다. 함수의 매개변수가 어디에서 왔는지 업데이트해야 합니다.
 */
class InferenceFunctionParameter(
  transformer: ComposableTargetAnnotationsTransformer,
  val parameter: IrValueParameter,
) : InferenceFunction(transformer) {
  override val name: String get() = "<parameter>"
  override val schemeIsUpdatable: Boolean get() = false

  override fun updateSchemeToAnnotation(scheme: Scheme) {
    // Note that this is currently not called. Type annotations are serialized into an
    // ComposableInferredAnnotation. This is kept here as example of how the type should
    // be updated once such a modification is correctly serialized by Kotlin.
    //
    // 현재 이 함수는 호출되지 않습니다. 타입 어노테이션은 @ComposableInferredAnnotation으로
    // 직렬화됩니다. 여기에서는 이러한 수정 사항이 Kotlin에서 올바르게 직렬화되면 타입을
    // 어떻게 업데이트해야 하는지에 대한 예시로 유지됩니다.
    val type = parameter.type
    if (type is IrSimpleType) {
      val newType: IrSimpleType =
        type.toBuilder()
          .apply { annotations = updatedAnnotations(annotations = annotations, target = scheme.target) }
          .buildSimpleType()
      parameter.type = newType
    }
  }

  override fun toDeclaredScheme(defaultTarget: Item): Scheme = with(transformer) {
    val samAnnotations = parameter.type.samOwnerOrNull()?.annotations.orEmpty()
    val annotations = parameter.type.annotations + samAnnotations
    val target = annotations.composableTarget.let { if (it.isUnspecified) defaultTarget else it }

    parameter.type.toScheme(defaultTarget = target)
  }

  override fun hashCode(): Int = parameter.hashCode() * 31
  override fun equals(other: Any?): Boolean = other is InferenceFunctionParameter && other.parameter == parameter
}

/**
 * Produce the scheme from a function type.
 *
 * 함수 유형에서 스키마를 생성합니다.
 */
class InferenceFunctionType(
  private val type: IrType,
  transformer: ComposableTargetAnnotationsTransformer,
) : InferenceFunction(transformer) {
  override val name: String get() = "<type>"
  override val schemeIsUpdatable: Boolean get() = false

  override fun updateSchemeToAnnotation(scheme: Scheme) {
    // Cannot update the scheme of a type yet. This is worked around for parameters by recording
    // the inferred scheme for the parameter in a serialized scheme for the function. For other
    // types we don't need to record the inference we just need the declared scheme.
    //
    // 아직 타입의 스키마를 업데이트할 수 없습니다. 매개변수의 경우, 함수의 직렬화된 스키마에
    // 매개변수의 추론된 스키마를 기록하여 이 문제를 해결할 수 있습니다. 다른 유형의 경우 추론을
    // 기록할 필요가 없으며, 선언된 스키마만 있으면 됩니다.
  }

  override fun toDeclaredScheme(defaultTarget: Item): Scheme =
    with(transformer) { type.toScheme(defaultTarget = defaultTarget) }
}

/**
 * An [InferenceFunctionCallType] is the type abstraction for a call. This is used for [IrCall]
 * because it has the substituted types for generic types and the function's symbol has the original
 * unsubstituted types. It is important, for example for calls to [let], that the arguments
 * and result are after generic resolution so calls like `content?.let { it.invoke() }` correctly
 * infer that the scheme is `[0[0]]` if `content` is a of type `(@Composable () -> Unit)?. This
 * can only be determined after the generic parameters have been substituted.
 *
 * [InferenceFunctionCallType]은 호출에 대한 타입 추상화입니다. [IrCall]에 사용되는 이유는 제네릭
 * 타입에 대한 substituted type을 가지고 있고, 함수 심볼에는 원래의 unsubstituted type이 있기 때문입니다.
 * 예를 들어 `let` 호출의 경우, 인수와 결과가 제네릭 확인 후에 오는 것이 중요합니다. 따라서
 * `content?.let { it.invoke() }`와 같은 호출은 content가 `(@Composable () -> Unit)?` 타입인 경우
 * 스키마가 [0[0]]임을 올바르게 추론합니다. 이는 제네릭 매개변수가 대체된 후에만 확인할 수 있습니다.
 */
class InferenceFunctionCallType(
  private val call: IrCall,
  transformer: ComposableTargetAnnotationsTransformer,
) : InferenceFunction(transformer) {
  override val name: String get() = "Call(${call.symbol.owner.name})"
  override val schemeIsUpdatable: Boolean get() = false

  override fun updateSchemeToAnnotation(scheme: Scheme) {
    // Ignore the updated scheme for the call as it can always be re-inferred.
    // 호출에 대한 업데이트된 Scheme은 언제든지 다시 추론할 수 있으므로 무시하세요.
  }

  override fun toDeclaredScheme(defaultTarget: Item): Scheme =
    with(transformer) {
      val target: Item = call.symbol.owner.annotations.composableTarget.let { target ->
        if (target.isUnspecified) defaultTarget else target
      }
      val parameters: MutableList<Scheme> =
        call.valueArguments
          .filterNotNull()
          .filter { it.type.isOrHasComposableLambda }
          .map { it.type.toScheme(defaultTarget = defaultTarget) }
          .toMutableList()

      fun recordParameter(expression: IrExpression?) {
        if (expression != null && expression.type.isOrHasComposableLambda) {
          parameters.add(expression.type.toScheme(defaultTarget = defaultTarget))
        }
      }

      recordParameter(call.extensionReceiver)

      val result =
        if (call.type.isOrHasComposableLambda)
          call.type.toScheme(defaultTarget = defaultTarget)
        else
          null

      Scheme(target = target, parameters = parameters, result = result)
    }

  override fun isOverlyWide(): Boolean = call.symbol.owner.hasOverlyWideParameters()
}

/**
 * A wrapper around IrElement to return the information requested by inference.
 *
 * 추론에서 요청한 정보를 반환하기 위해 [IrElement]를 둘러싼 래퍼입니다.
 */
sealed class InferenceNode {
  /**
   * The element being wrapped
   */
  abstract val element: IrElement

  /**
   * The node kind of the node
   */
  abstract val kind: NodeKind

  /**
   * The function type abstraction used by inference
   */
  abstract val function: InferenceFunction?

  /**
   * The container node being referred to by the this node, if there is one. For example, if this
   * is a parameter reference then this is the function that contains the parameter (which
   * parameterIndex can be used to determine the parameter index). If it is a call to a static
   * function then the reference is to the IrFunction that is being called.
   *
   * 이 노드가 참조하는 컨테이너 노드(있는 경우)입니다. 예를 들어 매개변수 참조인 경우 매개변수가
   * 포함된 함수입니다(매개변수 인덱스를 결정하는 데 사용할 수 있는 parameterIndex). 정적 함수에
   * 대한 호출인 경우 참조는 호출 중인 IrFunction에 대한 참조입니다.
   */
  open val referenceContainer: InferenceNode? = null

  /**
   * [node] is one of the parameters of this container node then return its index. -1 indicates
   * that [node] is not a parameter of this container (or this is not a container).
   *
   * 노드가 이 컨테이너 노드의 매개변수 중 하나이면 해당 인덱스를 반환합니다. -1은 노드가 이
   * 컨테이너의 파라미터가 아니거나 컨테이너가 아님을 나타냅니다.
   */
  open fun parameterIndex(node: InferenceNode): Int = -1

  /**
   * An overly wide function (a function with Any types) is too wide to use for to infer an
   * applier (that is it contains parameters of type Any or Any?).
   *
   * 지나치게 넓은 함수(Any 타입의 함수)는 Applier를 추론하는 데 사용하기에는 너무 넓습니다.
   * (즉, Any 또는 Any? 타입의 매개변수가 포함되어 있음)
   */
  open fun isOverlyWide(): Boolean = function?.isOverlyWide() == true

  override fun hashCode(): Int = element.hashCode() * 31
  override fun equals(other: Any?): Boolean = other is InferenceNode && other.element == element
}

/**
 * An [InferenceCallTargetNode] is a wrapper around an [IrCall] which represents the target of
 * the call, not the call itself. That its type is the type of the target of the call not the
 * result of the call.
 *
 * [InferenceCallTargetNode]는 호출 자체가 아니라 호출의 대상을 나타내는 [IrCall]을 감싸는 래퍼입니다.
 * 여기서 다루는 타입은 호출의 결과가 아니라 호출 대상의 타입입니다.
 */
class InferenceCallTargetNode(
  override val element: IrCall,
  private val transformer: ComposableTargetAnnotationsTransformer,
) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.Function

  override val function: InferenceFunction =
    with(transformer) {
      if (element.symbol.owner.hasComposableTargetSpecified())
        InferenceFunctionDeclaration(function = element.symbol.owner, transformer = transformer)
      else
        InferenceFunctionCallType(call = element, transformer = transformer)
    }

  override val referenceContainer: InferenceNode? =
  // If this is a generic function then don't redirect the scheme to the declaration.
    // 제네릭 함수인 경우 스키마를 선언으로 리디렉션하지 마세요.
    if (element.symbol.isGenericFunction) null else
      with(transformer) {
        val function: IrSimpleFunction = when {
          // If this was a lambda transformed into a singleton, find the singleton function.
          // 이것이 싱글톤으로 변환된 람다라면 싱글톤 함수를 찾습니다.
          element.isComposableSingletonGetter() -> element.singletonFunctionExpression().function

          // If this is a normal lambda, find the lambda's IrFunction.
          // 일반 람다인 경우 람다의 IrFunction을 찾습니다.
          element.hasTransformedLambda() -> element.transformedLambda().function

          else -> element.symbol.owner
        }

        // If this is a call to a non-generic function with a body (e.g. non-abstract), return its
        // function. Generic or abstract functions (interface members, lambdas, open methods, etc.)
        // do not contain a body to infer anything from so we just use the declared scheme if
        // there is one. Returning null from this function cause the scheme to be determined from
        // the target expression (using, for example, the substituted type parameters) instead of
        // the definition.
        //
        // body가 있는 non-generic 함수(예: 추상적이지 않은 함수)에 대한 호출인 경우 해당 함수를 반환합니다.
        // generic 또는 abstract 함수(interface member, lambda, open function 등)는 추론할 본문을 포함하지
        // 않으므로 선언된 스키마가 있는 경우 그냥 사용합니다. 이 함수에서 null을 반환하면 정의 대신 대상
        // 표현식(예: substituted type parameter 사용)에서 스키마가 결정됩니다.
        function
          .takeIf { it.body != null && it.typeParameters.isEmpty() }
          ?.let { inferenceNodeOf(element = function, transformer = transformer) }
      }

  override fun hashCode(): Int = super.hashCode() * 31
  override fun equals(other: Any?): Boolean = other is InferenceCallTargetNode && super.equals(other)
}

/**
 * A node representing a variable declaration.
 */
// infer: 추론하다
// inference: 추론(한 것)
class InferenceVariable(
  override val element: IrVariable,
  private val transformer: ComposableTargetAnnotationsTransformer,
) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.Variable

  override val function: InferenceFunction
    get() = transformer.inferenceFunctionTypeOf(type = element.type)

  override val referenceContainer: InferenceNode? get() = null
}

/**
 * A node wrapper for function declarations.
 */
class InferenceFunctionDeclarationNode(
  override val element: IrFunction,
  transformer: ComposableTargetAnnotationsTransformer,
) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.Function

  override val function: InferenceFunction = transformer.inferenceFunctionOf(function = element)

  override val referenceContainer: InferenceNode?
    get() = this.takeIf { element.body != null }
}

/**
 * A node wrapper for function expressions (i.e. lambdas).
 */
class InferenceFunctionExpressionNode(
  override val element: IrFunctionExpression,
  private val transformer: ComposableTargetAnnotationsTransformer,
) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.Lambda

  override val function: InferenceFunction
    get() = transformer.inferenceFunctionOf(element.function)

  override val referenceContainer: InferenceNode
    get() = inferenceNodeOf(element = element.function, transformer = transformer)
}

/**
 * A node wrapper for a call. This represents the result of a call. Use [InferenceCallTargetNode]
 * to represent the target of a call.
 *
 * 호출에 대한 노드 래퍼입니다. 호출의 결과를 나타냅니다. 호출의 대상을 나타내려면
 * [InferenceCallTargetNode]를 사용합니다.
 */
class InferenceCallExpression(
  override val element: IrCall,
  private val transformer: ComposableTargetAnnotationsTransformer,
) : InferenceNode() {
  override val kind: NodeKind
    get() = if (isSingletonLambda || isTransformedLambda) NodeKind.Lambda else NodeKind.Expression

  private val isSingletonLambda: Boolean = with(transformer) { element.isComposableSingletonGetter() }
  private val isTransformedLambda: Boolean = with(transformer) { element.hasTransformedLambda() }

  override val function: InferenceFunction =
    with(transformer) {
      when {
        isSingletonLambda -> inferenceFunctionOf(function = element.singletonFunctionExpression().function)
        isTransformedLambda -> inferenceFunctionOf(function = element.transformedLambda().function)
        else -> inferenceFunctionTypeOf(type = element.type)
      }
    }

  override val referenceContainer: InferenceNode?
    get() = with(transformer) {
      when {
        isSingletonLambda -> inferenceNodeOf(
          element = element.singletonFunctionExpression().function,
          transformer = transformer,
        )

        isTransformedLambda -> inferenceNodeOf(
          element = element.transformedLambda().function,
          transformer = transformer,
        )

        else -> null
      }
    }
}

/**
 * An expression node whose scheme is determined by the type of the node.
 *
 * 노드의 유형에 따라 scheme이 결정되는 expression 노드입니다.
 */
class InferenceElementExpression(
  override val element: IrExpression,
  transformer: ComposableTargetAnnotationsTransformer,
) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.Expression
  override val function: InferenceFunction = transformer.inferenceFunctionTypeOf(type = element.type)
}

/**
 * An [InferenceUnknownElement] is a general wrapper around function declarations and lambda.
 */
class InferenceUnknownElement(override val element: IrElement) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.Expression
  override val function: InferenceFunction? get() = null
  override val referenceContainer: InferenceNode? get() = null
}

/**
 * An [InferenceResolvedParameter] is a node that references a parameter of a container of this
 * node. For example, if the parameter is captured by a nested lambda this is resolved to the
 * captured parameter.
 *
 * [InferenceResolvedParameter]는 이 노드의 컨테이너 파라미터를 참조하는 노드입니다.
 * 예를 들어 매개변수가 중첩된 람다에 의해 캡처된 경우 캡처된 매개변수로 해석됩니다.
 */
class InferenceResolvedParameter(
  override val element: IrGetValue,
  override val function: InferenceFunction,
  val container: InferenceNode,
  val index: Int,
) : InferenceNode() {
  override val kind: NodeKind get() = NodeKind.ParameterReference
  override val referenceContainer: InferenceNode get() = container

  override fun parameterIndex(node: InferenceNode): Int =
    if (node.function == function) index else -1

  override fun hashCode(): Int = element.hashCode() * 31 + 103
  override fun equals(other: Any?): Boolean = other is InferenceResolvedParameter && other.element == element
}

private fun inferenceNodeOf(
  element: IrElement,
  transformer: ComposableTargetAnnotationsTransformer,
): InferenceNode =
  when (element) {
    is IrFunction -> InferenceFunctionDeclarationNode(element = element, transformer = transformer)
    is IrFunctionExpression -> InferenceFunctionExpressionNode(element = element, transformer = transformer)
    is IrTypeOperatorCall -> inferenceNodeOf(element = element.argument, transformer = transformer)
    is IrCall -> InferenceCallExpression(element = element, transformer = transformer)
    is IrExpression -> InferenceElementExpression(element = element, transformer = transformer)
    else -> InferenceUnknownElement(element = element)
  }

private val IrConstructorCall.isComposableTarget: Boolean
  get() = annotationClass?.isClassWithFqName(ComposeFqNames.ComposableTarget.toUnsafe()) == true

private val IrConstructorCall.isComposableTargetMarker: Boolean
  get() = annotationClass?.owner?.annotations?.hasAnnotation(ComposeFqNames.ComposableTargetMarker) == true

private val IrConstructorCall.isComposableInferredTarget: Boolean
  get() = annotationClass?.isClassWithFqName(ComposeFqNames.ComposableInferredTarget.toUnsafe()) == true

private val IrConstructorCall.isComposableOpenTarget: Boolean
  get() = annotationClass?.isClassWithFqName(ComposeFqNames.ComposableOpenTarget.toUnsafe()) == true

private inline fun <reified T> IrConstructorCall.firstConstParameterOrNull(): T? =
  if (valueArgumentsCount >= 1) (getValueArgument(0) as? IrConst)?.value as? T else null

private val IrCall.valueArguments: List<IrExpression?>
  get() = List(valueArgumentsCount) { getValueArgument(it) }

private val IrSimpleFunctionSymbol.isGenericFunction: Boolean
  get() =
    owner.typeParameters.isNotEmpty() ||
      owner.dispatchReceiverParameter
        ?.type
        ?.let { it is IrSimpleType && it.arguments.isNotEmpty() } == true

private fun IrType.samOwnerOrNull(): IrSimpleFunction? =
  classOrNull?.let { cls ->
    if (cls.owner.kind == ClassKind.INTERFACE)
      cls.functions.singleOrNull { it.owner.modality == Modality.ABSTRACT }?.owner
    else
      null
  }

private fun <T> Iterable<T>.takeOrEmpty(n: Int): List<T> =
  if (n <= 0) emptyList() else take(n)

/**
 * A function with overly wide parameters should be ignored for traversal as well as when
 * it is called.
 *
 * 지나치게 넓은 매개변수가 있는 함수는 호출될 때뿐만 아니라 추론 순회에서도 무시해야 합니다.
 */
private fun IrFunction.hasOverlyWideParameters(): Boolean =
  valueParameters.any { it.type.isAny() || it.type.isNullableAny() }

private fun IrFunction.hasTypeParameter(): Boolean =
  valueParameters.any { it.type.isTypeParameter() } ||
    dispatchReceiverParameter?.type?.isTypeParameter() == true ||
    extensionReceiverParameter?.type?.isTypeParameter() == true
