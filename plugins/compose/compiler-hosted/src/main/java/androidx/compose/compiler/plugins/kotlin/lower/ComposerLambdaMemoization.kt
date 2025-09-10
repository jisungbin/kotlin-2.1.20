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
import androidx.compose.compiler.plugins.kotlin.FeatureFlag
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.ComposeWritableSlices
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.analysis.knownStable
import androidx.compose.compiler.plugins.kotlin.irTrace
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.jvm.ir.isInPublicInlineScope
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrValueAccessExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.isSuspendFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.platform.isWasm
import org.jetbrains.kotlin.platform.jvm.isJvm

private class CaptureCollector {
  val capturedValues = mutableSetOf<IrValueDeclaration>()
  val capturedDeclarations = mutableSetOf<IrSymbolOwner>()

  val hasCaptures: Boolean
    get() = capturedValues.isNotEmpty() || capturedDeclarations.isNotEmpty()

  /** @param value 자기 자신 declaration에 포함된 local variable */
  fun recordCapturedValue(value: IrValueDeclaration) {
    capturedValues.add(value)
  }

  fun recordCapturedDeclaration(declaration: IrSymbolOwner) {
    capturedDeclarations.add(declaration)
  }
}

private abstract class DeclarationContext {
  val localDeclarationCapturedValues = mutableMapOf<IrSymbolOwner, Set<IrValueDeclaration>>()

  /** [declaration] 외부에 정의된 local variables */
  abstract val externalCapturedValues: Set<IrValueDeclaration>

  abstract val composable: Boolean
  abstract val symbol: IrSymbol
  abstract val declaration: IrSymbolOwner
  abstract val functionContext: FunctionContext?

  /** @param value [declaration] 자체에 정의된 local variable */
  abstract fun declareOwnLocalValue(value: IrValueDeclaration?)

  /** @return [value]가 [declaration] 자체에 정의된 건지 여부 */
  abstract fun recordCapturedValue(value: IrValueDeclaration?): Boolean
  abstract fun recordCapturedDeclaration(declaration: IrSymbolOwner?)

  abstract fun pushCollector(collector: CaptureCollector)
  abstract fun popCollector(collector: CaptureCollector)

  fun recordLocalDeclarationCapturesFromLocalContext(localContext: DeclarationContext) {
    localDeclarationCapturedValues[localContext.declaration] = localContext.externalCapturedValues
  }
}

private fun List<DeclarationContext>.recordCapturedValue(value: IrValueDeclaration) {
  for (declarationContext in reversed()) {
    val shouldBreak = declarationContext.recordCapturedValue(value = value)
    if (shouldBreak) break
  }
}

private fun List<DeclarationContext>.recordLocalDeclarationCapturesFromLocalContext(localContext: DeclarationContext) {
  for (declarationContext in reversed()) {
    declarationContext.recordLocalDeclarationCapturesFromLocalContext(localContext = localContext)
  }
}

/** @return [declaration] 안에서 캡처된 values */
private fun List<DeclarationContext>.recordLocalCapturedDeclaration(declaration: IrSymbolOwner): Set<IrValueDeclaration>? {
  val localCaptures = reversed().firstNotNullOfOrNull { it.localDeclarationCapturedValues[declaration] }
  if (localCaptures != null) {
    localCaptures.forEach { capture -> recordCapturedValue(value = capture) }

    for (declarationContext in reversed()) {
      declarationContext.recordCapturedDeclaration(declaration = declaration)

      if (declarationContext.localDeclarationCapturedValues.containsKey(declaration)) {
        // this is the scope that the class was defined in, so above this we don't need
        // to do anything.
        //
        // 이 클래스가 정의된 범위이므로 이 위에서는 아무것도 할 필요가 없습니다.
        break
      }
    }
  }

  return localCaptures
}

private class DeclarationOwnerContext(override val declaration: IrSymbolOwner) : DeclarationContext() {
  override val externalCapturedValues: Set<IrValueDeclaration> get() = emptySet()

  override val composable: Boolean get() = false
  override val symbol: IrSymbol get() = declaration.symbol
  override val functionContext: FunctionContext? get() = null

  override fun declareOwnLocalValue(value: IrValueDeclaration?) {}

  override fun recordCapturedValue(value: IrValueDeclaration?): Boolean = false
  override fun recordCapturedDeclaration(declaration: IrSymbolOwner?) {}

  override fun pushCollector(collector: CaptureCollector) {}
  override fun popCollector(collector: CaptureCollector) {}
}

private class FunctionLocalContext(
  override val declaration: IrSymbolOwner,
  override val functionContext: FunctionContext,
) : DeclarationContext() {
  override val externalCapturedValues: Set<IrValueDeclaration>
    get() = functionContext.externalCapturedValues

  override val composable: Boolean get() = functionContext.composable
  override val symbol: IrSymbol get() = declaration.symbol

  override fun declareOwnLocalValue(value: IrValueDeclaration?) {
    functionContext.declareOwnLocalValue(value = value)
  }

  override fun recordCapturedValue(value: IrValueDeclaration?): Boolean =
    functionContext.recordCapturedValue(value = value)

  override fun recordCapturedDeclaration(declaration: IrSymbolOwner?) {
    functionContext.recordCapturedDeclaration(declaration = declaration)
  }

  override fun pushCollector(collector: CaptureCollector) {
    functionContext.pushCollector(collector = collector)
  }

  override fun popCollector(collector: CaptureCollector) {
    functionContext.popCollector(collector = collector)
  }
}

