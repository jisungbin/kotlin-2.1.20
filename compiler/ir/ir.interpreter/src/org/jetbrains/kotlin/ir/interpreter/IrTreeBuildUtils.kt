/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter

import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.builtins.UnsignedType
import org.jetbrains.kotlin.constant.AnnotationValue
import org.jetbrains.kotlin.constant.ArrayValue
import org.jetbrains.kotlin.constant.BooleanValue
import org.jetbrains.kotlin.constant.ByteValue
import org.jetbrains.kotlin.constant.CharValue
import org.jetbrains.kotlin.constant.ConstantValue
import org.jetbrains.kotlin.constant.DoubleValue
import org.jetbrains.kotlin.constant.EnumValue
import org.jetbrains.kotlin.constant.FloatValue
import org.jetbrains.kotlin.constant.IntValue
import org.jetbrains.kotlin.constant.KClassValue
import org.jetbrains.kotlin.constant.LongValue
import org.jetbrains.kotlin.constant.NullValue
import org.jetbrains.kotlin.constant.ShortValue
import org.jetbrains.kotlin.constant.StringValue
import org.jetbrains.kotlin.constant.UByteValue
import org.jetbrains.kotlin.constant.UIntValue
import org.jetbrains.kotlin.constant.ULongValue
import org.jetbrains.kotlin.constant.UShortValue
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrEnumConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.getPrimitiveType
import org.jetbrains.kotlin.ir.types.getUnsignedType
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAllArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isAnnotation
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom
import org.jetbrains.kotlin.utils.exceptions.errorWithAttachment

internal val TEMP_CLASS_FOR_INTERPRETER = IrDeclarationOriginImpl("TEMP_CLASS_FOR_INTERPRETER")
internal val TEMP_FUNCTION_FOR_INTERPRETER = IrDeclarationOriginImpl("TEMP_FUNCTION_FOR_INTERPRETER")

@Deprecated("Please migrate to `org.jetbrains.kotlin.ir.util.toIrConst`", level = DeprecationLevel.HIDDEN)
fun Any?.toIrConst(irType: IrType, startOffset: Int = SYNTHETIC_OFFSET, endOffset: Int = SYNTHETIC_OFFSET): IrConst =
  toIrConst(irType, startOffset, endOffset)

internal fun IrFunction.createCall(origin: IrStatementOrigin? = null): IrCall {
  this as IrSimpleFunction
  return IrCallImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, returnType, symbol, typeParameters.size, origin)
}

internal fun IrConstructor.createConstructorCall(irType: IrType = returnType): IrConstructorCall {
  return IrConstructorCallImpl.fromSymbolOwner(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, irType, symbol)
}

internal fun IrValueDeclaration.createGetValue(): IrGetValue {
  return IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.type, this.symbol)
}

internal fun IrValueDeclaration.createTempVariable(): IrVariable {
  return IrVariableImpl(
    SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrDeclarationOrigin.IR_TEMPORARY_VARIABLE, IrVariableSymbolImpl(),
    this.name, this.type, isVar = false, isConst = false, isLateinit = false
  )
}

internal fun IrClass.createGetObject(): IrGetObjectValue {
  return IrGetObjectValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.defaultType, this.symbol)
}

internal fun IrFunction.createReturn(value: IrExpression): IrReturn {
  return IrReturnImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.returnType, this.symbol, value)
}

internal fun createTempFunction(
  name: Name,
  type: IrType,
  origin: IrDeclarationOrigin = TEMP_FUNCTION_FOR_INTERPRETER,
  visibility: DescriptorVisibility = DescriptorVisibilities.PUBLIC,
): IrSimpleFunction {
  return IrFactoryImpl.createSimpleFunction(
    startOffset = SYNTHETIC_OFFSET,
    endOffset = SYNTHETIC_OFFSET,
    origin = origin,
    name = name,
    visibility = visibility,
    isInline = false,
    isExpect = false,
    returnType = type,
    modality = Modality.FINAL,
    symbol = IrSimpleFunctionSymbolImpl(),
    isTailrec = false,
    isSuspend = false,
    isOperator = true,
    isInfix = false,
    isExternal = false,
  )
}

internal fun createTempClass(name: Name, origin: IrDeclarationOrigin = TEMP_CLASS_FOR_INTERPRETER): IrClass {
  return IrFactoryImpl.createClass(
    startOffset = SYNTHETIC_OFFSET,
    endOffset = SYNTHETIC_OFFSET,
    origin = origin,
    name = name,
    visibility = DescriptorVisibilities.PRIVATE,
    symbol = IrClassSymbolImpl(),
    kind = ClassKind.CLASS,
    modality = Modality.FINAL,
  )
}

internal fun IrFunction.createGetFieldOfCorrespondingProperty(): IrExpression {
  val backingField = this.property!!.backingField!!
  return backingField.createGetField(dispatchReceiverParameter)
}

internal fun IrField.createGetField(receiver: IrValueParameter? = null): IrGetField {
  return IrGetFieldImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.symbol, this.type, receiver?.createGetValue())
}

internal fun List<IrStatement>.wrapWithBlockBody(): IrBlockBody {
  return IrFactoryImpl.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this)
}

internal fun IrFunctionAccessExpression.shallowCopy(copyTypeArguments: Boolean = true): IrFunctionAccessExpression {
  return when (this) {
    is IrCall -> symbol.owner.createCall()
    is IrConstructorCall -> symbol.owner.createConstructorCall()
    is IrDelegatingConstructorCall -> IrDelegatingConstructorCallImpl.fromSymbolOwner(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, symbol)
    is IrEnumConstructorCall ->
      IrEnumConstructorCallImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, symbol, typeArguments.size)
  }.apply {
    if (copyTypeArguments) {
      this@shallowCopy.typeArguments.indices.forEach {
        typeArguments[it] = this@shallowCopy.typeArguments[it]
      }
    }
  }
}

