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
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.Name

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
    val shouldBreak = declarationContext.recordCapturedValue(value)
    if (shouldBreak) break
  }
}

private fun List<DeclarationContext>.recordLocalDeclarationCapturesFromLocalContext(localContext: DeclarationContext) {
  for (declarationContext in reversed()) {
    declarationContext.recordLocalDeclarationCapturesFromLocalContext(localContext)
  }
}

/** @return [declaration] 안에서 캡처된 variables */
private fun List<DeclarationContext>.recordLocalCapturedDeclaration(declaration: IrSymbolOwner): Set<IrValueDeclaration>? {
  val localCaptures = reversed().firstNotNullOfOrNull { it.localDeclarationCapturedValues[declaration] }
  if (localCaptures != null) {
    localCaptures.forEach { recordCapturedValue(it) }
    for (declarationContext in reversed()) {
      declarationContext.recordCapturedDeclaration(declaration)
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
    functionContext.declareOwnLocalValue(value)
  }

  override fun recordCapturedValue(value: IrValueDeclaration?): Boolean =
    functionContext.recordCapturedValue(value)

  override fun recordCapturedDeclaration(declaration: IrSymbolOwner?) {
    functionContext.recordCapturedDeclaration(declaration)
  }

  override fun pushCollector(collector: CaptureCollector) {
    functionContext.pushCollector(collector)
  }

  override fun popCollector(collector: CaptureCollector) {
    functionContext.popCollector(collector)
  }
}

private class FunctionContext(
  override val declaration: IrFunction,
  override val composable: Boolean,
) : DeclarationContext() {
  override val externalCapturedValues: MutableSet<IrValueDeclaration> = mutableSetOf()

  override val symbol: IrFunctionSymbol get() = declaration.symbol
  override val functionContext: FunctionContext get() = this

  val collectors = mutableListOf<CaptureCollector>()

  /** [declaration] 자체에 정의된 local variables */
  val ownLocalValues = mutableSetOf<IrValueDeclaration>()

  init {
    declaration.valueParameters.forEach { declareOwnLocalValue(it) }
    declaration.dispatchReceiverParameter?.let { declareOwnLocalValue(it) }
    declaration.extensionReceiverParameter?.let { declareOwnLocalValue(it) }
  }

  override fun declareOwnLocalValue(value: IrValueDeclaration?) {
    if (value != null) {
      ownLocalValues.add(value)
    }
  }

  override fun recordCapturedValue(value: IrValueDeclaration?): Boolean {
    val containsOwnLocal = ownLocalValues.contains(value)
    if (value != null && collectors.isNotEmpty() && containsOwnLocal) {
      for (collector in collectors) {
        collector.recordCapturedValue(value)
      }
    }
    if (value != null && declaration.isLocal && !containsOwnLocal) {
      externalCapturedValues.add(value)
    }
    return containsOwnLocal
  }

  override fun recordCapturedDeclaration(declaration: IrSymbolOwner?) {
    if (declaration != null) {
      val capturedValues = localDeclarationCapturedValues[declaration]
      for (collector in collectors) {
        collector.recordCapturedDeclaration(declaration)
        if (capturedValues != null) {
          for (capture in capturedValues) {
            collector.recordCapturedValue(capture)
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
    collectors.removeAt(collectors.size - 1)
  }
}

private class ClassContext(override val declaration: IrClass) : DeclarationContext() {
  override val externalCapturedValues: MutableSet<IrValueDeclaration> = mutableSetOf()

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
        collector.recordCapturedValue(value)
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
    collectors.removeAt(collectors.size - 1)
  }
}

class ComposerLambdaMemoization(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
) : AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags),
  ModuleLoweringPass {
  private val declarationContextStack = mutableListOf<DeclarationContext>()

  private val currentFunctionContext: FunctionContext?
    get() = declarationContextStack.peek()?.functionContext

  private var composableSingletonsClass: IrClass? = null
  private var currentFile: IrFile? = null
  private val usedSingletonLambdaNames = hashSetOf<String>()

  private var inlineLambdaInfo = ComposeInlineLambdaLocator(context)

  private val rememberFunctions = getTopLevelFunctions(ComposeCallableIds.remember).map { it.owner }

  private val composableLambdaFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambda)
  }

  private val composableLambdaNFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambdaN)
  }

  private val composableLambdaInstanceFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambdaInstance)
  }

  private val composableLambdaInstanceNFunction by guardedLazy {
    getTopLevelFunction(ComposeCallableIds.composableLambdaNInstance)
  }

  private val rememberComposableLambdaFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.rememberComposableLambda).singleOrNull()
  }

  private val rememberComposableLambdaNFunction by guardedLazy {
    getTopLevelFunctions(ComposeCallableIds.rememberComposableLambdaN).singleOrNull()
  }

  private val useNonSkippingGroupOptimization by guardedLazy {
    // Uses `rememberComposableLambda` as a indication that the runtime supports
    // generating remember after call as it was added at the same time as the slot table was
    // modified to support remember after call.
    //
    // STUDY `after call`이 무슨 의미로 쓰일까?
    //
    // 슬롯 테이블이 호출 후 remember를 지원하도록 수정됨과 동시에 `rememberComposableLambda`가
    // 추가되었으므로, 런타임이 호출 후 remember 생성을 지원한다는 표시로 `rememberComposableLambda`를
    // 사용합니다.
    FeatureFlag.OptimizeNonSkippingGroups.enabled && rememberComposableLambdaFunction != null
  }

  private fun getOrCreateComposableSingletonsEmptyClass(): IrClass {
    if (composableSingletonsClass != null) return composableSingletonsClass!!

    val declaration = currentFile!!
    val filePath = declaration.fileEntry.name
    val fileName = filePath.split('/').last()
    val current = context.irFactory.buildClass {
      startOffset = SYNTHETIC_OFFSET
      endOffset = SYNTHETIC_OFFSET
      kind = ClassKind.OBJECT
      visibility = DescriptorVisibilities.INTERNAL

      val shortName = PackagePartClassUtils.getFilePartShortName(fileName)
      name = Name.identifier("ComposableSingletons${"$"}$shortName")
    }.also { clazz ->
      clazz.createThisReceiverParameter()
      clazz.addConstructor { isPrimary = true }.also { constructor ->
        constructor.body = DeclarationIrBuilder(context, clazz.symbol).irBlockBody {
          +irDelegatingConstructorCall(
            context
              .irBuiltIns
              .anyClass
              .owner
              .primaryConstructor!!,
          )
          +IrInstanceInitializerCallImpl(
            startOffset = this.startOffset,
            endOffset = this.endOffset,
            classSymbol = clazz.symbol,
            type = context.irBuiltIns.unitType,
          )
        }
      }
    }.markAsComposableSingletonClass()

    composableSingletonsClass = current
    return current
  }

  override fun lower(irModule: IrModuleFragment) {
    inlineLambdaInfo.scan(irModule)
    irModule.transformChildrenVoid(this)
  }

  override fun visitFile(declaration: IrFile): IrFile =
    includeFileNameInExceptionTrace(declaration) {
      val prevFile = currentFile
      val prevClass = composableSingletonsClass
      try {
        currentFile = declaration
        composableSingletonsClass = null
        usedSingletonLambdaNames.clear()

        val file = super.visitFile(declaration)

        val resultingClass = composableSingletonsClass
        if (resultingClass != null && resultingClass.declarations.isNotEmpty()) {
          file.addChild(resultingClass)
        }

        file
      } finally {
        currentFile = prevFile
        composableSingletonsClass = prevClass
      }
    }

  override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement {
    if (declaration is IrFunction)
      return super.visitDeclaration(declaration)

    val functionContext = currentFunctionContext
    if (functionContext != null) {
      declarationContextStack.push(FunctionLocalContext(declaration, functionContext))
    } else {
      declarationContextStack.push(DeclarationOwnerContext(declaration))
    }

    val result = super.visitDeclaration(declaration)
    declarationContextStack.pop()

    return result
  }

  override fun visitFunction(declaration: IrFunction): IrStatement {
    val composable = declaration.allowsComposableCalls
    val context = FunctionContext(declaration, composable)

    if (declaration.isLocal) {
      declarationContextStack.recordLocalDeclarationCapturesFromLocalContext(context)
    }

    declarationContextStack.push(context)
    val result = super.visitFunction(declaration)
    declarationContextStack.pop()

    return result
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    val context = ClassContext(declaration)

    if (declaration.isLocal) {
      declarationContextStack.recordLocalDeclarationCapturesFromLocalContext(context)
    }

    declarationContextStack.push(context)
    val result = super.visitClass(declaration)
    declarationContextStack.pop()

    return result
  }

  override fun visitVariable(declaration: IrVariable): IrStatement {
    declarationContextStack.peek()?.declareOwnLocalValue(declaration)
    return super.visitVariable(declaration)
  }

  override fun visitValueAccess(expression: IrValueAccessExpression): IrExpression {
    declarationContextStack.recordCapturedValue(expression.symbol.owner)
    return super.visitValueAccess(expression)
  }

  override fun visitBlock(expression: IrBlock): IrExpression {
    val result = super.visitBlock(expression)

    if (result is IrBlock && result.origin == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE) {
      if (inlineLambdaInfo.isInlineFunctionExpression(expression)) {
        // Do not memoize function references for inline lambdas
        return result
      }

      val functionReference = result.statements.last()
      if (functionReference !is IrFunctionReference) {
        //  Do not memoize if the expected shape doesn't match.
        return result
      }

      return rememberFunctionReference(reference = functionReference, expression = expression)
    }

    return result
  }

  // Memoize the instance created by using the :: operator
  override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
    val result = super.visitFunctionReference(expression)

    if (
      inlineLambdaInfo.isInlineFunctionExpression(expression) ||
      inlineLambdaInfo.isInlineLambda(expression.symbol.owner)
    ) {
      // Do not memoize function references used in inline parameters.
      return result
    }

    if (expression.symbol.owner.origin == IrDeclarationOrigin.ADAPTER_FOR_CALLABLE_REFERENCE) {
      // Adapted function reference (inexact function signature match) is handled in block
      return result
    }

    if (result !is IrFunctionReference) {
      // Do not memoize if the shape doesn't match
      return result
    }

    return rememberFunctionReference(reference = result, expression = result)
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    // SAM conversions are handled by Kotlin compiler.
    // We only need to make sure that remember is handled correctly around type operator.
    if (
      expression.operator != IrTypeOperator.SAM_CONVERSION ||
      currentFunctionContext?.composable != true
    ) {
      return super.visitTypeOperator(expression)
    }

    // Unwrap function from type operator
    val originalFunctionExpression =
      expression.findSamFunctionExpr() ?: return super.visitTypeOperator(expression)

    // Record capture variables for this scope
    val collector = CaptureCollector()
    startCollector(collector)

    // Handle inside of the function expression
    val result = super.visitFunctionExpression(originalFunctionExpression)

    stopCollector(collector)

    // If the ancestor converted this then return
    val newFunctionExpression = result as? IrFunctionExpression ?: return result

    // Construct new type operator call to wrap remember around.
    val newArgument = when (val argument = expression.argument) {
      is IrFunctionExpression -> newFunctionExpression
      is IrTypeOperatorCall -> {
        require(
          argument.operator == IrTypeOperator.IMPLICIT_CAST &&
            argument.argument == originalFunctionExpression
        ) {
          "Only implicit cast is supported inside SAM conversion"
        }
        IrTypeOperatorCallImpl(
          startOffset = argument.startOffset,
          endOffset = argument.endOffset,
          type = argument.type,
          operator = argument.operator,
          typeOperand = argument.typeOperand,
          argument = newFunctionExpression,
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
      declarationContextStack.recordLocalCapturedDeclaration(fn)
    }
    return super.visitCall(expression)
  }

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val fn = expression.symbol.owner
    val cls = fn.parent as? IrClass
    if (cls != null && fn.isLocal) {
      declarationContextStack.recordLocalCapturedDeclaration(cls)
    }
    return super.visitConstructorCall(expression)
  }

  override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
    val declarationContext = declarationContextStack.peek() ?: return super.visitFunctionExpression(expression)

    return if (expression.function.allowsComposableCalls) {
      visitComposableFunctionExpression(expression = expression, declarationContext = declarationContext)
    } else {
      visitNonComposableFunctionExpression(expression = expression)
    }
  }

  private fun visitNonComposableFunctionExpression(expression: IrFunctionExpression): IrExpression {
    val functionContext = currentFunctionContext ?: return super.visitFunctionExpression(expression)

    if (
    // Only memoize non-composable lambdas in a context we can use remember.
    // 재사용할 수 없는 람다를 `remember`할 수 있는 위치에서만 메모하세요.
      !functionContext.composable ||
      // Don't memoize inlined lambdas
      inlineLambdaInfo.isInlineLambda(expression.function)
    ) {
      return super.visitFunctionExpression(expression)
    }

    // Record capture variables for this scope
    val collector = CaptureCollector()
    startCollector(collector)

    // Wrap composable functions expressions or memoize non-composable function expressions
    // 컴포저블 함수 표현식을 래핑하거나 컴포저블이 아닌 함수 표현식을 메모화하세요.
    val result = super.visitFunctionExpression(expression)

    stopCollector(collector)

    // If the ancestor converted this then return
    val functionExpression = result as? IrFunctionExpression ?: return result

    return rememberExpression(
      functionContext = functionContext,
      expression = functionExpression,
      capturedValues = collector.capturedValues.toList(),
    )
  }

  private fun visitComposableFunctionExpression(
    expression: IrFunctionExpression,
    declarationContext: DeclarationContext,
  ): IrExpression {
    val collector = CaptureCollector()
    startCollector(collector)

    val result = super.visitFunctionExpression(expression)

    stopCollector(collector)

    // If the ancestor converted this then return
    val functionExpression = result as? IrFunctionExpression ?: return result

    // Do not wrap target of an inline function
    if (inlineLambdaInfo.isInlineLambda(expression.function)) {
      return functionExpression
    }

    // Do not wrap composable lambdas with return results
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

    if (!collector.hasCaptures) {
      val enclosingFunction = declarationContext.functionContext?.declaration
      val inPublicInlineScope = enclosingFunction?.isInPublicInlineScope == true
      val singletonLambda = irGetComposableSingletonLambda(
        lambdaExpression = wrapFunctionExpressionWithComposableLambda(
          declarationContext = declarationContext,
          expression = functionExpression,
          collector = collector,
          useRememberingFactory = false,
        ),
        lambdaType = expression.type,
        lambdaName = createSingletonLambdaName(functionExpression),
      )
      return if (inPublicInlineScope) {
        // Public inline functions can't use singleton instance because changes to the function body
        // can cause ABI incompatibilities. Note that we still generate singleton instances
        // to ensure that we don't break existing consumers.
        //
        // 공용 인라인 함수는 함수 본문을 변경하면 ABI 비호환성이 발생할 수 있으므로 싱글톤 인스턴스를
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
      } else {
        singletonLambda
      }
    } else {
      return wrapFunctionExpressionWithComposableLambda(
        declarationContext = declarationContext,
        expression = functionExpression,
        collector = collector,
        useRememberingFactory = declarationContext.composable,
      )
    }
  }

  private fun createSingletonLambdaName(expression: IrFunctionExpression): String {
    val name = "lambda$${expression.function.sourceKey()}"
    if (usedSingletonLambdaNames.add(name)) {
      return name
    }

    var manglingNumber = 0
    while (true) {
      val mangledName = "$name$${manglingNumber++}"
      if (usedSingletonLambdaNames.add(mangledName)) {
        return mangledName
      }
    }
  }

  private fun irGetComposableSingletonLambda(
    lambdaExpression: IrExpression,
    lambdaType: IrType,
    lambdaName: String,
  ): IrCall {
    val clazz = getOrCreateComposableSingletonsEmptyClass()
    val lambdaProp = clazz.addProperty {
      name = Name.identifier(lambdaName)
      visibility = DescriptorVisibilities.INTERNAL
    }.also { property ->
      property.backingField = context.irFactory.buildField {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        name = Name.identifier(lambdaName)
        type = lambdaType
        visibility = DescriptorVisibilities.PRIVATE
        isStatic = true
      }.also { field ->
        field.correspondingPropertySymbol = property.symbol
        field.parent = clazz
        field.initializer = DeclarationIrBuilder(context, clazz.symbol)
          .irExprBody(lambdaExpression.markIsTransformedLambda())
      }
      val getter = property.addGetter {
        returnType = lambdaType
        visibility = DescriptorVisibilities.INTERNAL
        origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
      }.also { getter ->
        val thisParam = clazz.thisReceiver!!.copyTo(getter)
        getter.parent = clazz
        getter.dispatchReceiverParameter = thisParam
        getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
          +irReturn(irGetField(receiver = irGet(thisParam), field = property.backingField!!))
        }
      }

      /*
        Add property for backwards compatibility:
          Previous versions of the compose compiler leaked this ComposableSingletons class through inline functions.
          To keep compatibility, we're still generating a property with the old lambda naming schema.

          이전 버전의 컴포즈 컴파일러는 인라인 함수를 통해 이 ComposableSingletons 클래스를 유출했습니다.
          호환성을 유지하기 위해 여전히 이전 람다 명명 스키마로 프로퍼티를 생성하고 있습니다.
       */
      if (currentFunctionContext?.declaration?.isInPublicInlineScope == true) {
        clazz.addProperty {
          name = Name.identifier("lambda-${usedSingletonLambdaNames.size}")
          visibility = DescriptorVisibilities.INTERNAL
        }.also { p ->
          p.addGetter {
            returnType = lambdaType
            visibility = DescriptorVisibilities.INTERNAL
          }.also { fn ->
            val thisParam = clazz.thisReceiver!!.copyTo(fn)
            fn.parent = clazz
            fn.dispatchReceiverParameter = thisParam
            fn.body = DeclarationIrBuilder(context, fn.symbol).irBlockBody {
              +irReturn(irCall(getter))
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
    ).markAsComposableSingleton()
  }

  private fun startCollector(collector: CaptureCollector) {
    for (declarationContext in declarationContextStack) {
      declarationContext.pushCollector(collector)
    }
  }

  private fun stopCollector(collector: CaptureCollector) {
    for (declarationContext in declarationContextStack) {
      declarationContext.popCollector(collector)
    }
  }

  private fun irCurrentComposer(): IrExpression {
    val currentComposerSymbol = getTopLevelPropertyGetter(ComposeCallableIds.currentComposer)

    return IrCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = composerIrClass.defaultType,
      symbol = currentComposerSymbol as IrSimpleFunctionSymbol,
      typeArgumentsCount = currentComposerSymbol.owner.typeParameters.size,
      origin = IrStatementOrigin.FOR_LOOP_ITERATOR,
    )
  }

  private fun wrapFunctionExpressionWithComposableLambda(
    declarationContext: DeclarationContext,
    expression: IrFunctionExpression,
    collector: CaptureCollector,
    useRememberingFactory: Boolean,
  ): IrCall {
    val function = expression.function
    val argumentCount = function.valueParameters.size

    val useComposableLambdaN = argumentCount > MAX_RESTART_ARGUMENT_COUNT
    val requiresComposerParameter = useRememberingFactory && rememberComposableLambdaFunction == null

    val composableLambdaSymbol = when {
      useRememberingFactory -> when {
        useComposableLambdaN -> rememberComposableLambdaNFunction ?: composableLambdaNFunction
        else -> rememberComposableLambdaFunction ?: composableLambdaFunction
      }
      useComposableLambdaN -> composableLambdaInstanceNFunction
      else -> composableLambdaInstanceFunction
    }

    val irBuilder = DeclarationIrBuilder(
      generatorContext = context,
      symbol = declarationContext.symbol,
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
    )

    val composableLambdaExpression = irBuilder.irCall(composableLambdaSymbol).apply {
      var index = 0

      // first parameter is the composer parameter if we are using the composable(remembering) factory
      if (requiresComposerParameter) {
        putValueArgument(index++, irCurrentComposer())
      }

      // key parameter
      putValueArgument(index++, irBuilder.irInt(expression.function.sourceKey()))

      // tracked parameter
      // If the lambda has no captures, then Kotlinc will turn it into a singleton instance,
      // which means that it will never change, thus does not need to be tracked.
      val shouldBeTracked = collector.capturedValues.isNotEmpty()
      putValueArgument(index++, irBuilder.irBoolean(shouldBeTracked))

      // ComposableLambdaN requires the arity
      if (useComposableLambdaN) {
        // arity parameter
        putValueArgument(index++, irBuilder.irInt(argumentCount))
      }

      if (index >= valueArgumentsCount) {
        error("function=${function.name.asString()}, count=$valueArgumentsCount, index=$index")
      }

      // block parameter
      putValueArgument(index, expression.markIsTransformedLambda())
    }

    return composableLambdaExpression.markHasTransformedLambda()
  }

  private fun rememberExpression(
    functionContext: FunctionContext,
    expression: IrExpression,
    capturedValues: List<IrValueDeclaration>,
  ): IrExpression {
    // If the function doesn't capture, Kotlin's default optimization is sufficient
    if (capturedValues.isEmpty()) {
      metrics.recordLambda(
        composable = false,
        memoized = true,
        singleton = true
      )
      return expression.markAsStatic()
    }

    // MEMO 함수 메모이제이션이 가능한 규칙: 중요 포인트
    // Don't memoize if the function is annotated with @DontMemoize or captures:
    // - any var declarations,
    // - unstable values (without strong skipping),
    // - local delegates with property references,
    // - inlined lambdas.
    if (
      functionContext.declaration.hasAnnotation(ComposeFqNames.DontMemoize) ||
      expression.hasDontMemoizeAnnotation ||
      capturedValues.any {
        it.isVar() ||
          (!it.isStable() && !FeatureFlag.StrongSkipping.enabled) ||
          it.isPropertyReferenceDelegate() ||
          it.isInlinedLambda()
      }
    ) {
      metrics.recordLambda(
        composable = false,
        memoized = false,
        singleton = false
      )
      return expression
    }

    val capturedExpressions = capturedValues.map { irGet(it) }
    metrics.recordLambda(
      composable = false,
      memoized = true,
      singleton = false
    )

    return if (!FeatureFlag.IntrinsicRemember.enabled) {
      // generate cache directly only if strong skipping is enabled without intrinsic remember.
      // otherwise, generated memoization won't benefit from capturing changed values.
      //
      // intrinsic remember 없이 strong skipping가 활성화된 경우에는 캐시를 직접 생성합니다.
      // 그렇지 않으면 생성된 메모화는 변경된 값을 캡처하는 이점을 얻지 못합니다.
      //
      // ==============================================================
      // ==============================================================
      // ==============================================================
      //
      // intrinsic remember:
      // https://kotlinlang.org/api/kotlin-gradle-plugin/compose-compiler-gradle-plugin/org.jetbrains.kotlin.compose.compiler.gradle/-compose-feature-flag/-companion/-intrinsic-remember.html
      //
      // intrinsic remember은 remember 호출을 인라인화하고 .equals 비교(키의 경우)를 가능한 경우
      // $changed 메타 매개변수의 비교로 대체하여 애플리케이션의 런타임 성능을 개선하는 최적화
      // 모드입니다. 이렇게 하면 런타임에 사용되는 슬롯이 줄어들고 비교가 더 적게 수행됩니다.
      irCache(keys = capturedExpressions, expression = expression)
    } else {
      irRemember(keys = capturedExpressions, expression = expression)
    }.patchDeclarationParents(functionContext.declaration)
  }

  private fun irCache(
    keys: List<IrExpression>,
    expression: IrExpression,
  ): IrExpression {
    val invalidExpr =
      keys
        .map(::irChanged)
        .reduceOrNull { acc, changed -> irBooleanOr(acc, changed) }
        ?: irBooleanConst(false)

    val calculation = irLambdaExpression(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      returnType = expression.type,
    ) { fn ->
      fn.body = DeclarationIrBuilder(context, fn.symbol).irBlockBody {
        +irReturn(expression)
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

    return if (useNonSkippingGroupOptimization) {
      cache
    } else {
      // If the non-skipping group optimization is disabled then we need to wrap
      // the call to `cache` in a replaceable group.
      //
      // non-skipping group optimization가 비활성화되어 있는 경우 '캐시'에 대한
      // 호출을 ReplaceableGroup으로 래핑해야 합니다.
      val currentFunctionFqName = currentFunctionContext?.declaration?.kotlinFqName?.asString()
      val key = currentFunctionFqName.hashCode() + expression.startOffset
      val cacheTmpVar = irTemporaryVariable(cache, "tmpCache")

      cacheTmpVar.wrap(
        type = expression.type,
        before = listOf(irStartReplaceGroup(irCurrentComposer(), irIntConst(key))),
        after = listOf(irEndReplaceGroup(irCurrentComposer()), irGet(cacheTmpVar)),
      )
    }
  }

  private fun irRemember(
    keys: List<IrExpression>,
    expression: IrExpression,
  ): IrExpression {
    val directRememberFunction = // Exclude the varargs version
      rememberFunctions.singleOrNull {
        // keys + calculation arg
        it.valueParameters.size == keys.size + 1 &&
          // Exclude the varargs
          it.valueParameters.firstOrNull()?.varargElementType == null
      }
    val rememberFunction =
      directRememberFunction ?:
      // Use the varargs version
      rememberFunctions.single { it.valueParameters.firstOrNull()?.varargElementType != null }

    val rememberFunctionSymbol = rememberFunction.symbol
    val irBuilder = DeclarationIrBuilder(
      generatorContext = context,
      symbol = currentFunctionContext!!.symbol,
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
    )

    return irBuilder.irCall(
      callee = rememberFunctionSymbol,
      type = expression.type,
      origin = ComposeMemoizedLambdaOrigin,
    ).apply {
      // The result type type parameter is first, followed by the argument types
      typeArguments[0] = expression.type

      val lambdaArgumentIndex = if (directRememberFunction != null) {
        // condition arguments are the first `arg.size` arguments
        for (i in keys.indices) {
          putValueArgument(i, keys[i])
        }

        // The lambda is the last parameter
        keys.size
      } else {
        val parameterType = rememberFunction.valueParameters[0].type

        // Call to the vararg version
        putValueArgument(
          0,
          IrVarargImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = parameterType,
            varargElementType = context.irBuiltIns.anyType,
            elements = keys,
          )
        )

        // The lambda is the second parameter
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
            +irReturn(expression)
          }
        }
      )
    }
  }

  private fun rememberFunctionReference(
    reference: IrFunctionReference,
    expression: IrExpression,
  ): IrExpression {
    // Get the local captures for local function ref, to make sure we invalidate memoized
    // reference if its capture is different.
    //
    // 로컬 함수 참조의 로컬 캡처를 가져와서, 해당 캡처가 달라지면 memoize한 참조를 무효화합니다.
    val localCaptures = if (reference.symbol.owner.visibility == DescriptorVisibilities.LOCAL) {
      declarationContextStack.recordLocalCapturedDeclaration(reference.symbol.owner)
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
      // Memoize the reference for <expr>::<method>
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
        // receiver를 임시 변수에 저장하고 결과 변수를 사용하여 함수 참조를 memoize합니다.
        val builder = DeclarationIrBuilder(
          generatorContext = context,
          symbol = functionContext.symbol,
          startOffset = expression.startOffset,
          endOffset = expression.endOffset,
        )

        return builder.irBlock(resultType = expression.type) {
          val tempDispatchReceiver = dispatchReceiver?.let {
            val tmp = irTemporary(it)
            captures.add(tmp)
            tmp
          }
          val tempExtensionReceiver = extensionReceiver?.let {
            val tmp = irTemporary(it)
            captures.add(tmp)
            tmp
          }

          // Patch reference receiver in place
          reference.dispatchReceiver = tempDispatchReceiver?.let { irGet(it) }
          reference.extensionReceiver = tempExtensionReceiver?.let { irGet(it) }

          +rememberExpression(
            functionContext = functionContext,
            expression = expression,
            capturedValues = captures,
          )
        }
      } else if (dispatchReceiver == null && extensionReceiver == null) {
        return rememberExpression(functionContext, expression, captures)
      }
    }

    return expression
  }

  private fun irChanged(value: IrExpression): IrExpression =
    irChanged(
      currentComposer = irCurrentComposer(),
      value = value,
      inferredStable = false,
      compareInstanceForFunctionTypes = false,
      compareInstanceForUnstableValues = FeatureFlag.StrongSkipping.enabled
    )

  private fun IrValueDeclaration.isVar(): Boolean =
    (this as? IrVariable)?.isVar == true

  private fun IrValueDeclaration.isStable(): Boolean =
    stabilityInferencer.stabilityOfType(type).knownStable()

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
    context.irTrace.record(
      slice = ComposeWritableSlices.IS_STATIC_FUNCTION_EXPRESSION,
      key = this,
      value = true
    )
    return this
  }

  private fun <T : IrElement> T.markAsComposableSingleton(): T {
    // Mark it so the ComposableCallTransformer can insert the correct source information
    // around this call.
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
    context.irTrace.record(
      slice = ComposeWritableSlices.IS_COMPOSABLE_SINGLETON_CLASS,
      key = this,
      value = true,
    )
    return this
  }

  private fun <T : IrElement> T.markHasTransformedLambda(): T {
    // Mark so that the target annotation transformer can find the original lambda.
    // 대상 어노테이션 트랜스포머가 원본 람다를 찾을 수 있도록 표시합니다.
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
        inlineLambdaInfo.preservesComposableScope(this) &&
        declarationContextStack.peek()?.composable == true

  private val IrExpression.hasDontMemoizeAnnotation: Boolean
    get() = (this as? IrFunctionExpression)?.function?.hasAnnotation(ComposeFqNames.DontMemoize) ?: false

  private fun IrExpression?.isNullOrStable() =
    this == null || stabilityInferencer.stabilityOfExpression(this).knownStable()

  // TODO(b/315869143): consider hoisting property reference receivers into a variable and memoizing based on them.
  private fun IrValueDeclaration.isPropertyReferenceDelegate() =
    origin == IrDeclarationOrigin.PROPERTY_DELEGATE &&
      this is IrVariable &&
      initializer is IrPropertyReference
}

// This must match the highest value of FunctionXX which is current Function22
private const val MAX_RESTART_ARGUMENT_COUNT = 22

internal object ComposeMemoizedLambdaOrigin : IrStatementOrigin {
  override val debugName: String get() = "ComposeMemoizedLambdaOrigin"
}