private class FunctionContext(
  override val declaration: IrFunction,
  override val composable: Boolean,
) : DeclarationContext() {
  override val externalCapturedValues = mutableSetOf<IrValueDeclaration>()

  override val symbol: IrFunctionSymbol get() = declaration.symbol
  override val functionContext: FunctionContext get() = this

  val collectors = mutableListOf<CaptureCollector>()

  /** [declaration] 자체에 정의된 local variables */
  val ownLocalValues = mutableSetOf<IrValueDeclaration>()

  init {
    declaration.valueParameters.forEach { declareOwnLocalValue(value = it) }
    declaration.dispatchReceiverParameter?.let { declareOwnLocalValue(value = it) }
    declaration.extensionReceiverParameter?.let { declareOwnLocalValue(value = it) }
  }

  override fun declareOwnLocalValue(value: IrValueDeclaration?) {
    if (value != null) {
      ownLocalValues.add(value)
    }
  }

  override fun recordCapturedValue(value: IrValueDeclaration?): Boolean {
    val containsOwnLocal = value in ownLocalValues

    if (value != null && collectors.isNotEmpty() && containsOwnLocal) {
      for (collector in collectors) {
        collector.recordCapturedValue(value = value)
      }
    }

    if (value != null && declaration.isLocal && !containsOwnLocal) {
      externalCapturedValues.add(element = value)
    }

    return containsOwnLocal
  }

  override fun recordCapturedDeclaration(declaration: IrSymbolOwner?) {
    if (declaration != null) {
      val capturedValues = localDeclarationCapturedValues[declaration]

      for (collector in collectors) {
        collector.recordCapturedDeclaration(declaration = declaration)

        if (capturedValues != null) {
          for (capture in capturedValues) {
            collector.recordCapturedValue(value = capture)
          }
        }
      }
    }
  }

  override fun pushCollector(collector: CaptureCollector) {
    collectors.add(collector)
  }

  override fun popCollector(collector: CaptureCollector) {
    require(collectors.lastOrNull() == collector)
    collectors.removeLast()
  }
}

private class ClassContext(override val declaration: IrClass) : DeclarationContext() {
  override val externalCapturedValues = mutableSetOf<IrValueDeclaration>()

  override val composable: Boolean get() = false
  override val symbol: IrClassSymbol get() = declaration.symbol
  override val functionContext: FunctionContext? = null

  val thisParam: IrValueDeclaration get() = declaration.thisReceiver!!
  val collectors = mutableListOf<CaptureCollector>()

  override fun declareOwnLocalValue(value: IrValueDeclaration?) {}

  override fun recordCapturedValue(value: IrValueDeclaration?): Boolean {
    val isThis = value == thisParam
    val isConstructorParam = (value?.parent as? IrConstructor)?.parent === declaration
    val isClassParam = isThis || isConstructorParam

    if (value != null && collectors.isNotEmpty() && isClassParam) {
      for (collector in collectors) {
        collector.recordCapturedValue(value = value)
      }
    }

    if (value != null && declaration.isLocal && !isClassParam) {
      externalCapturedValues.add(value)
    }

    return isClassParam
  }

  override fun recordCapturedDeclaration(declaration: IrSymbolOwner?) {}

  override fun pushCollector(collector: CaptureCollector) {
    collectors.add(collector)
  }

  override fun popCollector(collector: CaptureCollector) {
    require(collectors.lastOrNull() == collector)
    collectors.removeLast()
  }
}