internal fun IrBuiltIns.copyArgumentsPassingNullOnDefault(from: IrFunctionAccessExpression, into: IrFunctionAccessExpression) {
  into.arguments.assignFrom(from.arguments) { it ?: IrConstImpl.constNull(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.anyNType) }
}

internal fun IrBuiltIns.irEquals(arg1: IrExpression, arg2: IrExpression): IrCall {
  val equalsCall = this.eqeqSymbol.owner.createCall(IrStatementOrigin.EQEQ)
  equalsCall.arguments[0] = arg1
  equalsCall.arguments[1] = arg2
  return equalsCall
}

internal fun IrBuiltIns.irIfNullThenElse(nullableArg: IrExpression, ifTrue: IrExpression, ifFalse: IrExpression): IrWhen {
  val nullCondition = this.irEquals(nullableArg, IrConstImpl.constNull(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.anyNType))
  val trueBranch = IrBranchImpl(nullCondition, ifTrue) // use default
  val elseBranch = IrElseBranchImpl(IrConstImpl.constTrue(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, this.booleanType), ifFalse)

  return IrWhenImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, ifTrue.type).apply { branches += listOf(trueBranch, elseBranch) }
}

internal fun IrBuiltIns.emptyArrayConstructor(arrayType: IrType): IrConstructorCall {
  val arrayClass = arrayType.classOrNull!!.owner
  val constructor = arrayClass.constructors.firstOrNull { it.hasShape(regularParameters = 1) } ?: arrayClass.constructors.first()
  val constructorCall = constructor.createConstructorCall(arrayType)

  constructorCall.arguments[0] = 0.toIrConst(this.intType)
  if (constructor.parameters.size == 2) {
    // TODO find a way to avoid creation of empty lambda
    val tempFunction = createTempFunction(Name.identifier("TempForVararg"), this.anyType)
    tempFunction.parent = arrayClass // can be anything, will not be used in any case
    val initLambda = IrFunctionExpressionImpl(
      SYNTHETIC_OFFSET,
      SYNTHETIC_OFFSET,
      constructor.parameters[1].type,
      tempFunction,
      IrStatementOrigin.LAMBDA
    )
    constructorCall.arguments[1] = initLambda
    constructorCall.typeArguments[0] = (arrayType as IrSimpleType).arguments.singleOrNull()?.typeOrNull
  }
  return constructorCall
}

internal fun IrConst.toConstantValue(): ConstantValue<*> {
  if (value == null) return NullValue

  val constType = this.type.makeNotNull().removeAnnotations()
  return when (this.type.getPrimitiveType()) {
    PrimitiveType.BOOLEAN -> BooleanValue(this.value as Boolean)
    PrimitiveType.CHAR -> CharValue(this.value as Char)
    PrimitiveType.BYTE -> ByteValue((this.value as Number).toByte())
    PrimitiveType.SHORT -> ShortValue((this.value as Number).toShort())
    PrimitiveType.INT -> IntValue((this.value as Number).toInt())
    PrimitiveType.FLOAT -> FloatValue((this.value as Number).toFloat())
    PrimitiveType.LONG -> LongValue((this.value as Number).toLong())
    PrimitiveType.DOUBLE -> DoubleValue((this.value as Number).toDouble())
    null -> when (constType.getUnsignedType()) {
      UnsignedType.UBYTE -> UByteValue((this.value as Number).toByte())
      UnsignedType.USHORT -> UShortValue((this.value as Number).toShort())
      UnsignedType.UINT -> UIntValue((this.value as Number).toInt())
      UnsignedType.ULONG -> ULongValue((this.value as Number).toLong())
      null -> when {
        constType.isString() -> StringValue(this.value as String)
        else -> error("Cannot convert IrConst ${this.render()} to ConstantValue")
      }
    }
  }
}

internal fun IrElement.toConstantValue(): ConstantValue<*> {
  return this.toConstantValueOrNull() ?: errorWithAttachment("Cannot convert IrExpression to ConstantValue") {
    withEntry("IrExpression", this@toConstantValue.render())
  }
}

internal fun IrElement.toConstantValueOrNull(): ConstantValue<*>? {
  fun createKClassValue(argumentType: IrType): KClassValue? {
    if (argumentType is IrErrorType) return null
    if (argumentType !is IrSimpleType) return null

    var type = argumentType
    var arrayDimensions = 0
    while (type.isArray()) {
      if (type.isPrimitiveArray()) break
      val argument = (type as? IrSimpleType)?.arguments?.singleOrNull()
      type = argument?.typeOrNull ?: break
      arrayDimensions++
    }

    if (type.getClass()?.isLocal == true) return KClassValue()
    val classId = type.getClass()?.classId ?: return null
    return KClassValue(classId, arrayDimensions)
  }

  return when (this) {
    is IrConst -> this.toConstantValue()
    is IrConstructorCall -> {
      if (!this.type.isAnnotation()) return null
      val classId = this.symbol.owner.constructedClass.classId ?: return null
      val rawArguments = this.getAllArgumentsWithIr()
      val argumentMapping = rawArguments
        .filter { it.second != null || it.first.type.isArray() }
        .associate { (parameter, expression) -> parameter.name to (expression?.toConstantValue() ?: ArrayValue(emptyList())) }
      AnnotationValue.create(classId, argumentMapping)
    }
    is IrGetEnumValue -> {
      val classId = this.type.getClass()?.classId ?: return null
      EnumValue(classId, this.symbol.owner.name)
    }
    is IrClassReference -> createKClassValue(this.classType)
    is IrVararg -> ArrayValue(this.elements.map { it.toConstantValue() })
    else -> null
  }
}
