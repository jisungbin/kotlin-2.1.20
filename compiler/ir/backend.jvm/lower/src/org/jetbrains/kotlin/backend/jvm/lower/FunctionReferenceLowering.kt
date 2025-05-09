/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.SamEqualsHashCodeMethodsGenerator
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.JvmSymbols
import org.jetbrains.kotlin.backend.jvm.ir.JvmIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.createJvmIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.findInlineLambdas
import org.jetbrains.kotlin.backend.jvm.ir.getCallableReferenceOwnerKClassType
import org.jetbrains.kotlin.backend.jvm.ir.getCallableReferenceTopLevelFlag
import org.jetbrains.kotlin.backend.jvm.ir.getSingleAbstractMethod
import org.jetbrains.kotlin.backend.jvm.ir.kClassReference
import org.jetbrains.kotlin.backend.jvm.ir.kClassToJavaClass
import org.jetbrains.kotlin.backend.jvm.ir.rawType
import org.jetbrains.kotlin.backend.jvm.lower.indy.LambdaMetafactoryArguments
import org.jetbrains.kotlin.backend.jvm.lower.indy.LambdaMetafactoryArgumentsBuilder
import org.jetbrains.kotlin.backend.jvm.lower.indy.MetafactoryArgumentsResult
import org.jetbrains.kotlin.backend.jvm.lower.indy.SamDelegatingLambdaBlock
import org.jetbrains.kotlin.backend.jvm.lower.indy.SamDelegatingLambdaBuilder
import org.jetbrains.kotlin.config.JvmClosureGenerationScheme
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irRawFunctionReference
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.setSourceRange
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrMetadataSourceOwner
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.expressions.putArgument
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.IrTypeParameterRemapper
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.allTypeParameters
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.eraseTypeParameters
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.isLambda
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.util.typeSubstitutionMap
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.java.JvmAnnotationNames
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SERIALIZABLE_LAMBDA_ANNOTATION_FQ_NAME

/**
 * Constructs instances of anonymous KFunction subclasses for function references.
 */
@PhaseDescription(name = "FunctionReference")
internal class FunctionReferenceLowering(private val context: JvmBackendContext) : FileLoweringPass, IrElementTransformerVoidWithContext() {
  private val crossinlineLambdas = HashSet<IrSimpleFunction>()

  private val IrFunctionReference.isIgnored: Boolean
    get() = (!type.isFunctionOrKFunction() && !isSuspendFunctionReference()) || origin == IrStatementOrigin.INLINE_LAMBDA

  // `suspend` function references are the same as non-`suspend` ones, just with an extra continuation parameter;
  // however, suspending lambdas require different generation implemented in SuspendLambdaLowering
  // because they are also their own continuation classes.
  // TODO: Currently, origin of callable references explicitly written in source code is null. Do we need to create one?
  private fun IrFunctionReference.isSuspendFunctionReference(): Boolean = isSuspend &&
    (origin == null || origin == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE || origin == IrStatementOrigin.SUSPEND_CONVERSION)

  override fun lower(irFile: IrFile) {
    irFile.findInlineLambdas(context) { argument, _, parameter, _ ->
      if (parameter.isCrossinline) {
        crossinlineLambdas.add(argument.symbol.owner as IrSimpleFunction)
      }
    }
    irFile.transformChildrenVoid(this)
    crossinlineLambdas.clear()
  }

  private val shouldGenerateIndySamConversions =
    context.config.samConversionsScheme == JvmClosureGenerationScheme.INDY

  private val shouldGenerateIndyLambdas: Boolean
    get() = context.config.lambdasScheme == JvmClosureGenerationScheme.INDY
      // We prefer CLASS lambdas when evaluating expression in debugger, as such lambdas have pretty toString implementation
      // However, it's safe to change compilation scheme only for lambdas defined in code fragment, not it's dependencies
      && allScopes.none { (it.irElement as? IrMetadataSourceOwner)?.metadata is MetadataSource.CodeFragment }

  private val shouldGenerateLightweightLambdas: Boolean
    get() = shouldGenerateIndyLambdas && context.config.languageVersionSettings.supportsFeature(LanguageFeature.LightweightLambdas)

  private val isJavaSamConversionWithEqualsHashCode =
    context.config.languageVersionSettings.supportsFeature(LanguageFeature.JavaSamConversionEqualsHashCode)

  override fun visitBlock(expression: IrBlock): IrExpression {
    return processBlock(expression, forceSerializability = false)
  }

  private fun processBlock(expression: IrBlock, forceSerializability: Boolean): IrExpression {
    if (!expression.origin.isLambda)
      return super.visitBlock(expression)

    val reference = expression.statements.last() as IrFunctionReference
    if (reference.isIgnored)
      return super.visitBlock(expression)

    expression.statements.dropLast(1).forEach { it.transform(this, null) }
    reference.transformChildrenVoid(this)

    if (shouldGenerateIndyLambdas) {
      val lambdaMetafactoryArguments =
        LambdaMetafactoryArgumentsBuilder(context, crossinlineLambdas)
          .getLambdaMetafactoryArguments(reference, reference.type, plainLambda = true, forceSerializability)
      if (lambdaMetafactoryArguments is LambdaMetafactoryArguments) {
        return wrapLambdaReferenceWithIndySamConversion(expression, reference, lambdaMetafactoryArguments)
      }
      // TODO MetafactoryArgumentsResult.Failure.FunctionHazard?
    }

    return FunctionReferenceBuilder(reference).build()
  }