class ComposerLambdaMemoization(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
) :
  AbstractComposeLowering(
    context = context,
    metrics = metrics,
    stabilityInferencer = stabilityInferencer,
    featureFlags = featureFlags
  ),
  ModuleLoweringPass {

  private val declarationContextStack = mutableListOf<DeclarationContext>()

  private val currentFunctionContext: FunctionContext?
    get() = declarationContextStack.peek()?.functionContext

  private var currentFile: IrFile? = null

  private var composableSingletonsClass: IrClass? = null
  private val usedSingletonLambdaNames = hashSetOf<String>()

  private var inlineLambdaInfo = ComposeInlineLambdaLocator(context = context)

  // 같은 remember 함수로 key 개수별로 변형이 존재함
  private val rememberFunctions: List<IrSimpleFunction> =
    getTopLevelFunctions(ComposeCallableIds.remember).map { it.owner }

  // fun composableLambda(
  //   composer: Composer,
  //   key: Int,
  //   tracked: Boolean,
  //   block: Any,
  // ): ComposableLambda
  private val composableLambdaFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambda)
  }

  // fun composableLambdaN(
  //    composer: Composer,
  //    key: Int,
  //    tracked: Boolean,
  //    arity: Int,
  //    block: Any,
  // ): ComposableLambdaN
  private val composableLambdaNFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambdaN)
  }

  // fun composableLambdaInstance(
  //   key: Int,
  //   tracked: Boolean,
  //   block: Any,
  // ): ComposableLambda
  private val composableLambdaInstanceFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambdaInstance)
  }

  // fun composableLambdaNInstance(
  //   key: Int,
  //   tracked: Boolean,
  //   arity: Int,
  //   block: Any,
  // ): ComposableLambdaN
  private val composableLambdaInstanceNFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambdaNInstance)
  }

  // @Composable fun rememberComposableLambda(
  //   key: Int,
  //   tracked: Boolean,
  //   block: Any,
  // ): ComposableLambda
  private val rememberComposableLambdaFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.rememberComposableLambda).singleOrNull()
  }

  // @Composable fun rememberComposableLambdaN(
  //   key: Int,
  //   tracked: Boolean,
  //   arity: Int,
  //   block: Any,
  // ): ComposableLambdaN
  private val rememberComposableLambdaNFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.rememberComposableLambdaN).singleOrNull()
  }

  private val currentComposerSymbol: IrFunctionSymbol =
    getTopLevelPropertyGetter(ComposeCallableIds.currentComposer)

  private val useNonSkippingGroupOptimization by guardedLazy {
    // Uses `rememberComposableLambda` as a indication that the runtime supports
    // generating remember after call as it was added at the same time as the slot table was
    // modified to support remember after call.
    //
    // 슬롯 테이블이 호출 후 remember를 지원하도록 수정됨과 동시에 `rememberComposableLambda`가
    // 추가되었으므로, 런타임이 호출 후 remember 생성을 지원한다는 표시로 `rememberComposableLambda`를
    // 사용합니다.
    FeatureFlag.OptimizeNonSkippingGroups.enabled && rememberComposableLambdaFunction != null
  }

  override fun lower(irModule: IrModuleFragment) {
    inlineLambdaInfo.scan(element = irModule)
    irModule.transformChildrenVoid(this)
  }

  override fun visitFile(declaration: IrFile): IrFile =
    includeFileNameInExceptionTrace(file = declaration) {
      val prevFile = currentFile
      val prevSingletonsClass = composableSingletonsClass

      try {
        currentFile = declaration
        composableSingletonsClass = null
        usedSingletonLambdaNames.clear()

        val file = super.visitFile(declaration)

        val resultingClass = composableSingletonsClass
        if (resultingClass != null && resultingClass.declarations.isNotEmpty()) {
          file.addChild(declaration = resultingClass)
        }

        file
      } finally {
        currentFile = prevFile
        composableSingletonsClass = prevSingletonsClass
      }
    }

  override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement {
    if (declaration is IrFunction)
      return super.visitDeclaration(declaration)

    val functionContext = currentFunctionContext
    if (functionContext != null) {
      declarationContextStack.push(
        FunctionLocalContext(
          declaration = declaration,
          functionContext = functionContext,
        ),
      )
    } else {
      declarationContextStack.push(DeclarationOwnerContext(declaration = declaration))
    }

    val result = super.visitDeclaration(declaration)
    declarationContextStack.pop()

    return result
  }

  override fun visitFunction(declaration: IrFunction): IrStatement {
    val composable = declaration.allowsComposableCalls
    val context = FunctionContext(declaration = declaration, composable = composable)

    if (declaration.isLocal) {
      declarationContextStack.recordLocalDeclarationCapturesFromLocalContext(localContext = context)
    }

    declarationContextStack.push(context)
    val result = super.visitFunction(declaration)
    declarationContextStack.pop()

    return result
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    val context = ClassContext(declaration = declaration)

    if (declaration.isLocal) {
      declarationContextStack.recordLocalDeclarationCapturesFromLocalContext(localContext = context)
    }

    declarationContextStack.push(context)
    val result = super.visitClass(declaration)
    declarationContextStack.pop()

    return result
  }

  override fun visitVariable(declaration: IrVariable): IrStatement {
    declarationContextStack.peek()?.declareOwnLocalValue(value = declaration)
    return super.visitVariable(declaration)
  }

  override fun visitValueAccess(expression: IrValueAccessExpression): IrExpression {
    declarationContextStack.recordCapturedValue(value = expression.symbol.owner)
    return super.visitValueAccess(expression)
  }

  override fun visitBlock(expression: IrBlock): IrExpression {
    val result = super.visitBlock(expression)

    if (
      result is IrBlock &&

      // ADAPTED_FUNCTION_REFERENCE:
      //
      //   block(::println)
      //         ^^^^^^^^^ <- 이처럼 함수 레퍼런스로 IrBlock이 채워진 경우
      result.origin == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE
    ) {
      if (inlineLambdaInfo.isInlineFunctionExpression(expression = expression)) {
        // Do not memoize function references for inline lambdas.
        // 인라인되는 람다의 함수 참조는 memoize하지 않습니다.
        return result
      }

      val functionReference = result.statements.last()
      if (functionReference !is IrFunctionReference) {
        // Do not memoize if the expected shape doesn't match.
        // 예상된 형태가 일치하지 않으면 memoize하지 않습니다.
        return result
      }

      return rememberFunctionReference(reference = functionReference, expression = expression)
    }

    return result
  }

  // Memoize the instance created by using the :: operator.
  // :: 연산자로 생성된 인스턴스를 memoize합니다.
  override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
    val result = super.visitFunctionReference(expression)

    if (
      inlineLambdaInfo.isInlineFunctionExpression(expression = expression) ||
      inlineLambdaInfo.isInlineLambda(lambda = expression.symbol.owner)
    ) {
      // Do not memoize function references used in inline parameters.
      // 인라인 매개변수에 사용된 함수 참조는 memoize하지 않습니다.
      return result
    }

    if (expression.symbol.owner.origin == IrDeclarationOrigin.ADAPTER_FOR_CALLABLE_REFERENCE) {
      // Adapted function reference (inexact function signature match) is handled in block.
      // 채택된 함수 참조(정확히 일치하지 않는 함수 시그니처)는 block에서 처리됩니다.
      //
      //
      // ADAPTER_FOR_CALLABLE_REFERENCE:
      //
      //   class A(a: Int, b: Int = 2)
      //
      //   fun main() {
      //     1.let(::A)
      //           ^^^ <- A는 두 개의 매개변수를 갖는데, 두 번째 b 매개변수는 기본값을 가짐.
      //                  즉, 값 하나만 제공하여도 A 인스턴스를 만들 수 있고, 이런 경우가
      //                  ADAPTER_FOR_CALLABLE_REFERENCE origin임.
      //   }
      return result
    }

    if (result !is IrFunctionReference) {
      // Do not memoize if the shape doesn't match.
      // 형태가 일치하지 않으면 memoize하지 않습니다.
      return result
    }

    return rememberFunctionReference(reference = result, expression = result)
  }

  override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
    val declarationContext = declarationContextStack.peek() ?: return super.visitFunctionExpression(expression)

    return if (expression.function.allowsComposableCalls) {
      visitComposableFunctionExpression(
        expression = expression,
        declarationContext = declarationContext,
      )
    } else {
      visitNonComposableFunctionExpression(expression = expression)
    }
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    // SAM conversions are handled by Kotlin compiler. We only need to make sure that
    // `remember` is handled correctly around type operator.
    //
    // SAM 변환은 Kotlin 컴파일러가 처리합니다. 우리는 remember가 타입 연산자 주변에서
    // 올바르게 처리되는지만 확인하면 됩니다.
    if (
      expression.operator != IrTypeOperator.SAM_CONVERSION ||
      currentFunctionContext?.composable != true
    ) {
      return super.visitTypeOperator(expression)
    }
    // SAM 연산과 컴포저블 스코프 안에서만 동작함

    // Unwrap function from type operator.
    // 타입 연산자에서 함수를 unwrap 합니다.
    val originalFunctionExpression =
      expression.findSamFunctionExpr() ?: return super.visitTypeOperator(expression)

    // Record capture variables for this scope.
    // 이 스코프의 캡처 변수를 기록합니다.
    val collector = CaptureCollector()

    startCollector(collector = collector)

    // Handle inside of the function expression.
    // 함수 표현식 내부를 처리합니다.
    val result = super.visitFunctionExpression(originalFunctionExpression)

    stopCollector(collector = collector)

    // If the ancestor converted this then return.
    // 조상이 이것을 변환했다면, 그대로 반환합니다.
    val transformedExpr = result as? IrFunctionExpression ?: return result

    // Construct new type operator call to wrap remember around.
    // remember로 감싸기 위해 새로운 타입 연산자 호출을 생성합니다.
    val newArgument =
      when (val argument = expression.argument) {
        is IrFunctionExpression -> transformedExpr

        is IrTypeOperatorCall -> {
          require(
            argument.operator == IrTypeOperator.IMPLICIT_CAST &&
              argument.argument == originalFunctionExpression
          ) {
            // SAM 변환 내부에서는 암시적 캐스트만 지원합니다.
            "Only implicit cast is supported inside SAM conversion"
          }

          IrTypeOperatorCallImpl(
            startOffset = argument.startOffset,
            endOffset = argument.endOffset,
            type = argument.type,
            operator = argument.operator,
            typeOperand = argument.typeOperand,
            argument = transformedExpr,
          )
        }

        else -> error("Unknown argument type: ${argument::class}")
      }

    val expressionToRemember =
      IrTypeOperatorCallImpl(
        startOffset = expression.startOffset,
        endOffset = expression.endOffset,
        type = expression.type,
        operator = IrTypeOperator.SAM_CONVERSION,
        typeOperand = expression.typeOperand,
        argument = newArgument,
      )

    return rememberExpression(
      functionContext = currentFunctionContext!!,
      expression = expressionToRemember,
      capturedValues = collector.capturedValues.toList(),
    )
  }

  override fun visitCall(expression: IrCall): IrExpression {
    val fn = expression.symbol.owner

    if (fn.visibility == DescriptorVisibilities.LOCAL) {
      declarationContextStack.recordLocalCapturedDeclaration(declaration = fn)
    }

    return super.visitCall(expression)
  }

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val fn = expression.symbol.owner
    val cls = fn.parent as? IrClass

    if (cls != null && fn.isLocal) {
      declarationContextStack.recordLocalCapturedDeclaration(declaration = cls)
    }

    return super.visitConstructorCall(expression)
  }

  private fun visitComposableFunctionExpression(
    expression: IrFunctionExpression,
    declarationContext: DeclarationContext,
  ): IrExpression {
    val collector = CaptureCollector()

    startCollector(collector = collector)
    val result = super.visitFunctionExpression(expression)
    stopCollector(collector = collector)

    // If the ancestor converted this then return.
    // 조상이 이것을 변환했다면, 그대로 반환합니다.
    val functionExpression = result as? IrFunctionExpression ?: return result

    // Do not wrap target of an inline function.
    // 인라인 함수의 대상은 래핑하지 않습니다.
    if (inlineLambdaInfo.isInlineLambda(lambda = expression.function)) {
      return functionExpression
    }

    // Do not wrap composable lambdas with return results.
    // 결과를 반환하는 컴포저블 람다는 래핑하지 않습니다.
    if (!functionExpression.function.returnType.isUnit()) {
      metrics.recordLambda(
        composable = true,
        memoized = !collector.hasCaptures,
        singleton = !collector.hasCaptures,
      )
      return functionExpression
    }

    metrics.recordLambda(
      composable = true,
      memoized = true,
      singleton = !collector.hasCaptures,
    )

    return if (!collector.hasCaptures) {
      // 람다가 캡처하는 값이 없다면

      val enclosingFunction = declarationContext.functionContext?.declaration

      // 날 담는 부모들(재귀)이 모두 public이라면 true
      val inPublicInlineScope = enclosingFunction?.isInPublicInlineScope == true

      val singletonLambda = irGetComposableSingletonLambda(
        lambdaExpression = wrapFunctionExpressionWithComposableLambda(
          declarationContext = declarationContext,
          expression = functionExpression,
          collector = collector,
          useRememberingFactory = false, // instance factory 사용
        ),
        lambdaType = expression.type,
        lambdaName = createSingletonLambdaName(expression = functionExpression),
      )

      if (inPublicInlineScope) {
        // Public inline functions can't use singleton instance because changes to the function body
        // can cause ABI incompatibilities. Note that we still generate singleton instances
        // to ensure that we don't break existing consumers.
        //
        // public 인라인 함수는 함수 본문을 변경하면 ABI 호환이 깨질 수 있으므로 싱글톤 인스턴스를
        // 사용할 수 없습니다. 기존 소비자를 손상시키지 않도록 싱글톤 인스턴스는 여전히 생성합니다.
        wrapFunctionExpressionWithComposableLambda(
          declarationContext = declarationContext,
          expression = functionExpression,
          collector = collector,
          useRememberingFactory = declarationContext.composable,
        )
          .also {
            it.associatedComposableSingletonStub = singletonLambda
          }
      }

      // inPublicInlineScope == false
      else {
        singletonLambda
      }
    }

    // collector.hasCaptures == true
    else {
      wrapFunctionExpressionWithComposableLambda(
        declarationContext = declarationContext,
        expression = functionExpression,
        collector = collector,
        useRememberingFactory = declarationContext.composable,
      )
    }
  }

  private fun visitNonComposableFunctionExpression(expression: IrFunctionExpression): IrExpression {
    val functionContext = currentFunctionContext ?: return super.visitFunctionExpression(expression)

    if (
    // Only memoize non-composable lambdas in a context we can use `remember`.
    // remember할 수 있는 위치에서만 컴포저블이 아닌 람다를 memoize하세요.
      !functionContext.composable ||
      // Don't memoize inlined lambdas.
      // 인라인된 람다는 memoize하지 않습니다.
      inlineLambdaInfo.isInlineLambda(lambda = expression.function)
    ) {
      return super.visitFunctionExpression(expression)
    }

    // Record capture variables for this scope.
    // 이 스코프의 캡처 변수를 기록합니다.
    val collector = CaptureCollector()

    startCollector(collector = collector)

    // Wrap composable functions expressions or memoize non-composable function expressions
    // 컴포저블 함수 표현식을 래핑하거나 컴포저블이 아닌 함수 표현식을 memoize하세요.
    val result = super.visitFunctionExpression(expression)

    stopCollector(collector = collector)

    // If the ancestor converted this then return.
    // 조상이 이것을 변환했다면, 그대로 반환합니다.
    val functionExpression = result as? IrFunctionExpression ?: return result

    return rememberExpression(
      functionContext = functionContext,
      expression = functionExpression,
      capturedValues = collector.capturedValues.toList(),
    )
  }

  // MEMO composableLambda 함수로 람다를 감싸는 로직
  private fun wrapFunctionExpressionWithComposableLambda(
    declarationContext: DeclarationContext,
    expression: IrFunctionExpression,
    collector: CaptureCollector,
    useRememberingFactory: Boolean, // false라면 instance factory를 사용함
  ): IrCall {
    val function = expression.function
    val valueParameterCount = function.valueParameters.size

    val useComposableLambdaN = valueParameterCount > MAX_RESTART_ARGUMENT_COUNT
    val requiresComposerParameter = useRememberingFactory && rememberComposableLambdaFunction == null

    val composableLambdaSymbol =
      when {
        useRememberingFactory -> when {
          useComposableLambdaN -> rememberComposableLambdaNFunction ?: composableLambdaNFunction
          else -> rememberComposableLambdaFunction ?: composableLambdaFunction
        }

        // useRememberingFactory가 false라면 컴포저블 스코프가 아닌 곳에서 호출되었음을 의미함.
        // 컴포저블이 아니라면 함수가 스스로 재시작될 가능성은 없으므로(리컴포지션에 영향 받지 않음),
        // ComposableLambda 인스턴스를 remember하지 않아도 됨.

        useComposableLambdaN -> composableLambdaInstanceNFunction

        else -> composableLambdaInstanceFunction
      }

    val irBuilder = DeclarationIrBuilder(
      generatorContext = context,
      symbol = declarationContext.symbol,
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
    )

    val composableLambdaExpression = irBuilder.irCall(callee = composableLambdaSymbol).apply {
      var index = 0

      // first parameter is the composer parameter if we are using the composable(remembering) factory.
      // composable(remembering) 팩토리를 사용하는 경우 첫 번째 매개변수는 composer 매개변수입니다.
      if (requiresComposerParameter) {
        putValueArgument(index++, irCurrentComposer())
      }

      // key parameter.
      putValueArgument(index++, irBuilder.irInt(expression.function.sourceKey()))

      // tracked parameter.
      //
      // If the lambda has no captures, then Kotlinc will turn it into a singleton instance,
      // which means that it will never change, thus does not need to be tracked.
      //
      // 람다가 캡처를 가지지 않으면 Kotlinc가 이를 싱글턴 인스턴스로 변환합니다. 이는 절대
      // 변경되지 않음을 의미하므로 추적할 필요가 없습니다.
      //
      //
      // MEMO 람다 결과가 아니라 람다 자체를 메모이제이션하므로(이 시점에는 매개변수의 인자가 없음)
      //  capturedValues를 key로 넣지 않아도 됨
      val shouldBeTracked = collector.capturedValues.isNotEmpty()
      putValueArgument(index++, irBuilder.irBoolean(shouldBeTracked))

      // ComposableLambdaN requires the arity.
      // ComposableLambdaN은 arity가 필수입니다.
      if (useComposableLambdaN) {
        // arity parameter.
        putValueArgument(index++, irBuilder.irInt(valueParameterCount))
      }

      if (index >= valueArgumentsCount) {
        error("function=${function.name.asString()}, count=$valueArgumentsCount, index=$index")
      }

      // block parameter.
      putValueArgument(index, expression.markIsTransformedLambda())
    }

    return composableLambdaExpression.markHasTransformedLambda()
  }

  // MEMO 람다를 cache 또는 remember하는 로직. 캡처된 값들(capturedValues)이 key로 쓰임.
  private fun rememberExpression(
    functionContext: FunctionContext,
    expression: IrExpression,
    capturedValues: List<IrValueDeclaration>,
  ): IrExpression {
    val memoizeLambdasWithoutCaptures =
    // Kotlin/JS doesn't have an optimization for non-capturing lambdas.
    // Kotlin/JS는 캡처하지 않는 람다에 최적화를 지원하지 않습니다.
    //
      // https://youtrack.jetbrains.com/issue/KT-49923
      context.platform.isJs() ||
        context.platform.isWasm() ||
        (
          // K2 uses invokedynamic for lambdas, which doesn't perform lambda optimization
          // on Android.
          //
          // K2는 람다에 invokedynamic을 사용하며, Android에서는 람다 최적화를 수행하지
          // 않습니다. (=> 그러니 컴포즈 컴파일러에서 람다 최적화해야 함)
          context.platform.isJvm() &&
            context.languageVersionSettings.languageVersion.usesK2
          )

    // If the function doesn't capture, Kotlin's default optimization is sufficient.
    // 함수가 캡처하지 않는다면 Kotlin의 기본 최적화만으로 충분합니다.
    if (!memoizeLambdasWithoutCaptures && capturedValues.isEmpty()) {
      metrics.recordLambda(
        composable = false,
        memoized = true,
        singleton = true,
      )
      return expression.markAsStatic()
    }

    // MEMO 함수 메모이제이션이 가능한 규칙
    //
    // Don't memoize if the function is annotated with @DontMemoize or captures:
    //
    // - any var declarations,
    // - unstable values (without strong skipping),
    // - local delegates with property references,
    // - inlined lambdas.
    //
    //
    // 함수가 @DontMemoize로 어노테이션되었거나 다음을 캡처하는 경우 memoize하지 않습니다:
    //
    // - var 선언
    // - 안정적이지 않은 값(강력 건너뛰기가 비활성화된 경우)
    // - 프로퍼티를 레퍼런스하는 델리게이션
    //
    //      class A(val n: Int)
    //
    //      fun test(a: A) {
    //        val z: Int by a::n
    //            ^ 이처럼 프로퍼티를 레퍼런스하여 델리게이트하는 변수에 해당함
    //      }
    //
    // - 인라인되는 람다
    if (
      functionContext.declaration.hasAnnotation(ComposeFqNames.DontMemoize) ||
      expression.hasDontMemoizeAnnotation ||
      capturedValues.any { captured ->
        captured.isVar() ||
          (!captured.isStable() && !FeatureFlag.StrongSkipping.enabled) ||
          captured.isPropertyReferenceDelegate() ||
          captured.isInlinedLambda()
      }
    ) {
      metrics.recordLambda(
        composable = false,
        memoized = false,
        singleton = false,
      )
      return expression
    }

    val capturedExpressions = capturedValues.map { irGet(variable = it) }

    metrics.recordLambda(
      composable = false,
      memoized = true,
      singleton = false,
    )

    return (if (FeatureFlag.IntrinsicRemember.enabled) {
      irRemember(keys = capturedExpressions, expression = expression)
    } else {
      // generate cache directly only if strong skipping is enabled without intrinsic remember.
      // otherwise, generated memoization won't benefit from capturing changed values.
      //
      // intrinsic remember 없이 strong skipping가 활성화된 경우에는 캐시를 직접 생성합니다.
      // 그렇지 않으면 생성된 메모화는 변경된 값을 캡처하는 이점을 얻지 못합니다.
      irCache(keys = capturedExpressions, expression = expression)
    })
      .patchDeclarationParents(initialParent = functionContext.declaration)
  }

  private fun rememberFunctionReference(
    reference: IrFunctionReference,
    expression: IrExpression,
  ): IrExpression {
    // Get the local captures for local function ref, to make sure we invalidate memoized
    // reference if its capture is different.
    //
    // 로컬 함수가 레퍼런스될 때, 해당 함수가 캡처하는 값이 달라지면 memoize한 레퍼런스를
    // 무효화합니다.
    //
    //
    //   fun test() {
    //     fun a(a: Any) = Unit
    //
    //     1.also(::print)
    //            ^^^^^^^ <- public reference
    //
    //     1.also(::a)
    //            ^^^ <- local reference
    //   }
    val localCaptures: Set<IrValueDeclaration>? =
      if (reference.symbol.owner.visibility == DescriptorVisibilities.LOCAL) {
        declarationContextStack.recordLocalCapturedDeclaration(declaration = reference.symbol.owner)
      } else {
        null
      }

    val functionContext = currentFunctionContext ?: return expression

    // The syntax <expr>::<method>(<params>) and ::<function>(<params>) is reserved for
    // future use. Revisit implementation if this syntax is as a curry syntax in the future.
    // The most likely correct implementation is to treat the parameters exactly as the
    // receivers are treated below.
    //
    // 구문 `<expr>::<method>(<params>)` 및 `::<function>(<params>)`는 향후 사용을 위해
    // 예약되어 있습니다. 향후 이 구문이 curry syntax으로 사용될 경우 구현을 다시 검토하세요.
    // 가장 올바른 구현은 아래에서 수신자가 처리되는 것과 똑같이 매개변수를 처리하는 것입니다.

    // Do not attempt memoization if the referenced function has context receivers.
    // 참조된 함수에 컨텍스트 리시버가 있으면 메모이제이션을 시도하지 않습니다.
    if (reference.symbol.owner.contextReceiverParametersCount > 0) {
      return expression
    }

    // Do not attempt memoization if value arguments are not null. This is to guard against
    // unexpected IR shapes.
    //
    // 인자 값이 null이 아닌 경우 메모화를 시도하지 마십시오. 이는 예기치 않은 IR 모양을
    // 방지하기 위한 것입니다. (=> FunctionReference는 argument가 없어야 함)
    for (i in 0 until reference.valueArgumentsCount) {
      if (reference.getValueArgument(i) != null) {
        return expression
      }
    }

    if (functionContext.composable) {
      // Memoize the reference for <expr>::<method>.
      // `<expr>::<method>` 레퍼런스를 memoize합니다.

      val dispatchReceiver = reference.dispatchReceiver
      val extensionReceiver = reference.extensionReceiver

      val hasReceiver = dispatchReceiver != null || extensionReceiver != null
      val receiverIsStable = dispatchReceiver.isNullOrStable() && extensionReceiver.isNullOrStable()

      val captures = mutableListOf<IrValueDeclaration>()

      if (localCaptures != null) {
        captures.addAll(localCaptures)
      }

      if (hasReceiver && (FeatureFlag.StrongSkipping.enabled || receiverIsStable)) {
        // Save the receivers into a temporaries and memoize the function reference using
        // the resulting temporaries.
        //
        // receiver를 임시 변수에 저장하고, 해당 변수를 사용하여 함수 참조를 memoize합니다.
        return DeclarationIrBuilder(
          generatorContext = context,
          symbol = functionContext.symbol,
          startOffset = expression.startOffset,
          endOffset = expression.endOffset,
        )
          .irBlock(resultType = expression.type) {
            val tempDispatchReceiver = dispatchReceiver?.let { dispatch ->
              val tmp = irTemporary(value = dispatch)
              captures.add(tmp)
              tmp
            }
            val tempExtensionReceiver = extensionReceiver?.let { extension ->
              val tmp = irTemporary(value = extension)
              captures.add(tmp)
              tmp
            }

            // Patch reference receiver in place.
            // 참조 리시버를 제자리에서 패치합니다.
            reference.dispatchReceiver = tempDispatchReceiver?.let { irGet(variable = it) }
            reference.extensionReceiver = tempExtensionReceiver?.let { irGet(variable = it) }

            +rememberExpression(
              functionContext = functionContext,
              expression = expression,
              capturedValues = captures,
            )
          }
      }

      // hasReceiver == false ||
      //   (FeatureFlag.StrongSkipping.enabled == false && receiverIsStable == false)
      else if (!hasReceiver) {
        return rememberExpression(
          functionContext = functionContext,
          expression = expression,
          capturedValues = captures,
        )
      }
    }

    return expression
  }

  private fun irGetComposableSingletonLambda(
    lambdaExpression: IrExpression,
    lambdaType: IrType,
    lambdaName: String,
  ): IrCall {
    val clazz = getOrCreateComposableSingletonsEmptyClass()
    val lambdaProp =
      clazz.addProperty {
        name = Name.identifier(lambdaName)
        visibility = DescriptorVisibilities.INTERNAL
      }
        .also { lambdaProp ->
          lambdaProp.backingField = context.irFactory.buildField {
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            name = Name.identifier(lambdaName)
            type = lambdaType
            visibility = DescriptorVisibilities.PRIVATE
            isStatic = true
          }.also { backingField ->
            backingField.correspondingPropertySymbol = lambdaProp.symbol
            backingField.parent = clazz
            backingField.initializer = DeclarationIrBuilder(context, clazz.symbol)
              .irExprBody(value = lambdaExpression.markIsTransformedLambda())
          }

          val lambdaPropGetter = lambdaProp.addGetter {
            returnType = lambdaType
            visibility = DescriptorVisibilities.INTERNAL
            origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
          }.also { getter ->
            val thisParam = clazz.thisReceiver!!.copyTo(irFunction = getter)

            getter.parent = clazz
            getter.dispatchReceiverParameter = thisParam
            getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
              +irReturn(
                value = irGetField(
                  receiver = irGet(variable = thisParam),
                  field = lambdaProp.backingField!!,
                ),
              )
            }
          }

          // Add property for backwards compatibility:
          //
          //   Previous versions of the compose compiler leaked this ComposableSingletons class through
          //   inline functions. To keep compatibility, we're still generating a property with the old
          //   lambda naming schema.
          //
          //
          // 하위 호환성을 위한 프로퍼티 추가:
          //
          //   이전 버전의 컴포즈 컴파일러는 인라인 함수로 이 ComposableSingletons 클래스를 유출했습니다.
          //   호환성을 유지하기 위해 여전히 이전 람다 명명 스키마로 프로퍼티를 생성합니다.
          //
          //
          // 구버전 대응을 위한 로직이라 공부 스킵!
          if (currentFunctionContext?.declaration?.isInPublicInlineScope == true) {
            clazz.addProperty {
              name = Name.identifier("lambda-${usedSingletonLambdaNames.size}")
              visibility = DescriptorVisibilities.INTERNAL
            }.also { property ->
              property.addGetter {
                returnType = lambdaType
                visibility = DescriptorVisibilities.INTERNAL
              }.also { getter ->
                val thisParam = clazz.thisReceiver!!.copyTo(irFunction = getter)

                getter.parent = clazz
                getter.dispatchReceiverParameter = thisParam
                getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
                  +irReturn(value = irCall(callee = lambdaPropGetter))
                }
              }
            }
          }
        }

    return irCall(
      symbol = lambdaProp.getter!!.symbol,
      dispatchReceiver = IrGetObjectValueImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = clazz.defaultType,
        symbol = clazz.symbol,
      )
    )
      .markAsComposableSingleton()
  }

  private fun getOrCreateComposableSingletonsEmptyClass(): IrClass {
    if (composableSingletonsClass != null)
      return composableSingletonsClass!!

    val declaration = currentFile!!
    val filePath = declaration.fileEntry.name
    val fileName = filePath.split('/').last()

    val current =
      context.irFactory.buildClass {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        kind = ClassKind.OBJECT
        visibility = DescriptorVisibilities.INTERNAL

        val shortName = PackagePartClassUtils.getFilePartShortName(fileName = fileName)
        name = Name.identifier($$"ComposableSingletons$$$shortName")
      }
        .also { clazz ->
          clazz.createThisReceiverParameter()
          clazz.addConstructor { isPrimary = true }.also { constructor ->
            constructor.body = DeclarationIrBuilder(context, clazz.symbol).irBlockBody {
              +irDelegatingConstructorCall(callee = context.irBuiltIns.anyClass.owner.primaryConstructor!!)
              +IrInstanceInitializerCallImpl(
                startOffset = this.startOffset,
                endOffset = this.endOffset,
                classSymbol = clazz.symbol,
                type = context.irBuiltIns.unitType,
              )
            }
          }
        }
        .markAsComposableSingletonClass()

    composableSingletonsClass = current

    return current
  }

  private fun createSingletonLambdaName(expression: IrFunctionExpression): String {
    val name = "lambda$${expression.function.sourceKey()}"

    if (usedSingletonLambdaNames.add(name))
      return name

    var manglingNumber = 0

    while (true) {
      val mangledName = "$name$${manglingNumber++}"

      if (usedSingletonLambdaNames.add(mangledName))
        return mangledName
    }
  }

  private fun irCache(
    keys: List<IrExpression>,
    expression: IrExpression,
  ): IrExpression {
    val invalidExpr =
      keys
        .map(::irChanged)
        .reduceOrNull { acc, changed -> irBooleanOr(lhs = acc, rhs = changed) }
        ?: irBooleanConst(false)

    val calculation = irLambdaExpression(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      returnType = expression.type,
    ) { fn ->
      fn.body = DeclarationIrBuilder(context, fn.symbol).irBlockBody {
        +irReturn(value = expression)
      }
    }

    val cache = irCache(
      currentComposer = irCurrentComposer(),
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
      returnType = expression.type,
      invalid = invalidExpr,
      calculation = calculation,
    )

    return if (useNonSkippingGroupOptimization /* 기본으로 비활성화되어 있음 */) {
      cache
    } else {
      // If the non-skipping group optimization is disabled then we need to wrap
      // the call to `cache` in a replace group.
      //
      // non-skipping group optimization가 비활성화되어 있는 경우 `cache` 호출을
      // replace group으로 래핑해야 합니다.

      val currentFunctionFqName = currentFunctionContext?.declaration?.kotlinFqName?.asString()
      val key = currentFunctionFqName.hashCode() + expression.startOffset
      val cacheTmpVar = irTemporaryVariable(value = cache, name = "tmpCache")

      cacheTmpVar.wrap(
        type = expression.type,
        before = listOf(
          irStartReplaceGroup(
            currentComposer = irCurrentComposer(),
            key = irIntConst(key),
          ),
        ),
        after = listOf(
          irEndReplaceGroup(currentComposer = irCurrentComposer()),
          irGet(variable = cacheTmpVar),
        ),
      )
    }
  }

  private fun irRemember(
    keys: List<IrExpression>,
    expression: IrExpression,
  ): IrExpression {
    // Exclude the varargs version.
    // 가변 인자 버전은 제외합니다.
    val directRememberFunction =
      rememberFunctions.singleOrNull { fn ->
        // keys + calculation arg
        fn.valueParameters.size == keys.size + 1 &&
          // Exclude the varargs
          fn.valueParameters.firstOrNull()?.isVararg == false
      }

    val rememberFunction =
      directRememberFunction ?:
      // Use the varargs version.
      // 가변 인자 버전을 사용합니다.
      rememberFunctions.single { it.valueParameters[0].isVararg }

    return DeclarationIrBuilder(
      generatorContext = context,
      symbol = currentFunctionContext!!.symbol,
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
    )
      .irCall(
        callee = rememberFunction.symbol,
        type = expression.type,
        origin = ComposeMemoizedLambdaOrigin,
      )
      .apply {
        // The result type type parameter is first, followed by the argument types.
        // 결과 타입 매개변수가 먼저 오고, 그 뒤에 인자 타입들이 옵니다.
        typeArguments[0] = expression.type

        val lambdaArgumentIndex =
          if (directRememberFunction != null) {
            // condition arguments are the first `arg.size` arguments.
            // 조건 인자는 arg.size번에 있습니다.
            for (i in keys.indices) {
              putValueArgument(i, keys[i])
            }

            // The lambda is the last parameter.
            // 람다는 마지막 매개변수입니다.
            keys.size
          }

          // directRememberFunction == null
          else {
            // Call to the vararg version.
            // 가변 인자 버전을 호출합니다.
            putValueArgument(
              0,
              IrVarargImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = rememberFunction.valueParameters[0].type,
                varargElementType = context.irBuiltIns.anyType,
                elements = keys,
              ),
            )

            // The lambda is the second parameter.
            // 람다는 두 번째 매개변수입니다.
            1
          }

        putValueArgument(
          lambdaArgumentIndex,
          irLambdaExpression(
            startOffset = expression.startOffset,
            endOffset = expression.endOffset,
            returnType = expression.type,
          ) { fn ->
            fn.body = DeclarationIrBuilder(context, fn.symbol).irBlockBody {
              +irReturn(value = expression)
            }
          }
        )
      }
  }

  private fun irCurrentComposer(): IrCall =
    IrCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = composerIrClass.defaultType,
      symbol = currentComposerSymbol as IrSimpleFunctionSymbol,
      typeArgumentsCount = currentComposerSymbol.owner.typeParameters.size,
      origin = IrStatementOrigin.FOR_LOOP_ITERATOR,
    )

  private fun irChanged(value: IrExpression): IrCall =
    irChanged(
      currentComposer = irCurrentComposer(),
      value = value,
      inferredStable = false,
      compareInstanceForFunctionTypes = false,
      compareInstanceForUnstableValues = FeatureFlag.StrongSkipping.enabled,
    )

  private fun startCollector(collector: CaptureCollector) {
    for (declarationContext in declarationContextStack) {
      declarationContext.pushCollector(collector = collector)
    }
  }

  private fun stopCollector(collector: CaptureCollector) {
    for (declarationContext in declarationContextStack) {
      declarationContext.popCollector(collector = collector)
    }
  }

  private fun IrValueDeclaration.isVar(): Boolean =
    (this as? IrVariable)?.isVar == true

  private fun IrValueDeclaration.isStable(): Boolean =
    stabilityInferencer.stabilityOfType(type = type).knownStable()

  // inline 함수의 (inline되는) 람다 매개변수인지 확인
  private fun IrValueDeclaration.isInlinedLambda(): Boolean =
    isInlineableFunction() &&
      this is IrValueParameter &&
      (parent as? IrFunction)?.isInline == true &&
      !isNoinline

  private fun IrValueDeclaration.isInlineableFunction(): Boolean =
    type.isFunctionOrKFunction() ||
      type.isSyntheticComposableFunction() ||
      type.isSuspendFunctionOrKFunction()

  private fun <T : IrExpression> T.markAsStatic(): T {
    // Mark it so the ComposableCallTransformer will insert the correct code around this
    // call.
    //
    // 이 호출 주변에 올바른 코드를 삽입하도록 ComposableCallTransformer가 인식하게 표시합니다.
    context.irTrace.record(
      slice = ComposeWritableSlices.IS_STATIC_FUNCTION_EXPRESSION,
      key = this,
      value = true,
    )
    return this
  }

  private fun <T : IrElement> T.markAsComposableSingleton(): T {
    // Mark it so the ComposableCallTransformer can insert the correct source information
    // around this call.
    //
    // 이 호출 주변에 올바른 코드를 삽입하도록 ComposableCallTransformer가 인식하게 표시합니다.
    context.irTrace.record(
      slice = ComposeWritableSlices.IS_COMPOSABLE_SINGLETON,
      key = this,
      value = true,
    )
    return this
  }

  private fun <T : IrElement> T.markAsComposableSingletonClass(): T {
    // Mark it so the ComposableCallTransformer can insert the correct source information
    // around this call.
    //
    // 이 호출 주변에 올바른 코드를 삽입하도록 ComposableCallTransformer가 인식하게 표시합니다.
    context.irTrace.record(
      slice = ComposeWritableSlices.IS_COMPOSABLE_SINGLETON_CLASS,
      key = this,
      value = true,
    )
    return this
  }

  private fun <T : IrElement> T.markHasTransformedLambda(): T {
    // Mark so that the target annotation transformer can find the original lambda.
    // TargetAnnotationTransformer가 원본 람다를 찾을 수 있도록 표시합니다.
    context.irTrace.record(
      slice = ComposeWritableSlices.HAS_TRANSFORMED_LAMBDA,
      key = this,
      value = true,
    )
    return this
  }

  private fun <T : IrElement> T.markIsTransformedLambda(): T {
    context.irTrace.record(
      slice = ComposeWritableSlices.IS_TRANSFORMED_LAMBDA,
      key = this,
      value = true,
    )
    return this
  }

  private val IrFunction.allowsComposableCalls: Boolean
    get() =
      hasComposableAnnotation() ||
        (
          inlineLambdaInfo.preservesComposableScope(function = this) &&
            declarationContextStack.peek()?.composable == true
          )

  private val IrExpression.hasDontMemoizeAnnotation: Boolean
    get() = (this as? IrFunctionExpression)?.function?.hasAnnotation(ComposeFqNames.DontMemoize) ?: false

  private fun IrExpression?.isNullOrStable(): Boolean =
    this == null || stabilityInferencer.stabilityOfExpression(expr = this).knownStable()

  // TODO(b/315869143)
  //  consider hoisting property reference receivers into a variable and memoizing based on them.
  //
  // 프로퍼티 참조의 리시버를 변수로 끌어올려 저장하고 그 값을 기준으로 메모이제이션하는 방안을
  // 고려합니다.
  private fun IrValueDeclaration.isPropertyReferenceDelegate(): Boolean =
    origin == IrDeclarationOrigin.PROPERTY_DELEGATE &&
      this is IrVariable &&
      initializer is IrPropertyReference
}

// This must match the highest value of FunctionXX which is current Function22.
// 이는 FunctionXX 중 최댓값과 일치해야 하며, 현재 최댓값은 Function22입니다.
private const val MAX_RESTART_ARGUMENT_COUNT = 22

internal val ComposeMemoizedLambdaOrigin by IrStatementOriginImpl
