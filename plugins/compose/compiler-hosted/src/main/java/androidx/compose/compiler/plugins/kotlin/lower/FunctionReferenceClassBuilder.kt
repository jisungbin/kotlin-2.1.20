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

import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.setSourceRange
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Before:
 * ```
 * fun interface A {
 *     @Composable fun compute(value: Int): Unit
 * }
 * fun Example(a: A) {
 *     Example { it -> a.compute(it) }
 * }
 * ```
 *
 * After:
 * ```
 * interface A {
 *   @Composable
 *   abstract fun compute(value: Int)
 * }
 * fun Example(a: A) {
 *   Example({
 *     class <no name provided> : A {
 *       @Composable
 *       override fun compute(it: Int) {
 *         a.compute(it)
 *       }
 *     }
 *     <no name provided>()
 *   })
 * }
 * ```
 */
class FunctionReferenceClassBuilder(
  private val functionExpression: IrFunctionExpression,
  functionSuperClass: IrClassSymbol,
  private val superType: IrType,
  private val currentDeclarationParent: IrDeclarationParent,
  private val context: IrGeneratorContext,
  private val currentScopeOwnerSymbol: IrSymbol,
  private val typeSystemContext: IrTypeSystemContext,
) {
  private val callee = functionExpression.function
  private val superMethod =
    functionSuperClass.functions.single { it.owner.modality == Modality.ABSTRACT }

  private val functionReferenceClass =
    context.irFactory.buildClass {
      name = SpecialNames.NO_NAME_PROVIDED
      visibility = DescriptorVisibilities.LOCAL
      origin = JvmLoweredDeclarationOrigin.LAMBDA_IMPL
      setSourceRange(functionExpression)
    }.apply {
      parent = currentDeclarationParent
      superTypes = listOf(superType)
      createThisReceiverParameter()
      copyAttributes(functionExpression)
      metadata = functionExpression.function.metadata
    }

  fun buildClassCall(): IrExpression =
    DeclarationIrBuilder(
      generatorContext = context,
      symbol = currentScopeOwnerSymbol,
    ).run {
      irBlock(functionExpression.startOffset, functionExpression.endOffset) {
        val constructor = createFunctionReferenceClassConstructor()
        createSuperMethodOverridenFunctionAtFunctionReferenceClass()
        functionReferenceClass.addFakeOverrides(typeSystemContext)
        +functionReferenceClass
        +irCall(constructor.symbol)
      }
    }

  private fun createFunctionReferenceClassConstructor(): IrConstructor =
    functionReferenceClass.addConstructor {
      origin = JvmLoweredDeclarationOrigin.GENERATED_MEMBER_IN_CALLABLE_REFERENCE
      returnType = functionReferenceClass.defaultType
      isPrimary = true
    }.apply {
      val constructor = typeSystemContext.irBuiltIns.anyClass.owner.constructors.single()
      body = DeclarationIrBuilder(context, symbol).run {
        irBlockBody(startOffset, endOffset) {
          +irDelegatingConstructorCall(constructor)

          // STUDY IrInstanceInitializerCall의 용도 파악하기
          +IrInstanceInitializerCallImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            classSymbol = functionReferenceClass.symbol,
            type = context.irBuiltIns.unitType,
          )
        }
      }
    }

  private fun createSuperMethodOverridenFunctionAtFunctionReferenceClass(): IrSimpleFunction =
    functionReferenceClass.addFunction {
      name = superMethod.owner.name
      returnType = callee.returnType
      isSuspend = callee.isSuspend
      setSourceRange(callee)
    }.apply {
      copyMethodBodyFromCallee()
      overriddenSymbols += superMethod
      dispatchReceiverParameter = parentAsClass.thisReceiver!!.copyTo(this)
    }

  // Inline the body of an anonymous function into the generated lambda subclass.
  // 익명 함수의 본문을 생성된 람다 서브클래스에 인라인으로 삽입합니다.
  private fun IrSimpleFunction.copyMethodBodyFromCallee() {
    annotations += callee.annotations

    @Suppress("ReplaceAssociateFunction")
    val valueParameterMap = callee.parameters.associate { param ->
      param to param.copyTo(this)
    }

    valueParameters += valueParameterMap.values
    body = callee.moveBodyTo(this, valueParameterMap)
  }
}
