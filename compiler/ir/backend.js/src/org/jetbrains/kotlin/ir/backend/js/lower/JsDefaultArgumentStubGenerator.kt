/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.defaultArgumentsDispatchFunction
import org.jetbrains.kotlin.backend.common.lower.DefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.JsLoweredDeclarationOrigin
import org.jetbrains.kotlin.ir.backend.js.JsStatementOrigins
import org.jetbrains.kotlin.ir.backend.js.export.isExported
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.utils.JsAnnotations
import org.jetbrains.kotlin.ir.backend.js.utils.getVoid
import org.jetbrains.kotlin.ir.backend.js.utils.realOverrideTarget
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEqeqeqWithoutBox
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irRawFunctionReference
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableMember
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isExternalOrInheritedFromExternal
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isTopLevel
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.passTypeArgumentsFrom
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlin.utils.memoryOptimizedMap
import org.jetbrains.kotlin.utils.memoryOptimizedPlus

class JsDefaultArgumentStubGenerator(context: JsIrBackendContext) :
  DefaultArgumentStubGenerator<JsIrBackendContext>(
    context = context,
    factory = JsDefaultArgumentFunctionFactory(context),
    skipExternalMethods = true,
    forceSetOverrideSymbols = false
  ) {

  private fun IrBuilderWithScope.createDefaultResolutionExpression(
    defaultExpression: IrExpression?,
    toParameter: IrValueParameter,
  ): IrExpression? {
    return defaultExpression?.let {
      irIfThenElse(
        toParameter.type,
        irEqeqeqWithoutBox(
          irGet(toParameter, toParameter.type),
          this@JsDefaultArgumentStubGenerator.context.getVoid()
        ),
        it,
        irGet(toParameter)
      )
    }
  }

  private fun IrBuilderWithScope.createResolutionStatement(
    parameter: IrValueParameter,
    defaultExpression: IrExpression?,
  ): IrSetValue? {
    return createDefaultResolutionExpression(defaultExpression, parameter)?.let {
      JsIrBuilder.buildSetValue(parameter.symbol, it)
    }
  }

  private fun IrFunction.introduceDefaultResolution(): IrFunction {
    this.defaultArgumentsDispatchFunction?.let { return it }
    val irBuilder = context.createIrBuilder(symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    val variables = hashMapOf<IrValueParameter, IrValueParameter>()

    valueParameters = valueParameters.memoryOptimizedMap { param ->
      param.takeIf { it.defaultValue != null }
        ?.copyTo(this, isAssignable = true, origin = JsLoweredDeclarationOrigin.JS_SHADOWED_DEFAULT_PARAMETER)
        ?.also { new -> variables[param] = new } ?: param
    }

    val blockBody = body as? IrBlockBody

    if (blockBody != null && variables.isNotEmpty()) {
      blockBody.transformChildren(VariableRemapper(variables), null)
      blockBody.statements.addAll(0, valueParameters.mapNotNull {
        irBuilder.createResolutionStatement(it, it.defaultValue?.expression)
      })
    }

    return also {
      it.defaultArgumentsDispatchFunction = it
    }
  }

  override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
    if (declaration !is IrFunction || declaration.isExternalOrInheritedFromExternal()) {
      return null
    }

    if (declaration.hasDefaultArgs() && (declaration is IrConstructor || declaration.isTopLevel)) {
      return listOf(declaration.introduceDefaultResolution())
    }

    val (originalFun, defaultFunStub) = super.transformFlat(declaration) ?: return null

    if (originalFun !is IrFunction || defaultFunStub !is IrFunction) {
      return listOf(originalFun, defaultFunStub)
    }

    if (!defaultFunStub.isFakeOverride) {
      with(defaultFunStub) {
        valueParameters.forEach {
          if (it.defaultValue != null) {
            it.origin = JsLoweredDeclarationOrigin.JS_SHADOWED_DEFAULT_PARAMETER
          }
          it.defaultValue = null
        }

        if (originalFun.isExported(context)) {
          context.additionalExportedDeclarations.add(defaultFunStub)

          if (!originalFun.hasAnnotation(JsAnnotations.jsNameFqn)) {
            originalFun.annotations = originalFun.annotations memoryOptimizedPlus originalFun.generateJsNameAnnotationCall()
          }
        }
      }
    }

    val (exportAnnotations, irrelevantAnnotations) = originalFun.annotations
      .map { it.deepCopyWithSymbols(originalFun as? IrDeclarationParent) }
      .partition {
        it.isAnnotation(JsAnnotations.jsExportFqn) || (it.isAnnotation(JsAnnotations.jsNameFqn))
      }

    originalFun.annotations = irrelevantAnnotations
    defaultFunStub.annotations = exportAnnotations
    originalFun.origin = JsLoweredDeclarationOrigin.JS_SHADOWED_EXPORT

    return listOf(originalFun, defaultFunStub)
  }

  override fun IrFunction.generateDefaultStubBody(originalDeclaration: IrFunction): IrBody {
    val ctx = context
    val irBuilder = context.createIrBuilder(symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    val variables = hashMapOf<IrValueParameter, IrValueDeclaration>().apply {
      originalDeclaration.dispatchReceiverParameter?.let {
        set(it, dispatchReceiverParameter!!)
      }
      originalDeclaration.extensionReceiverParameter?.let {
        set(it, extensionReceiverParameter!!)
      }
      originalDeclaration.valueParameters.forEachIndexed { index, param ->
        set(param, valueParameters[index])
      }
    }

    return irBuilder.irBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
      +valueParameters.zip(originalDeclaration.valueParameters)
        .mapNotNull { (new, original) ->
          createResolutionStatement(
            new,
            original.defaultValue?.expression?.transform(VariableRemapper(variables), null),
          )
        }

      val wrappedFunctionCall = irCall(originalDeclaration, JsStatementOrigins.IMPLEMENTATION_DELEGATION_CALL).apply {
        passTypeArgumentsFrom(originalDeclaration)
        dispatchReceiver = dispatchReceiverParameter?.let { irGet(it) }
        extensionReceiver = extensionReceiverParameter?.let { irGet(it) }

        originalDeclaration.valueParameters.forEachIndexed { index, irValueParameter ->
          putValueArgument(index, irGet(variables[irValueParameter] ?: valueParameters[index]))
        }
      }

      var superContextValueParam: IrValueParameter? = null

      val superFunCall = runIf(wrappedFunctionCall.dispatchReceiver != null) {
        val superContext = valueParameters.last().also {
          superContextValueParam = it
        }
        val realOverrideTarget = originalDeclaration.realOverrideTarget.takeIf {
          it !is IrOverridableMember || it.modality !== Modality.ABSTRACT
        }
        if (realOverrideTarget?.parentClassOrNull?.isInterface == true) {
          irCall(realOverrideTarget).apply {
            extensionReceiver = wrappedFunctionCall.extensionReceiver?.deepCopyWithSymbols()
            (0 until wrappedFunctionCall.valueArgumentsCount).forEach {
              putValueArgument(it, wrappedFunctionCall.getValueArgument(it)?.deepCopyWithSymbols())
            }
          }
        } else {
          irCall(ctx.intrinsics.jsCall).apply {
            putValueArgument(0, wrappedFunctionCall.dispatchReceiver!!.deepCopyWithSymbols())
            putValueArgument(
              1,
              irCall(ctx.intrinsics.jsContexfulRef).apply {
                putValueArgument(0, irGet(superContext))
                putValueArgument(1, irRawFunctionReference(ctx.dynamicType, originalDeclaration.symbol))
              }
            )
            putValueArgument(2, irVararg(ctx.dynamicType, buildList {
              addIfNotNull(wrappedFunctionCall.extensionReceiver?.deepCopyWithSymbols())
              (0 until wrappedFunctionCall.valueArgumentsCount).forEach {
                addIfNotNull(wrappedFunctionCall.getValueArgument(it)?.deepCopyWithSymbols())
              }
            }))
          }
        }
      }

      +irReturn(
        if (superFunCall == null) {
          wrappedFunctionCall
        } else {
          irIfThenElse(
            originalDeclaration.returnType,
            irEqeqeqWithoutBox(irGet(superContextValueParam!!), ctx.getVoid()),
            wrappedFunctionCall,
            superFunCall
          )
        }
      )
    }
  }

  private fun IrFunction.generateJsNameAnnotationCall(): IrConstructorCall {
    val builder = context.createIrBuilder(symbol, startOffset, endOffset)

    return with(context) {
      builder.irCall(intrinsics.jsNameAnnotationSymbol.constructors.single())
        .apply {
          putValueArgument(
            0,
            IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.stringType, name.identifier)
          )
        }
    }
  }

  private fun IrConstructorCall.isAnnotation(name: FqName): Boolean {
    return symbol.owner.parentAsClass.fqNameWhenAvailable == name
  }

  private fun IrFunction.hasDefaultArgs(): Boolean =
    valueParameters.any { it.defaultValue != null }
}