  private fun wrapLambdaReferenceWithIndySamConversion(
    expression: IrBlock,
    reference: IrFunctionReference,
    lambdaMetafactoryArguments: LambdaMetafactoryArguments,
  ): IrBlock {
    val indySamConversion = wrapWithIndySamConversion(reference.type, lambdaMetafactoryArguments)
    expression.statements[expression.statements.size - 1] = indySamConversion
    expression.type = indySamConversion.type
    return expression
  }

  override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
    expression.transformChildrenVoid(this)
    return if (expression.isIgnored)
      expression
    else
      FunctionReferenceBuilder(expression).build()
  }

  private fun getDeclarationParentForDelegatingLambda(): IrDeclarationParent {
    for (s in allScopes.asReversed()) {
      val scopeOwner = s.scope.scopeOwnerSymbol.owner
      if (scopeOwner is IrDeclarationParent) {
        return scopeOwner
      }
    }
    throw AssertionError(
      "No IrDeclarationParent found in scopes:\n" +
        allScopes.joinToString(separator = "\n") { "  " + it.scope.scopeOwnerSymbol.owner.render() }
    )
  }

  // Handle SAM conversions which wrap a function reference:
  //     class sam$n(private val receiver: R) : Interface { override fun method(...) = receiver.target(...) }
  //
  // This avoids materializing an invokable KFunction representing, thus producing one less class.
  // This is actually very common, as `Interface { something }` is a local function + a SAM-conversion
  // of a reference to it into an implementation.
  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    if (expression.operator != IrTypeOperator.SAM_CONVERSION) {
      return super.visitTypeOperator(expression)
    }

    val samSuperType = expression.typeOperand

    val invokable = expression.argument
    val reference = if (invokable is IrFunctionReference) {
      invokable
    } else if (invokable is IrBlock && invokable.origin.isLambda && invokable.statements.last() is IrFunctionReference) {
      invokable.statements.dropLast(1).forEach { it.transform(this, null) }
      invokable.statements.last() as IrFunctionReference
    } else if (shouldGenerateIndySamConversions && canGenerateIndySamConversionOnFunctionalExpression(samSuperType, invokable)) {
      val lambdaBlock = SamDelegatingLambdaBuilder(context)
        .build(invokable, samSuperType, currentScope!!.scope.scopeOwnerSymbol, getDeclarationParentForDelegatingLambda())
      val lambdaMetafactoryArguments = LambdaMetafactoryArgumentsBuilder(context, crossinlineLambdas)
        .getLambdaMetafactoryArguments(lambdaBlock.ref, samSuperType, plainLambda = false, forceSerializability = false)

      if (lambdaMetafactoryArguments !is LambdaMetafactoryArguments) {
        // TODO MetafactoryArgumentsResult.Failure.FunctionHazard?
        return super.visitTypeOperator(expression)
      }

      // This is what IR contains if SAM-converted argument needs additional cast
      // See ArgumentsGenerationUtilsKt.castArgumentToFunctionalInterfaceForSamType (K1)
      // or AdapterGenerator.castArgumentToFunctionalInterfaceForSamType (K2)
      // In this case we should propagate serialization flag from the SAM type regardless of delegating lambda type (see KT-70306)
      if (invokable is IrTypeOperatorCall && invokable.operator == IrTypeOperator.IMPLICIT_CAST && invokable.argument is IrBlock) {
        invokable.argument = processBlock(invokable.argument as IrBlock, lambdaMetafactoryArguments.shouldBeSerializable)
      } else {
        invokable.transformChildrenVoid()
      }

      return wrapSamDelegatingLambdaWithIndySamConversion(samSuperType, lambdaBlock, lambdaMetafactoryArguments)
    } else {
      return super.visitTypeOperator(expression)
    }

    reference.transformChildrenVoid()

    if (shouldGenerateIndySamConversions) {
      val lambdaMetafactoryArguments =
        LambdaMetafactoryArgumentsBuilder(context, crossinlineLambdas)
          .getLambdaMetafactoryArguments(reference, samSuperType, plainLambda = false, forceSerializability = false)
      if (lambdaMetafactoryArguments is LambdaMetafactoryArguments) {
        return wrapSamConversionArgumentWithIndySamConversion(expression) { samType ->
          wrapWithIndySamConversion(samType, lambdaMetafactoryArguments, expression.startOffset, expression.endOffset)
        }
      } else if (lambdaMetafactoryArguments is MetafactoryArgumentsResult.Failure.FunctionHazard) {
        // Try wrapping function with a proxy local function and see if that helps.
        val proxyLocalFunBlock = createProxyLocalFunctionForIndySamConversion(reference)
        val proxyLocalFunRef = proxyLocalFunBlock.statements[proxyLocalFunBlock.statements.size - 1] as IrFunctionReference
        val proxyLambdaMetafactoryArguments = LambdaMetafactoryArgumentsBuilder(context, crossinlineLambdas)
          .getLambdaMetafactoryArguments(proxyLocalFunRef, samSuperType, plainLambda = false, forceSerializability = false)
        if (proxyLambdaMetafactoryArguments is LambdaMetafactoryArguments) {
          return wrapSamConversionArgumentWithIndySamConversion(expression) { samType ->
            proxyLocalFunBlock.statements[proxyLocalFunBlock.statements.size - 1] =
              wrapWithIndySamConversion(samType, proxyLambdaMetafactoryArguments)
            proxyLocalFunBlock.type = samType
            proxyLocalFunBlock
          }
        }
      }
    }

    // Erase generic arguments in the SAM type, because they are not easy to approximate correctly otherwise,
    // and LambdaMetafactory also uses erased type.
    val erasedSamSuperType = samSuperType.erasedUpperBound.rawType(context)

    return FunctionReferenceBuilder(reference, erasedSamSuperType).build()
  }

  private fun createProxyLocalFunctionForIndySamConversion(reference: IrFunctionReference): IrBlock {
    val startOffset = reference.startOffset
    val endOffset = reference.endOffset
    val targetFun = reference.symbol.owner

    // For a function reference with possibly bound value parameters
    //      [ dispatchReceiver = ..., extensionReceiver = ..., ... ]::foo
    // create a proxy wrapper:
    //      {
    //          val tmp_proxy_0 = <bound_argument_value_0>
    //          ...
    //          val tmp_proxy_N = <bound_argument_value_N>
    //          fun `$proxy`(p_0: TP_0, ..., p_M: TP_M): TR =
    //              foo(... arg_J ...)
    //              // here for each J arg_J is either 'tmp_proxy_K' or 'p_K' for some K
    //          ::`$proxy`
    //      }

    val temporaryVals = ArrayList<IrVariable>()

    val targetCall: IrFunctionAccessExpression =
      when (targetFun) {
        is IrSimpleFunction ->
          IrCallImpl.fromSymbolOwner(startOffset, endOffset, targetFun.symbol)
        is IrConstructor ->
          IrConstructorCallImpl.fromSymbolOwner(startOffset, endOffset, targetFun.returnType, targetFun.symbol)
      }

    val proxyFun = context.irFactory.buildFun {
      name = Name.identifier("${targetFun.name.asString()}__proxy")
      returnType = targetFun.returnType.eraseTypeParameters()
      visibility = DescriptorVisibilities.LOCAL
      modality = Modality.FINAL
      isSuspend = false
      isInline = false
      origin =
        if (targetFun.isInline || targetFun.isArrayOf())
          JvmLoweredDeclarationOrigin.PROXY_FUN_FOR_METAFACTORY
        else
          JvmLoweredDeclarationOrigin.SYNTHETIC_PROXY_FUN_FOR_METAFACTORY
    }.also { proxyFun ->
      proxyFun.parent = currentDeclarationParent
        ?: throw AssertionError("No declaration parent when processing $reference")

      var temporaryValIndex = 0
      var proxyParameterIndex = 0

      fun addAndGetTemporaryVal(initializer: IrExpression): IrGetValue {
        val tmpVal = IrVariableImpl(
          startOffset, endOffset,
          IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
          IrVariableSymbolImpl(),
          Name.identifier("tmp_proxy_${temporaryValIndex++}"),
          initializer.type,
          isVar = false, isConst = false, isLateinit = false
        )
        tmpVal.initializer = initializer
        tmpVal.parent = proxyFun.parent
        temporaryVals.add(tmpVal)
        return IrGetValueImpl(startOffset, endOffset, tmpVal.symbol)
      }

      fun addAndGetProxyValueParameter(originalParameter: IrValueParameter): IrGetValue {
        val proxyParameter = buildValueParameter(proxyFun) {
          updateFrom(originalParameter)
          name = Name.identifier("p$proxyParameterIndex\$${originalParameter.name.asString()}")
          type = originalParameter.type.eraseTypeParameters()
          proxyParameterIndex++
        }.apply {
          parent = proxyFun
        }
        proxyFun.valueParameters += proxyParameter
        return IrGetValueImpl(startOffset, endOffset, proxyParameter.symbol)
      }

      fun getTargetCallArgument(boundValue: IrExpression?, originalParameter: IrValueParameter?): IrExpression? =
        when {
          boundValue != null ->
            addAndGetTemporaryVal(boundValue)
          originalParameter != null ->
            addAndGetProxyValueParameter(originalParameter)
          else ->
            null
        }

      targetCall.dispatchReceiver = getTargetCallArgument(reference.dispatchReceiver, targetFun.dispatchReceiverParameter)
      targetCall.extensionReceiver = getTargetCallArgument(reference.extensionReceiver, targetFun.extensionReceiverParameter)
      for ((valueParameterIndex, valueParameter) in targetFun.valueParameters.withIndex()) {
        targetCall.putValueArgument(
          valueParameterIndex,
          getTargetCallArgument(
            reference.getValueArgument(valueParameterIndex),
            valueParameter
          )
        )
      }

      for (typeParameterIndex in targetFun.typeParameters.indices) {
        targetCall.typeArguments[typeParameterIndex] = reference.typeArguments[typeParameterIndex]
      }

      val proxyFunBody = context.irFactory.createBlockBody(startOffset, endOffset).also { proxyFun.body = it }
      when {
        targetFun.returnType.isUnit() -> {
          proxyFunBody.statements.add(targetCall)
        }
        else -> {
          proxyFunBody.statements.add(
            IrReturnImpl(
              startOffset, endOffset,
              context.irBuiltIns.nothingType,
              proxyFun.symbol,
              targetCall
            )
          )
        }
      }
    }

    val proxyFunRef = IrFunctionReferenceImpl(
      startOffset, endOffset,
      reference.type,
      proxyFun.symbol,
      0 // TODO generic function reference?
    )

    return IrBlockImpl(
      startOffset, endOffset,
      reference.type,
      origin = null,
      temporaryVals + proxyFun + proxyFunRef
    )
  }

  private fun canGenerateIndySamConversionOnFunctionalExpression(samSuperType: IrType, expression: IrExpression): Boolean {
    val samClass = samSuperType.classOrNull
      ?: throw AssertionError("Class type expected: ${samSuperType.render()}")
    if (!samClass.owner.isFromJava() || isJavaSamConversionWithEqualsHashCode)
      return false
    if (expression is IrBlock && expression.origin == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE)
      return false
    return true
  }

  private fun wrapSamDelegatingLambdaWithIndySamConversion(
    samSuperType: IrType,
    lambdaBlock: SamDelegatingLambdaBlock,
    lambdaMetafactoryArguments: LambdaMetafactoryArguments,
  ): IrExpression {
    val indySamConversion = wrapWithIndySamConversion(samSuperType, lambdaMetafactoryArguments)
    lambdaBlock.replaceRefWith(indySamConversion)
    return lambdaBlock.block
  }

  private fun wrapSamConversionArgumentWithIndySamConversion(
    expression: IrTypeOperatorCall,
    produceSamConversion: (IrType) -> IrExpression,
  ): IrExpression {
    val samType = expression.typeOperand
    return when (val argument = expression.argument) {
      is IrFunctionReference ->
        produceSamConversion(samType)
      is IrBlock ->
        wrapFunctionReferenceInsideBlockWithIndySamConversion(samType, argument, produceSamConversion)
      else -> throw AssertionError("Block or function reference expected: ${expression.render()}")
    }
  }

  private fun wrapFunctionReferenceInsideBlockWithIndySamConversion(
    samType: IrType,
    block: IrBlock,
    produceSamConversion: (IrType) -> IrExpression,
  ): IrExpression {
    val indySamConversion = produceSamConversion(samType)
    block.statements[block.statements.size - 1] = indySamConversion
    block.type = indySamConversion.type
    return block
  }

  private val jvmIndyLambdaMetafactoryIntrinsic = context.ir.symbols.indyLambdaMetafactoryIntrinsic

  private val specialNullabilityAnnotationsFqNames =
    setOf(
      JvmSymbols.FLEXIBLE_NULLABILITY_ANNOTATION_FQ_NAME,
      JvmAnnotationNames.ENHANCED_NULLABILITY_ANNOTATION,
    )

  private fun wrapWithIndySamConversion(
    samType: IrType,
    lambdaMetafactoryArguments: LambdaMetafactoryArguments,
    startOffset: Int = UNDEFINED_OFFSET,
    endOffset: Int = UNDEFINED_OFFSET,
  ): IrCall {
    val notNullSamType = samType.makeNotNull()
      .removeAnnotations { it.type.classFqName in specialNullabilityAnnotationsFqNames }
    return context.createJvmIrBuilder(currentScope!!, startOffset, endOffset).run {
      // See [org.jetbrains.kotlin.backend.jvm.JvmSymbols::indyLambdaMetafactoryIntrinsic].
      irCall(jvmIndyLambdaMetafactoryIntrinsic, notNullSamType).apply {
        typeArguments[0] = notNullSamType
        putValueArgument(0, irRawFunctionRef(lambdaMetafactoryArguments.samMethod))
        putValueArgument(1, lambdaMetafactoryArguments.implMethodReference)
        putValueArgument(2, irRawFunctionRef(lambdaMetafactoryArguments.fakeInstanceMethod))
        putValueArgument(3, irVarargOfRawFunctionRefs(lambdaMetafactoryArguments.extraOverriddenMethods))
        putValueArgument(4, irBoolean(lambdaMetafactoryArguments.shouldBeSerializable))
      }
    }
  }

  private fun IrBuilderWithScope.irRawFunctionRef(irFun: IrFunction) =
    irRawFunctionReference(context.irBuiltIns.anyType, irFun.symbol)

  private fun IrBuilderWithScope.irVarargOfRawFunctionRefs(irFuns: List<IrFunction>) =
    irVararg(context.irBuiltIns.anyType, irFuns.map { irRawFunctionRef(it) })

  private inner class FunctionReferenceBuilder(val irFunctionReference: IrFunctionReference, val samSuperType: IrType? = null) {
    private val isLambda = irFunctionReference.origin.isLambda
    private val isLightweightLambda = isLambda
      && shouldGenerateLightweightLambdas
      && !irFunctionReference.symbol.owner.hasAnnotation(JVM_SERIALIZABLE_LAMBDA_ANNOTATION_FQ_NAME)
    private val isHeavyweightLambda = isLambda && !isLightweightLambda
    private val callee = irFunctionReference.symbol.owner

    // Only function references can bind a receiver and even then we can only bind either an extension or a dispatch receiver.
    // However, when we bind a value of an inline class type as a receiver, the receiver will turn into an argument of
    // the function in question. Yet we still need to record it as the "receiver" in CallableReference in order for reflection
    // to work correctly.
    private val boundReceiver: Pair<IrValueParameter, IrExpression>? =
      if (callee.isJvmStaticInObject()) createFakeBoundReceiverForJvmStaticInObject()
      else irFunctionReference.getArgumentsWithIr().singleOrNull()

    // The type of the reference is KFunction<in A1, ..., in An, out R>
    private val parameterTypes = (irFunctionReference.type as IrSimpleType).arguments.map {
      when (it) {
        is IrTypeProjection -> it.type
        is IrStarProjection -> context.irBuiltIns.anyNType
      }
    }
    private val argumentTypes = parameterTypes.dropLast(1)
    private val referenceReturnType = parameterTypes.last()

    private val typeArgumentsMap = irFunctionReference.typeSubstitutionMap

    private val functionSuperClass =
      samSuperType?.classOrNull
        ?: if (irFunctionReference.isSuspend)
          context.irBuiltIns.suspendFunctionN(argumentTypes.size).symbol
        else
          context.irBuiltIns.functionN(argumentTypes.size).symbol
    private val superMethod =
      functionSuperClass.owner.getSingleAbstractMethod()
        ?: throw AssertionError("Not a SAM class: ${functionSuperClass.owner.render()}")

    private val adapteeCall: IrFunctionAccessExpression? =
      if (callee.origin == IrDeclarationOrigin.ADAPTER_FOR_CALLABLE_REFERENCE) {
        // The body of a callable reference adapter contains either only a call, or an IMPLICIT_COERCION_TO_UNIT type operator
        // applied to a call. That call's target is the original function which we need to get owner/name/signature.
        val call = when (val statement = callee.body!!.statements.single()) {
          is IrTypeOperatorCall -> {
            assert(statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) {
              "Unexpected type operator in ADAPTER_FOR_CALLABLE_REFERENCE: ${callee.render()}"
            }
            statement.argument
          }
          is IrReturn -> statement.value
          else -> statement
        }
        if (call !is IrFunctionAccessExpression) {
          throw UnsupportedOperationException("Unknown structure of ADAPTER_FOR_CALLABLE_REFERENCE: ${callee.render()}")
        }
        call
      } else {
        null
      }

    private val adaptedReferenceOriginalTarget: IrFunction? = adapteeCall?.symbol?.owner
    private val isFunInterfaceConstructorReference =
      callee.origin == IrDeclarationOrigin.ADAPTER_FOR_FUN_INTERFACE_CONSTRUCTOR
    private val constructedFunInterfaceSymbol: IrClassSymbol? =
      if (isFunInterfaceConstructorReference)
        callee.returnType.classOrNull
          ?: throw AssertionError("Fun interface type expected: ${callee.returnType.render()}")
      else
        null
    private val isAdaptedReference =
      isFunInterfaceConstructorReference || adaptedReferenceOriginalTarget != null

    private val samInterface = samSuperType?.getClass()
    private val isKotlinFunInterface = samInterface != null && !samInterface.isFromJava()

    private val needToGenerateSamEqualsHashCodeMethods =
      (isKotlinFunInterface || isJavaSamConversionWithEqualsHashCode) &&
        (isAdaptedReference || !isLambda)

    private val superType =
      samSuperType
        ?: when {
          isLightweightLambda -> context.ir.symbols.any
          isHeavyweightLambda -> context.ir.symbols.lambdaClass
          isFunInterfaceConstructorReference -> context.ir.symbols.funInterfaceConstructorReferenceClass
          else -> when {
            isAdaptedReference -> context.ir.symbols.adaptedFunctionReference
            else -> context.ir.symbols.functionReferenceImpl
          }
        }.defaultType

    private val functionReferenceClass = context.irFactory.buildClass {
      setSourceRange(irFunctionReference)
      visibility = DescriptorVisibilities.LOCAL
      // A callable reference results in a synthetic class, while a lambda is not synthetic.
      // We don't produce GENERATED_SAM_IMPLEMENTATION, which is always synthetic.
      origin = if (isLambda) JvmLoweredDeclarationOrigin.LAMBDA_IMPL else JvmLoweredDeclarationOrigin.FUNCTION_REFERENCE_IMPL
      name = SpecialNames.NO_NAME_PROVIDED
    }.apply {
      parent = currentDeclarationParent ?: error("No current declaration parent at ${irFunctionReference.dump()}")
      superTypes = listOfNotNull(
        superType,
        if (samSuperType == null)
          functionSuperClass.typeWith(parameterTypes)
        else null,
        if (needToGenerateSamEqualsHashCodeMethods)
          context.ir.symbols.functionAdapter.defaultType
        else null,
      )
      if (samInterface != null && origin == JvmLoweredDeclarationOrigin.LAMBDA_IMPL) {
        // Old back-end generates formal type parameters as in SAM supertype.
        // Here we create formal type parameters with same names and equivalent upper bounds.
        // We don't really perform any type substitutions within class body
        // (it's all fine as soon as we have required generic signatures and don't fail anywhere).
        // NB this would no longer matter if we generate SAM wrapper classes as synthetic.
        typeParameters = createFakeFormalTypeParameters(samInterface.typeParameters, this)
      }
      createThisReceiverParameter()
      copyAttributes(irFunctionReference)
      if (isHeavyweightLambda) {
        metadata = irFunctionReference.symbol.owner.metadata
      }
    }

    private fun createFakeFormalTypeParameters(sourceTypeParameters: List<IrTypeParameter>, irClass: IrClass): List<IrTypeParameter> {
      if (sourceTypeParameters.isEmpty()) return emptyList()

      val fakeTypeParameters = sourceTypeParameters.map {
        buildTypeParameter(irClass) {
          updateFrom(it)
          name = it.name
        }
      }
      val typeRemapper = IrTypeParameterRemapper(sourceTypeParameters.associateWith { fakeTypeParameters[it.index] })
      for (fakeTypeParameter in fakeTypeParameters) {
        val sourceTypeParameter = sourceTypeParameters[fakeTypeParameter.index]
        fakeTypeParameter.superTypes = sourceTypeParameter.superTypes.map { typeRemapper.remapType(it) }
      }

      return fakeTypeParameters
    }

    fun build(): IrExpression = context.createJvmIrBuilder(currentScope!!).run {
      irBlock(irFunctionReference.startOffset, irFunctionReference.endOffset) {
        val constructor = createConstructor()
        val boundReceiverVar =
          if (samSuperType != null && boundReceiver != null) {
            irTemporary(boundReceiver.second)
          } else null
        createInvokeMethod(boundReceiverVar)

        if (needToGenerateSamEqualsHashCodeMethods) {
          generateSamEqualsHashCodeMethods(boundReceiverVar)
        }
        if (isKotlinFunInterface) {
          functionReferenceClass.addFakeOverrides(backendContext.typeSystem)
        }

        +functionReferenceClass
        +irCall(constructor.symbol).apply {
          if (valueArgumentsCount > 0) putValueArgument(0, boundReceiver!!.second)
        }
      }
    }

    private fun JvmIrBuilder.generateSamEqualsHashCodeMethods(boundReceiverVar: IrVariable?) {
      checkNotNull(samSuperType) { "equals/hashCode can only be generated for fun interface wrappers: ${callee.render()}" }

      SamEqualsHashCodeMethodsGenerator(backendContext, functionReferenceClass, samSuperType) {
        val internalClass = when {
          isAdaptedReference -> backendContext.ir.symbols.adaptedFunctionReference
          else -> backendContext.ir.symbols.functionReferenceImpl
        }
        val constructor = internalClass.owner.constructors.single {
          // arity, [receiver], owner, name, signature, flags
          it.valueParameters.size == 1 + (if (boundReceiver != null) 1 else 0) + 4
        }
        irCallConstructor(constructor.symbol, emptyList()).apply {
          generateConstructorCallArguments(this) { irGet(boundReceiverVar!!) }
        }
      }.generate()
    }

    private fun createConstructor(): IrConstructor =
      functionReferenceClass.addConstructor {
        origin = JvmLoweredDeclarationOrigin.GENERATED_MEMBER_IN_CALLABLE_REFERENCE
        returnType = functionReferenceClass.defaultType
        isPrimary = true
      }.apply {
        if (samSuperType == null && boundReceiver != null) {
          addValueParameter("receiver", context.irBuiltIns.anyNType)
        }

        // Super constructor:
        // - For fun interface constructor references, super class is kotlin.jvm.internal.FunInterfaceConstructorReference
        //   with single constructor 'public FunInterfaceConstructorReference(Class funInterface)'
        // - For SAM references, the super class is Any
        // - For lambdas, accepts arity
        // - For optimized function references (1.4+), accepts:
        //       arity, [receiver], owner, name, signature, flags
        // - For unoptimized function references, accepts:
        //       arity, [receiver]
        val constructor =
          when {
            isFunInterfaceConstructorReference ->
              context.ir.symbols.funInterfaceConstructorReferenceClass.owner.constructors.single()
            samSuperType != null ->
              context.irBuiltIns.anyClass.owner.constructors.single()
            else -> {
              val expectedArity =
                if (isLightweightLambda && !isAdaptedReference) 0
                else if (isHeavyweightLambda && !isAdaptedReference) 1
                else 1 + (if (boundReceiver != null) 1 else 0) + 4
              superType.getClass()!!.constructors.single {
                it.valueParameters.size == expectedArity
              }
            }
          }

        body = context.createJvmIrBuilder(symbol).run {
          irBlockBody(startOffset, endOffset) {
            +irDelegatingConstructorCall(constructor).also { call ->
              if (samSuperType == null) {
                generateConstructorCallArguments(call) { irGet(valueParameters.first()) }
              }
            }
            +IrInstanceInitializerCallImpl(startOffset, endOffset, functionReferenceClass.symbol, context.irBuiltIns.unitType)
          }
        }
      }

    private fun JvmIrBuilder.generateConstructorCallArguments(
      call: IrFunctionAccessExpression,
      generateBoundReceiver: IrBuilder.() -> IrExpression,
    ) {
      if (isFunInterfaceConstructorReference) {
        val funInterfaceKClassRef = kClassReference(constructedFunInterfaceSymbol!!.owner.defaultType)
        val funInterfaceJavaClassRef = kClassToJavaClass(funInterfaceKClassRef)
        call.putValueArgument(0, funInterfaceJavaClassRef)
      } else {
        var index = 0
        if (!isLightweightLambda) {
          call.putValueArgument(index++, irInt(argumentTypes.size + if (irFunctionReference.isSuspend) 1 else 0))
        }
        if (boundReceiver != null) {
          call.putValueArgument(index++, generateBoundReceiver())
        }
        if (!isLambda) {
          val callableReferenceTarget = adaptedReferenceOriginalTarget ?: callee
          val owner = calculateOwnerKClass(callableReferenceTarget.parent)
          call.putValueArgument(index++, kClassToJavaClass(owner))
          call.putValueArgument(index++, irString(callableReferenceTarget.originalName.asString()))
          call.putValueArgument(index++, generateSignature(callableReferenceTarget.symbol))
          call.putValueArgument(index, irInt(getFunctionReferenceFlags(callableReferenceTarget)))
        }
      }
    }

    private fun getFunctionReferenceFlags(callableReferenceTarget: IrFunction): Int {
      val isTopLevelBit = callableReferenceTarget.getCallableReferenceTopLevelFlag()
      val adaptedCallableReferenceFlags = getAdaptedCallableReferenceFlags()
      return isTopLevelBit + (adaptedCallableReferenceFlags shl 1)
    }

    private fun getAdaptedCallableReferenceFlags(): Int {
      if (adaptedReferenceOriginalTarget == null) return 0

      val isVarargMappedToElementBit = if (hasVarargMappedToElement()) 1 else 0
      val isSuspendConvertedBit = if (!adaptedReferenceOriginalTarget.isSuspend && callee.isSuspend) 1 else 0
      val isCoercedToUnitBit = if (!adaptedReferenceOriginalTarget.returnType.isUnit() && callee.returnType.isUnit()) 1 else 0

      return isVarargMappedToElementBit +
        (isSuspendConvertedBit shl 1) +
        (isCoercedToUnitBit shl 2)
    }

    private fun hasVarargMappedToElement(): Boolean {
      if (adapteeCall == null) return false
      for (i in 0 until adapteeCall.valueArgumentsCount) {
        val arg = adapteeCall.getValueArgument(i) ?: continue
        if (arg !is IrVararg) continue
        for (varargElement in arg.elements) {
          if (varargElement is IrGetValue) return true
        }
      }
      return false
    }

    private fun createInvokeMethod(receiverVar: IrValueDeclaration?): IrSimpleFunction =
      functionReferenceClass.addFunction {
        setSourceRange(if (isLambda) callee else irFunctionReference)
        name = superMethod.name
        returnType = referenceReturnType
        isSuspend = callee.isSuspend
      }.apply {
        metadata = functionReferenceClass.metadata
        overriddenSymbols += superMethod.symbol
        dispatchReceiverParameter = buildReceiverParameter {
          origin = IrDeclarationOrigin.INSTANCE_RECEIVER
          type = functionReferenceClass.symbol.defaultType
        }

        when {
          isLambda ->
            createLambdaInvokeMethod()
          isFunInterfaceConstructorReference ->
            createFunInterfaceConstructorInvokeMethod()
          else ->
            createFunctionReferenceInvokeMethod(receiverVar)
        }
      }

    // Inline the body of an anonymous function into the generated lambda subclass.
    private fun IrSimpleFunction.createLambdaInvokeMethod() {
      annotations += callee.annotations
      val valueParameterMap = callee.parameters.associate { param ->
        param to param.copyTo(this)
      }
      valueParameters += valueParameterMap.values
      body = callee.moveBodyTo(this, valueParameterMap)
    }

    private fun IrSimpleFunction.createFunInterfaceConstructorInvokeMethod() {
      val adapterValueParameter = callee.valueParameters.singleOrNull()
        ?: throw AssertionError("Single value parameter expected: ${callee.render()}")
      val invokeValueParameter = adapterValueParameter.copyTo(this)
      val valueParameterMap = mapOf(adapterValueParameter to invokeValueParameter)
      valueParameters = listOf(invokeValueParameter)
      body = callee.moveBodyTo(this, valueParameterMap)
      callee.body = null
    }

    private fun IrSimpleFunction.createFunctionReferenceInvokeMethod(receiver: IrValueDeclaration?) {
      for ((index, argumentType) in argumentTypes.withIndex()) {
        addValueParameter {
          name = Name.identifier("p$index")
          type = argumentType
        }
      }

      body = context.createJvmIrBuilder(symbol, startOffset, endOffset).run {
        var unboundIndex = 0
        val call = irCall(callee.symbol, referenceReturnType).apply {
          for (typeParameter in irFunctionReference.symbol.owner.allTypeParameters) {
            typeArguments[typeParameter.index] = typeArgumentsMap[typeParameter.symbol]
          }

          for (parameter in callee.parameters) {
            when {
              boundReceiver?.first == parameter ->
                // Bound receiver parameter. For function references, this is stored in a field of the superclass.
                // For sam references, we just capture the value in a local variable and LocalDeclarationsLowering
                // will put it into a field.
                if (samSuperType == null)
                  irImplicitCast(
                    irGetField(
                      irGet(dispatchReceiverParameter!!),
                      functionReferenceClass.getReceiverField(backendContext)
                    ),
                    boundReceiver.second.type
                  )
                else
                  irGet(receiver!!)

              unboundIndex >= argumentTypes.size ->
                // Default value argument (this pass doesn't handle suspend functions, otherwise
                // it could also be the continuation argument)
                null

              else ->
                irGet(valueParameters[unboundIndex++])
            }?.let { putArgument(callee, parameter, it) }
          }
        }
        irExprBody(
          inlineAdapterCallIfPossible(call, this@createFunctionReferenceInvokeMethod)
        )
      }
    }

    private fun inlineAdapterCallIfPossible(
      expression: IrFunctionAccessExpression,
      invokeMethod: IrSimpleFunction,
    ): IrExpression {
      val irCall = expression as? IrCall
        ?: return expression
      val callee = irCall.symbol.owner
      if (callee.origin != IrDeclarationOrigin.ADAPTER_FOR_CALLABLE_REFERENCE)
        return expression

      // TODO fix testSuspendUnitConversion
      if (callee.isSuspend) return expression

      // Callable reference adapter is a simple function that delegates to callable reference target,
      // adapting its signature for required functional type.
      // Usually it simply forwards arguments to target function.
      // It also passes 'receiver' field for bound references, with downcast to the actual receiver type.
      // In any case, adapter itself is synthetic and is not necessarily debuggable, so we can reuse variables freely.
      // Inlining adapter into 'invoke' saves us two methods (adapter & synthetic accessor).
      val adapterBody = callee.body as? IrBlockBody
      if (adapterBody == null || adapterBody.statements.size != 1)
        throw AssertionError("Unexpected adapter body: ${callee.dump()}")
      val resultStatement = adapterBody.statements[0]
      val resultExpression: IrExpression =
        when {
          resultStatement is IrReturn ->
            resultStatement.value
          resultStatement is IrTypeOperatorCall && resultStatement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT ->
            resultStatement
          resultStatement is IrCall ->
            resultStatement
          resultStatement is IrConstructorCall ->
            resultStatement
          else ->
            throw AssertionError("Unexpected adapter body: ${callee.dump()}")
        }

      val startOffset = irCall.startOffset
      val endOffset = irCall.endOffset

      val callArguments = LinkedHashMap<IrValueParameter, IrValueDeclaration>()
      val inlinedAdapterBlock = IrBlockImpl(startOffset, endOffset, irCall.type, origin = null)
      var tmpVarIndex = 0

      fun wrapIntoTemporaryVariableIfNecessary(expression: IrExpression): IrValueDeclaration {
        if (expression is IrGetValue)
          return expression.symbol.owner
        if (expression !is IrTypeOperatorCall || expression.argument !is IrGetField)
          throw AssertionError("Unexpected adapter argument:\n${expression.dump()}")
        val temporaryVar = IrVariableImpl(
          startOffset, endOffset, IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
          IrVariableSymbolImpl(),
          Name.identifier("tmp_${tmpVarIndex++}"),
          expression.type,
          isVar = false, isConst = false, isLateinit = false
        )
        temporaryVar.parent = invokeMethod
        temporaryVar.initializer = expression
        inlinedAdapterBlock.statements.add(temporaryVar)
        return temporaryVar
      }

      callee.dispatchReceiverParameter?.let {
        callArguments[it] = wrapIntoTemporaryVariableIfNecessary(
          irCall.dispatchReceiver
            ?: throw AssertionError("No dispatch receiver in adapter call: ${irCall.dump()}")
        )
      }
      callee.extensionReceiverParameter?.let {
        callArguments[it] = wrapIntoTemporaryVariableIfNecessary(
          irCall.extensionReceiver
            ?: throw AssertionError("No extension receiver in adapter call: ${irCall.dump()}")
        )
      }
      for (valueParameter in callee.valueParameters) {
        callArguments[valueParameter] = wrapIntoTemporaryVariableIfNecessary(
          irCall.getValueArgument(valueParameter.indexInOldValueParameters)
            ?: throw AssertionError("No value argument #${valueParameter.indexInOldValueParameters} in adapter call: ${irCall.dump()}")
        )
      }

      val inlinedAdapterResult = resultExpression.transform(VariableRemapper(callArguments), null)
      inlinedAdapterBlock.statements.add(inlinedAdapterResult)

      callee.body = null
      return inlinedAdapterBlock.patchDeclarationParents(invokeMethod)
    }

    private val IrFunction.originalName: Name
      get() = metadata?.name ?: name

    private fun JvmIrBuilder.generateSignature(target: IrFunctionSymbol): IrExpression =
      irCall(backendContext.ir.symbols.signatureStringIntrinsic).apply {
        putValueArgument(
          0,
          //don't pass receivers otherwise LocalDeclarationLowering will create additional captured parameters
          IrFunctionReferenceImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, irFunctionReference.type, target,
            irFunctionReference.typeArguments.size,
            irFunctionReference.reflectionTarget, null
          ).apply {
            copyTypeArgumentsFrom(irFunctionReference)
          }
        )
      }

    private fun createFakeBoundReceiverForJvmStaticInObject(): Pair<IrValueParameter, IrGetObjectValueImpl> {
      // JvmStatic functions in objects are special in that they are generated as static methods in the bytecode, and JVM IR lowers
      // both declarations and call sites early on in jvmStaticInObjectPhase because it's easier that way in subsequent lowerings.
      // However from the point of view of Kotlin language (and thus reflection), these functions still take the dispatch receiver
      // parameter of the object type. So we pretend here that a JvmStatic function in object has an additional dispatch receiver
      // parameter, so that the correct function reference object will be created and reflective calls will work at runtime.
      val objectClass = callee.parentAsClass
      return buildValueParameter(callee) {
        name = Name.identifier("\$this")
        type = objectClass.typeWith()
      } to IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, objectClass.typeWith(), objectClass.symbol)
    }
  }

  companion object {
    internal fun JvmIrBuilder.calculateOwnerKClass(irContainer: IrDeclarationParent): IrExpression =
      kClassReference(irContainer.getCallableReferenceOwnerKClassType(backendContext))

    // This method creates an IrField for the field `kotlin.jvm.internal.CallableReference.receiver`, as if this field was declared
    // in the anonymous class for this callable reference. Technically it's incorrect because this field is not declared here. It would
    // be more correct to create a fake override but that seems like more work for no clear benefit. Codegen will generate the correct
    // field access anyway, even if the field is not present in this parent.
    // Note that it is necessary to generate an access to the field whose parent is this anonymous class, and NOT some supertype like
    // k.j.i.CallableReference, or k.j.i.FunctionReferenceImpl, because then AddSuperQualifierToJavaFieldAccess lowering would add
    // superQualifierSymbol, which would break inlining of bound function references, since inliner will not understand how to transform
    // this getfield instruction in the bytecode.
    internal fun IrClass.getReceiverField(context: JvmBackendContext): IrField =
      context.irFactory.buildField {
        name = Name.identifier("receiver")
        type = context.irBuiltIns.anyNType
        visibility = DescriptorVisibilities.PROTECTED
      }.apply {
        parent = this@getReceiverField
      }
  }
}
