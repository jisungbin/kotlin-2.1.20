/*
 * Copyright 2024 The Android Open Source Project
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

import androidx.compose.compiler.plugins.kotlin.ComposeMetadata
import androidx.compose.compiler.plugins.kotlin.ComposeNames
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.name.Name

/**
 * Replaces abstract/open function calls with default parameters with a wrapper that will contain
 * the default parameter preamble and make a virtual call to correct override.
 *
 * Given:
 * ```
 * abstract class Test {
 *     @Composable abstract fun doSomething(arg1: Int = remember { 0 })
 * }
 *
 * @Composable fun callWithDefaults(instance: Test) {
 *     instance.doSomething()
 *     instance.doSomething(0)
 * }
 * ```
 *
 * Generates:
 * ```
 * abstract class Test {
 *     @Composable abstract fun doSomething(arg1: Int)
 *
 *     class ComposeDefaultsImpl {
 *          /* static */ fun doSomething$composable$default(
 *              instance: Test,
 *              arg1: Int = remember { 0 },
 *          ) {
 *              return instance.doSomething(arg1)
 *          }
 *     }
 * }
 *
 *
 * @Composable fun callWithDefaults(
 *     instance: Test,
 *     $composer: Composer,
 *     $changed: Int
 * ) {
 *     Test$DefaultsImpl.doSomething(instance)
 *     Test$DefaultsImpl.doSomething(instance, 0)
 * }
 *```
 */
// 기본 매개변수를 가진 abstract/open 함수 호출을, 기본 매개변수 코드를 포함하고
// 올바른 오버라이드를 가상 호출하는 래퍼로 대체합니다.
// STUDY 이게 왜 필요할까?
class ComposableDefaultParamLowering(
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
) : AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags) {
  private val originalToTransformed = mutableMapOf<IrSimpleFunction, IrSimpleFunction>()

  override fun lower(irModule: IrModuleFragment) {
    irModule.transformChildrenVoid()
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    if (declaration in originalToTransformed) {
      // Make sure that calls in declaration body are not transformed the second time
      return declaration
    }

    if (!declaration.isVirtualComposableFunctionWithDefaultParam()) {
      return super.visitSimpleFunction(declaration)
    }

    declaration.transformIfNeeded()

    return super.visitSimpleFunction(declaration)
  }

  override fun visitCall(expression: IrCall): IrExpression {
    if (expression.superQualifierSymbol != null) {
      return super.visitCall(expression)
    }

    val callee = expression.symbol.owner
    if (!callee.hasComposableAnnotation()) {
      return super.visitCall(expression)
    }

    val wrapper = callee.findOverriddenFunWithDefaultParam()?.transformIfNeeded()
    if (wrapper == null) {
      return super.visitCall(expression)
    }

    val newCall = irCall(
      function = wrapper,
      startOffset = expression.startOffset,
      endOffset = expression.endOffset
    ).also { newCall ->
      var argCount = expression.valueArgumentsCount
      for (i in 0 until argCount) {
        newCall.putValueArgument(i, expression.getValueArgument(i))
      }
      if (expression.dispatchReceiver != null) {
        newCall.putValueArgument(argCount++, expression.dispatchReceiver)
      }
      if (expression.extensionReceiver != null) {
        newCall.putValueArgument(argCount, expression.extensionReceiver)
      }
    }

    return super.visitCall(newCall)
  }

  private fun IrSimpleFunction.transformIfNeeded(): IrSimpleFunction {
    if (this in originalToTransformed) return originalToTransformed[this]!!

    val wrapper = makeDefaultParameterWrapper(this)
    originalToTransformed[this] = wrapper

    // add to the set of transformed functions to ensure it is not transformed twice
    originalToTransformed[wrapper] = wrapper

    when (val parent = parent) {
      is IrClass -> getOrCreateDefaultImplClass(parent).addChild(wrapper)

      // virtual method이니 무조건 parent가 있음 -> 함수가 속할 수 있는 parent는 class뿐임
      else -> error("Cannot add wrapper function to $parent")
    }

    this.isVirtualFunctionWithDefaultParam = true

    if (modality == Modality.OPEN) {
      // For open functions, add metadata to indicate that open function wrappers are present
      if (origin == IrDeclarationOrigin.DEFINED) {
        this.composeMetadata = ComposeMetadata(LanguageVersion.LATEST_STABLE)

        // make stub for backwards compatibility
        val stub = makeStubForOpenFunctionWDefaultParamsIfNeeded(wrapper)
        if (stub != null) {
          (parent as? IrClass)?.addChild(stub)
        }
      }
    }
    parameters.forEach {
      it.defaultValue = null
    }

    return wrapper
  }

  private fun IrSimpleFunction.findOverriddenFunWithDefaultParam(): IrSimpleFunction? {
    if (this in originalToTransformed) {
      return this
    }

    if (isVirtualComposableFunctionWithDefaultParam()) {
      return this
    }

    overriddenSymbols.forEach {
      val matchingOverride = it.owner.findOverriddenFunWithDefaultParam()
      if (matchingOverride != null) {
        return matchingOverride
      }
    }

    return null
  }

  private fun IrSimpleFunction.isVirtualComposableFunctionWithDefaultParam() =
    hasComposableAnnotation() &&
      !isExpect &&
      isVirtualFunction() &&
      overriddenSymbols.isEmpty() && // first in the chain of overrides (=> 원본 메서드)
      parameters.any { it.defaultValue != null } // has a default parameter

  private fun IrSimpleFunction.isVirtualFunction() =
    modality == Modality.ABSTRACT || // abstract function
      (modality == Modality.OPEN &&  // open function with supported metadata
        (origin != IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB ||
          composeMetadata?.supportsOpenFunctionsWithDefaultParams() == true))

  private fun makeDefaultParameterWrapper(source: IrSimpleFunction): IrSimpleFunction {
    val wrapper = context.irFactory.createSimpleFunction(
      startOffset = source.startOffset,
      endOffset = source.endOffset,
      origin = IrDeclarationOrigin.DEFINED,
      name = Name.identifier("${source.name.asString()}\$default"),
      visibility = source.visibility,
      isInline = false,
      isExpect = false,
      returnType = source.returnType,
      modality = Modality.FINAL,
      symbol = IrSimpleFunctionSymbolImpl(),
      isTailrec = source.isTailrec,
      isSuspend = false,
      isOperator = false,
      isInfix = false,
    )
    wrapper.copyAnnotationsFrom(source)
    wrapper.copyParametersFrom(source)

    wrapper.valueParameters.forEach {
      it.defaultValue?.transformChildrenVoid()
    }

    // move receiver parameters to value parameters
    val dispatcherReceiver = wrapper.dispatchReceiverParameter
    if (dispatcherReceiver != null) {
      wrapper.dispatchReceiverParameter = null
      wrapper.valueParameters += dispatcherReceiver
    }

    val extensionReceiver = wrapper.extensionReceiverParameter
    if (extensionReceiver != null) {
      wrapper.extensionReceiverParameter = null
      wrapper.valueParameters += extensionReceiver
    }

    wrapper.body = DeclarationIrBuilder(context, wrapper.symbol).irBlockBody {
      +irCall(
        symbol = source.symbol,
        dispatchReceiver = dispatcherReceiver?.let(::irGet),
        extensionReceiver = extensionReceiver?.let(::irGet),
        args = Array(source.valueParameters.size) {
          irGet(wrapper.valueParameters[it])
        },
      )
    }

    return wrapper
  }

  private fun getOrCreateDefaultImplClass(parent: IrClass): IrClass {
    val cls = parent.declarations.find {
      it is IrClass && it.name == ComposeNames.DEFAULT_IMPLS
    } as? IrClass

    return cls ?: context.irFactory.buildClass {
      startOffset = parent.startOffset
      endOffset = parent.endOffset
      name = ComposeNames.DEFAULT_IMPLS
    }.apply {
      parent.addChild(this)
      createThisReceiverParameter()
    }
  }

  /**
   * Open @Composable restartable functions with default params used to be generated with restartable group and incorrect handling
   * of default parameters. This function generates a stub for such functions to ensure that the correct function is called for calls
   * generated by older compilers.
   */
  // 기본 매개변수를 가진 Open @Composable restartable 함수들은 이전에는 restartable group과
  // 잘못된 기본 매개변수 처리 방식으로 생성되었습니다. 이 함수는 이전 컴파일러에서 생성된 호출에
  // 대해 올바른 함수가 호출되도록 보장하는 스텁을 생성합니다.
  private fun IrSimpleFunction.makeStubForOpenFunctionWDefaultParamsIfNeeded(wrapper: IrSimpleFunction): IrSimpleFunction? {
    if (!visibility.isPublicAPI && !isPublishedApi()) {
      return null
    }

    return makeStub().also { copy ->
      originalToTransformed[copy] = wrapper
      copy.parameters.fastForEach {
        if (it.defaultValue != null) {
          it.defaultValue = factory.createExpressionBody(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            expression = irCall(builtIns.throwIseSymbol)
          )
        }
      }
      // Call into the wrapper function, moving receivers to value parameters
      copy.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
        statements.add(
          irReturn(
            target = copy.symbol,
            value = irCall(wrapper).apply {
              var index = 0
              copy.parameters.fastForEach { p ->
                if (p.kind == IrParameterKind.Regular || p.kind == IrParameterKind.Context) {
                  arguments[index++] = irGet(p)
                }
              }

              copy.parameters.fastForEach { p ->
                if (p.kind == IrParameterKind.DispatchReceiver) {
                  arguments[index++] = irGet(p)
                }
              }

              copy.parameters.fastForEach { p ->
                if (p.kind == IrParameterKind.ExtensionReceiver) {
                  arguments[index++] = irGet(p)
                }
              }
            },
            type = copy.returnType,
          )
        )
      }
    }
  }
}
