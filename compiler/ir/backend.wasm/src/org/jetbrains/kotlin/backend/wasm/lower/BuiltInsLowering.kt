/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.lower.calls.EnumIntrinsicsUtils
import org.jetbrains.kotlin.ir.backend.js.utils.isEqualsInheritedFromAny
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irComposite
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfNull
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.putClassTypeArgument
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getArrayElementType
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.parentOrNull

class BuiltInsLowering(val context: WasmBackendContext) : FileLoweringPass {
  private val irBuiltins = context.irBuiltIns
  private val symbols = context.wasmSymbols

  private fun IrType.findEqualsMethod(): IrSimpleFunction {
    val klass = getClass() ?: irBuiltins.anyClass.owner
    return klass.functions.single { it.isEqualsInheritedFromAny() }
  }

  private fun transformCall(
    call: IrCall,
    builder: DeclarationIrBuilder,
  ): IrExpression {
    when (val symbol = call.symbol) {
      irBuiltins.linkageErrorSymbol -> {
        return irCall(call, context.wasmSymbols.throwLinkageError)
      }
      irBuiltins.ieee754equalsFunByOperandType[irBuiltins.floatClass] -> {
        if (call.arguments[0]!!.type.isNullable() || call.arguments[1]!!.type.isNullable()) {
          return irCall(call, symbols.nullableFloatIeee754Equals)
        }
        return irCall(call, symbols.floatEqualityFunctions.getValue(irBuiltins.floatType))
      }
      irBuiltins.ieee754equalsFunByOperandType[irBuiltins.doubleClass] -> {
        if (call.arguments[0]!!.type.isNullable() || call.arguments[1]!!.type.isNullable()) {
          return irCall(call, symbols.nullableDoubleIeee754Equals)
        }
        return irCall(call, symbols.floatEqualityFunctions.getValue(irBuiltins.doubleType))
      }
      irBuiltins.eqeqSymbol,
      irBuiltins.eqeqeqSymbol,
        -> {
        fun callRefIsNull(expr: IrExpression): IrCall {
          if (
            !context.isWasmJsTarget &&
            expr.type.erasedUpperBound.isExternal
          ) {
            error("Unexpected external refs in wasi mode")
          }
          val refIsNull = if (expr.type.erasedUpperBound.isExternal) symbols.jsRelatedSymbols.externRefIsNull else symbols.refIsNull
          return builder.irCall(refIsNull).apply { arguments[0] = expr }
        }

        val lhs = call.arguments[0]!!
        val rhs = call.arguments[1]!!

        if (lhs.isNullConst()) return callRefIsNull(rhs)

        if (rhs.isNullConst()) return callRefIsNull(lhs)

        val lhsType = lhs.type
        val rhsType = rhs.type

        if (lhsType == rhsType) {
          val newSymbol =
            symbols.equalityFunctions[lhsType]
            // For eqeqeqSymbol try to use more efficient comparison if type is Double or Float.
            // But for eqeqSymbol we have to use generic comparison for floating point numbers.
              ?: if (call.symbol === irBuiltins.eqeqeqSymbol) symbols.floatEqualityFunctions[lhsType] else null

          if (newSymbol != null) {
            return irCall(call, newSymbol)
          }
        }

        // For eqeqSymbol use overridden `Any.equals(Any?)` if there is any.
        if (call.symbol === irBuiltins.eqeqSymbol && !lhsType.isNullable() && !lhsType.isNothing()) {
          return irCall(call, lhsType.findEqualsMethod().symbol, argumentsAsReceivers = true)
        }

        val fallbackEqFun = if (call.symbol === irBuiltins.eqeqeqSymbol) symbols.refEq else symbols.nullableEquals
        return irCall(call, fallbackEqFun)
      }

      irBuiltins.checkNotNullSymbol -> {
        val arg = call.arguments[0]!!

        if (arg.isNullConst()) {
          return builder.irCall(symbols.throwNullPointerException)
        }

        return builder.irComposite {
          val temporary = irTemporary(arg)
          +builder.irIfNull(
            type = arg.type.makeNotNull(),
            subject = irGet(temporary),
            thenPart = builder.irCall(symbols.throwNullPointerException),
            elsePart = irGet(temporary)
          )
        }
      }
      in symbols.comparisonBuiltInsToWasmIntrinsics.keys -> {
        val newSymbol = symbols.comparisonBuiltInsToWasmIntrinsics[symbol]!!
        return irCall(call, newSymbol)
      }

      irBuiltins.noWhenBranchMatchedExceptionSymbol ->
        return builder.irCall(symbols.throwNoBranchMatchedException, irBuiltins.nothingType)

      irBuiltins.illegalArgumentExceptionSymbol ->
        return builder.irCall(symbols.throwIAE, irBuiltins.nothingType).apply {
          arguments[0] = call.arguments[0]!!
        }

      irBuiltins.dataClassArrayMemberHashCodeSymbol, irBuiltins.dataClassArrayMemberToStringSymbol -> {
        val argument = call.arguments[0]!!
        val argumentType = argument.type
        val overloadSymbol: IrSimpleFunctionSymbol
        val returnType: IrType
        if (symbol == irBuiltins.dataClassArrayMemberHashCodeSymbol) {
          overloadSymbol = symbols.findContentHashCodeOverload(argumentType)
          returnType = irBuiltins.intType
        } else {
          overloadSymbol = symbols.findContentToStringOverload(argumentType)
          returnType = irBuiltins.stringType
        }

        return builder.irCall(
          overloadSymbol,
          returnType,
        ).apply {
          val extensionIndex = overloadSymbol.owner.parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
          arguments[extensionIndex] = argument
          if (argumentType.classOrNull == irBuiltins.arrayClass) {
            typeArguments[0] = argumentType.getArrayElementType(irBuiltins)
          }
        }
      }
      in symbols.startCoroutineUninterceptedOrReturnIntrinsics -> {
        val arity = symbols.startCoroutineUninterceptedOrReturnIntrinsics.indexOf(symbol)
        val newSymbol = irBuiltins.suspendFunctionN(arity).getSimpleFunction("invoke")!!
        return irCall(call, newSymbol, argumentsAsReceivers = true)
      }
      context.reflectionSymbols.getKClass -> {
        val type = call.typeArguments[0]!!
        val klass = type.classOrNull?.owner ?: error("Invalid type")

        val constructorArgument: IrExpression
        val kclassConstructor: IrConstructor
        if (klass.isEffectivelyExternal()) {
          check(context.isWasmJsTarget) { "External classes reflection in WASI mode are not supported" }
          kclassConstructor = symbols.jsRelatedSymbols.kExternalClassImpl.owner.constructors.first()
          constructorArgument = getExternalKClassCtorArgument(type, builder)
        } else {
          kclassConstructor = symbols.reflectionSymbols.kClassImpl.owner.constructors.first()
          constructorArgument = getKClassCtorArgument(type, builder)
        }

        return IrConstructorCallImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = kclassConstructor.returnType,
          symbol = kclassConstructor.symbol,
          typeArgumentsCount = 1,
          constructorTypeArgumentsCount = 0
        ).also {
          it.putClassTypeArgument(0, type)
          it.arguments[0] = constructorArgument
        }
      }

      symbols.enumValueOfIntrinsic ->
        return EnumIntrinsicsUtils.transformEnumValueOfIntrinsic(call)
      symbols.enumValuesIntrinsic ->
        return EnumIntrinsicsUtils.transformEnumValuesIntrinsic(call)
      symbols.enumEntriesIntrinsic ->
        return EnumIntrinsicsUtils.transformEnumEntriesIntrinsic(call)
    }

    return call
  }

  private fun getKClassCtorArgument(type: IrType, builder: DeclarationIrBuilder): IrExpression {
    val klass = type.classOrNull?.owner ?: error("Invalid type")

    val typeId = builder.irCall(symbols.wasmTypeId).also {
      it.typeArguments[0] = type
    }

    if (!klass.isInterface) {
      return builder.irCall(context.wasmSymbols.reflectionSymbols.getTypeInfoTypeDataByPtr).also {
        it.arguments[0] = typeId
      }
    } else {
      val fqName = type.classFqName!!
      val fqnShouldBeEmitted =
        context.configuration.languageVersionSettings.getFlag(AnalysisFlags.allowFullyQualifiedNameInKClass)
      val packageName = if (fqnShouldBeEmitted) fqName.parentOrNull()?.asString() ?: "" else ""
      val typeName = fqName.shortName().asString()

      return builder.irCallConstructor(symbols.reflectionSymbols.wasmTypeInfoData.constructors.first(), emptyList()).also {
        it.arguments[0] = typeId
        it.arguments[1] = packageName.toIrConst(context.irBuiltIns.stringType)
        it.arguments[2] = typeName.toIrConst(context.irBuiltIns.stringType)
      }
    }
  }

  private fun getExternalKClassCtorArgument(type: IrType, builder: DeclarationIrBuilder): IrExpression {
    val klass = type.classOrNull?.owner ?: error("Invalid type")
    check(klass.kind != ClassKind.INTERFACE) { "External interface must not be a class literal" }
    return builder.irCall(context.mapping.wasmGetJsClass[klass]!!)
  }

  override fun lower(irFile: IrFile) {
    val builder = context.createIrBuilder(irFile.symbol)
    irFile.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
      override fun visitCall(expression: IrCall): IrExpression {
        val newExpression = transformCall(expression, builder)
        newExpression.transformChildrenVoid(this)
        return newExpression
      }
    })
  }
}
