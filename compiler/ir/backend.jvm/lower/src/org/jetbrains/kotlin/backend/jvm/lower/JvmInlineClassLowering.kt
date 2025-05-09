/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.InlineClassAbi
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.MemoizedValueClassAbstractReplacements
import org.jetbrains.kotlin.backend.jvm.inlineClassFieldName
import org.jetbrains.kotlin.backend.jvm.isInlineClassFieldGetter
import org.jetbrains.kotlin.backend.jvm.unboxInlineClass
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irEqualsNull
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfNull
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.declarations.inlineClassRepresentation
import org.jetbrains.kotlin.ir.declarations.isSingleFieldValueClass
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.expressions.putArgument
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.transformStatement
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isClassWithFqName
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyValueAndTypeParametersFrom
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getInlineClassBackingField
import org.jetbrains.kotlin.ir.util.getInlineClassUnderlyingType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isEquals
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isTypeParameter
import org.jetbrains.kotlin.ir.util.isUnsigned
import org.jetbrains.kotlin.ir.util.passTypeArgumentsFrom
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.util.transformDeclarationsFlat
import org.jetbrains.kotlin.ir.util.typeSubstitutionMap
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.JVM_INLINE_ANNOTATION_FQ_NAME

/**
 * Adds new constructors, box, and unbox functions to inline classes as well as replacement
 * functions and bridges to avoid clashes between overloaded function. Changes call with
 * known types to call the replacement functions.
 *
 * We do not unfold inline class types here. Instead, the type mapper will lower inline class
 * types to the types of their underlying field.
 */
@PhaseDescription(
  name = "InlineClasses",
  // forLoopsPhase may produce UInt and ULong which are inline classes.
  // Standard library replacements are done on the not mangled names for UInt and ULong classes.
  // Collection stubs may require mangling by value class rules.
  // SAM wrappers may require mangling for fun interfaces with value class parameters
  prerequisite = [
    ForLoopsLowering::class, JvmBuiltInsLowering::class, CollectionStubMethodLowering::class, JvmSingleAbstractMethodLowering::class
  ]
)
internal class JvmInlineClassLowering(context: JvmBackendContext) : JvmValueClassAbstractLowering(context) {
  override val replacements: MemoizedValueClassAbstractReplacements
    get() = context.inlineClassReplacements

  private val valueMap = mutableMapOf<IrValueSymbol, IrValueDeclaration>()

  override fun addBindingsFor(original: IrFunction, replacement: IrFunction) {
    for ((param, newParam) in original.parameters.zip(replacement.parameters)) {
      valueMap[param.symbol] = newParam
    }
  }

  override fun createBridgeDeclaration(source: IrSimpleFunction, replacement: IrSimpleFunction, mangledName: Name): IrSimpleFunction =
    context.irFactory.buildFun {
      updateFrom(source)
      name = mangledName
      returnType = source.returnType
    }.apply {
      copyValueAndTypeParametersFrom(source)
      annotations = source.annotations
      parent = source.parent
      // We need to ensure that this bridge has the same attribute owner as its static inline class replacement, since this
      // is used in [CoroutineCodegen.isStaticInlineClassReplacementDelegatingCall] to identify the bridge and avoid generating
      // a continuation class.
      copyAttributes(source)
    }

  override fun IrClass.isSpecificLoweringLogicApplicable(): Boolean = isSingleFieldValueClass

  override val specificMangle: SpecificMangle
    get() = SpecificMangle.Inline

  override fun visitClassNewDeclarationsWhenParallel(declaration: IrDeclaration) = Unit

  override fun visitClassNew(declaration: IrClass): IrClass {
    // The arguments to the primary constructor are in scope in the initializers of IrFields.

    declaration.primaryConstructor?.let {
      replacements.getReplacementFunction(it)?.let { replacement -> addBindingsFor(it, replacement) }
    }

    declaration.transformDeclarationsFlat { memberDeclaration ->
      if (memberDeclaration is IrFunction) {
        withinScope(memberDeclaration) {
          transformFunctionFlat(memberDeclaration)
        }
      } else {
        memberDeclaration.accept(this, null)
        null
      }
    }

    if (declaration.isSpecificLoweringLogicApplicable()) {
      handleSpecificNewClass(declaration)
    }

    return declaration
  }

  override fun handleSpecificNewClass(declaration: IrClass) {
    val irConstructor = declaration.primaryConstructor!!
    // The field getter is used by reflection and cannot be removed here unless it is internal.
    declaration.declarations.removeIf {
      it == irConstructor || (it is IrFunction && it.isInlineClassFieldGetter && !it.visibility.isPublicAPI)
    }
    buildPrimaryInlineClassConstructor(declaration, irConstructor)
    buildBoxFunction(declaration)
    buildUnboxFunction(declaration)
    buildSpecializedEqualsMethodIfNeeded(declaration)
    addJvmInlineAnnotation(declaration)
  }

  private fun addJvmInlineAnnotation(valueClass: IrClass) {
    if (valueClass.hasAnnotation(JVM_INLINE_ANNOTATION_FQ_NAME)) return
    val constructor = context.ir.symbols.jvmInlineAnnotation.constructors.first()
    valueClass.annotations = valueClass.annotations + IrConstructorCallImpl.fromSymbolOwner(
      constructor.owner.returnType,
      constructor
    )
  }

  override fun createBridgeBody(source: IrSimpleFunction, target: IrSimpleFunction, original: IrFunction, inverted: Boolean) {
    source.body = context.createIrBuilder(source.symbol, source.startOffset, source.endOffset).run {
      irExprBody(irCall(target).apply {
        passTypeArgumentsFrom(source)
        for ((parameter, newParameter) in source.parameters.zip(target.parameters)) {
          putArgument(newParameter, irGet(parameter))
        }
      })
    }
  }

  // Secondary constructors for boxed types get translated to static functions returning
  // unboxed arguments. We remove the original constructor.
  // Primary constructors' case is handled at the start of transformFunctionFlat
  override fun transformSecondaryConstructorFlat(constructor: IrConstructor, replacement: IrSimpleFunction): List<IrDeclaration> {
    replacement.valueParameters.forEach { it.transformChildrenVoid() }
    replacement.body = context.createIrBuilder(replacement.symbol, replacement.startOffset, replacement.endOffset).irBlockBody(
      replacement
    ) {
      val thisVar = irTemporary(irType = replacement.returnType, nameHint = "\$this")
      valueMap[constructor.constructedClass.thisReceiver!!.symbol] = thisVar

      constructor.body?.statements?.forEach { statement ->
        +statement
          .transformStatement(object : IrElementTransformerVoid() {
            // Don't recurse under nested class declarations
            override fun visitClass(declaration: IrClass): IrStatement {
              return declaration
            }

            // Capture the result of a delegating constructor call in a temporary variable "thisVar".
            //
            // Within the constructor we replace references to "this" with references to "thisVar".
            // This is safe, since the delegating constructor call precedes all references to "this".
            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
              expression.transformChildrenVoid()
              return irSet(thisVar.symbol, expression)
            }

            // A constructor body has type unit and may contain explicit return statements.
            // These early returns may have side-effects however, so we still have to evaluate
            // the return expression. Afterwards we return "thisVar".
            // For example, the following is a valid inline class declaration.
            //
            //     inline class Foo(val x: String) {
            //       constructor(y: Int) : this(y.toString()) {
            //         if (y == 0) return throw java.lang.IllegalArgumentException()
            //         if (y == 1) return
            //         return Unit
            //       }
            //     }
            override fun visitReturn(expression: IrReturn): IrExpression {
              expression.transformChildrenVoid()
              if (expression.returnTargetSymbol != constructor.symbol)
                return expression

              return irReturn(irBlock(expression.startOffset, expression.endOffset) {
                +expression.value
                +irGet(thisVar)
              })
            }
          })
          .transformStatement(this@JvmInlineClassLowering)
          .patchDeclarationParents(replacement)
      }

      +irReturn(irGet(thisVar))
    }

    return listOf(replacement)
  }

  private fun IrMemberAccessExpression<*>.buildReplacement(
    originalFunction: IrFunction,
    original: IrMemberAccessExpression<*>,
    replacement: IrSimpleFunction,
  ) {
    copyTypeArgumentsFrom(original)
    val valueParameterMap = originalFunction.parameters.zip(replacement.parameters).toMap()
    for ((parameter, argument) in typedArgumentList(originalFunction, original)) {
      if (argument == null) continue
      val newParameter = valueParameterMap.getValue(parameter)
      putArgument(replacement, newParameter, argument.transform(this@JvmInlineClassLowering, null))
    }
  }

  override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
    if (expression.origin == InlineClassAbi.UNMANGLED_FUNCTION_REFERENCE)
      return super.visitFunctionReference(expression)

    val function = expression.symbol.owner
    val replacement = context.inlineClassReplacements.getReplacementFunction(function)
      ?: return super.visitFunctionReference(expression)

    // In case of callable reference to inline class constructor,
    // type parameters of the replacement include class's type parameters,
    // however, expression does not. Thus, we should not include them either.
    return IrFunctionReferenceImpl(
      expression.startOffset, expression.endOffset, expression.type,
      replacement.symbol, function.typeParameters.size,
      expression.reflectionTarget, expression.origin
    ).apply {
      buildReplacement(function, expression, replacement)
      copyAttributes(expression)
    }
  }

  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    val function = expression.symbol.owner
    val replacement = context.inlineClassReplacements.getReplacementFunction(function)
      ?: return super.visitFunctionAccess(expression)

    return IrCallImpl(
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
      type = function.returnType.substitute(expression.typeSubstitutionMap),
      symbol = replacement.symbol,
      typeArgumentsCount = replacement.typeParameters.size,
      origin = expression.origin,
      superQualifierSymbol = (expression as? IrCall)?.superQualifierSymbol
    ).apply {
      buildReplacement(function, expression, replacement)
    }
  }

  private fun coerceInlineClasses(argument: IrExpression, from: IrType, to: IrType, skipCast: Boolean = false): IrExpression {
    return IrCallImpl.fromSymbolOwner(UNDEFINED_OFFSET, UNDEFINED_OFFSET, to, context.ir.symbols.unsafeCoerceIntrinsic).apply {
      val underlyingType = from.erasedUpperBound.inlineClassRepresentation?.underlyingType
      if (underlyingType?.isTypeParameter() == true && !skipCast) {
        typeArguments[0] = from
        typeArguments[1] = underlyingType
        putValueArgument(
          0, IrTypeOperatorCallImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, to, IrTypeOperator.IMPLICIT_CAST, underlyingType, argument
          )
        )
      } else {
        typeArguments[0] = from
        typeArguments[1] = to
        putValueArgument(0, argument)
      }
    }
  }

  private fun IrExpression.coerceToUnboxed() =
    coerceInlineClasses(this, this.type, this.type.unboxInlineClass())

  // Precondition: left has an inline class type, but may not be unboxed
  private fun IrBuilderWithScope.specializeEqualsCall(left: IrExpression, right: IrExpression): IrExpression? {
    // There's already special handling for null-comparisons in the Equals intrinsic.
    if (left.isNullConst() || right.isNullConst())
      return null

    // We don't specialize calls when both arguments are boxed.
    val leftIsUnboxed = left.type.unboxInlineClass() != left.type
    val rightIsUnboxed = right.type.unboxInlineClass() != right.type
    if (!leftIsUnboxed && !rightIsUnboxed)
      return null

    // Precondition: left is an unboxed inline class type
    fun equals(left: IrExpression, right: IrExpression): IrExpression {
      // Unsigned types use primitive comparisons
      if (left.type.isUnsigned() && right.type.isUnsigned() && rightIsUnboxed)
        return irEquals(left.coerceToUnboxed(), right.coerceToUnboxed())

      val leftOperandClass = left.type.classOrNull!!.owner
      val equalsMethod = if (rightIsUnboxed && leftOperandClass == right.type.classOrNull!!.owner) {
        this@JvmInlineClassLowering.context.inlineClassReplacements.getSpecializedEqualsMethod(leftOperandClass, context.irBuiltIns)
      } else {
        val equals = leftOperandClass.functions.single { it.name.asString() == "equals" && it.overriddenSymbols.isNotEmpty() }
        this@JvmInlineClassLowering.context.inlineClassReplacements.getReplacementFunction(equals)!!
      }

      return irCall(equalsMethod).apply {
        putValueArgument(0, left)
        putValueArgument(1, right)
      }
    }

    val leftNullCheck = left.type.isNullable()
    val rightNullCheck = rightIsUnboxed && right.type.isNullable() // equals-impl has a nullable second argument
    return if (leftNullCheck || rightNullCheck) {
      irBlock {
        val leftVal = if (left is IrGetValue) left.symbol.owner else irTemporary(left)
        val rightVal = if (right is IrGetValue) right.symbol.owner else irTemporary(right)

        val equalsCall = equals(
          if (leftNullCheck) irImplicitCast(irGet(leftVal), left.type.makeNotNull()) else irGet(leftVal),
          if (rightNullCheck) irImplicitCast(irGet(rightVal), right.type.makeNotNull()) else irGet(rightVal)
        )

        val equalsRight = if (rightNullCheck) {
          irIfNull(context.irBuiltIns.booleanType, irGet(rightVal), irFalse(), equalsCall)
        } else {
          equalsCall
        }

        if (leftNullCheck) {
          +irIfNull(context.irBuiltIns.booleanType, irGet(leftVal), irEqualsNull(irGet(rightVal)), equalsRight)
        } else {
          +equalsRight
        }
      }
    } else {
      equals(left, right)
    }
  }

  override fun visitCall(expression: IrCall): IrExpression =
    when {
      // Getting the underlying field of an inline class merely changes the IR type,
      // since the underlying representations are the same.
      expression.symbol.owner.isInlineClassFieldGetter -> {
        val arg = expression.dispatchReceiver!!.transform(this, null)
        val from = expression.symbol.owner.dispatchReceiverParameter!!.type
        val to = context.inlineClassReplacements.getUnboxFunction(from.erasedUpperBound).returnType
        // We need direct unboxed parameter type here
        coerceInlineClasses(arg, from, to)
      }
      // Specialize calls to equals when the left argument is a value of inline class type.
      expression.isEqEqCallOnInlineClass || expression.isEqualsMethodCallOnInlineClass -> {
        expression.transformChildrenVoid()
        val leftOp: IrExpression
        val rightOp: IrExpression
        if (expression.isEqEqCallOnInlineClass) {
          leftOp = expression.getValueArgument(0)!!
          rightOp = expression.getValueArgument(1)!!
        } else {
          leftOp = expression.dispatchReceiver!!
          rightOp = expression.getValueArgument(0)!!
        }
        context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset)
          .specializeEqualsCall(leftOp, rightOp)
          ?: expression
      }
      else ->
        super.visitCall(expression)
    }

  private val IrCall.isEqualsMethodCallOnInlineClass: Boolean
    get() {
      if (!symbol.owner.isEquals()) return false
      val receiverClass = dispatchReceiver?.type?.classOrNull?.owner
      return receiverClass?.canUseSpecializedEqMethod ?: false
    }

  private val IrCall.isEqEqCallOnInlineClass: Boolean
    get() {
      // Note that reference equality (x === y) is not allowed on values of inline class type,
      // so it is enough to check for eqeq.
      if (symbol != context.irBuiltIns.eqeqSymbol) return false
      val leftOperandClass = getValueArgument(0)?.type?.classOrNull?.owner
      return leftOperandClass?.canUseSpecializedEqMethod ?: false
    }

  private val IrClass.canUseSpecializedEqMethod: Boolean
    get() {
      if (!isSingleFieldValueClass) return false
      // Before version 1.4, we cannot rely on the Result.equals-impl0 method
      return !isClassWithFqName(StandardNames.RESULT_FQ_NAME) ||
        context.config.languageVersionSettings.apiVersion >= ApiVersion.KOTLIN_1_4
    }

  override fun visitGetField(expression: IrGetField): IrExpression {
    val field = expression.symbol.owner
    val parent = field.parent
    if (field.origin == IrDeclarationOrigin.PROPERTY_BACKING_FIELD &&
      parent is IrClass &&
      parent.isSingleFieldValueClass &&
      field.name == parent.inlineClassFieldName
    ) {
      val receiver = expression.receiver!!.transform(this, null)
      // If we get the field of nullable variable, we can be sure, that type is not null,
      // since we first generate null check.
      return coerceInlineClasses(receiver, receiver.type.makeNotNull(), field.type)
    }
    return super.visitGetField(expression)
  }

  override fun visitGetValue(expression: IrGetValue): IrExpression {
    valueMap[expression.symbol]?.let {
      return IrGetValueImpl(
        expression.startOffset, expression.endOffset,
        it.type, it.symbol, expression.origin
      )
    }
    return super.visitGetValue(expression)
  }

  override fun visitSetValue(expression: IrSetValue): IrExpression {
    valueMap[expression.symbol]?.let {
      return IrSetValueImpl(
        expression.startOffset, expression.endOffset,
        it.type, it.symbol,
        expression.value.transform(this@JvmInlineClassLowering, null),
        expression.origin
      )
    }
    return super.visitSetValue(expression)
  }

  private fun buildPrimaryInlineClassConstructor(valueClass: IrClass, irConstructor: IrConstructor) {
    // Add the default primary constructor
    valueClass.addConstructor {
      updateFrom(irConstructor)
      visibility = DescriptorVisibilities.PRIVATE
      origin = JvmLoweredDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER
      returnType = irConstructor.returnType
    }.apply {
      // Don't create a default argument stub for the primary constructor
      irConstructor.valueParameters.forEach { it.defaultValue = null }
      copyValueAndTypeParametersFrom(irConstructor)
      annotations = irConstructor.annotations
      body = context.createIrBuilder(this.symbol).irBlockBody(this) {
        +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
        +irSetField(
          irGet(valueClass.thisReceiver!!),
          getInlineClassBackingField(valueClass),
          irGet(this@apply.valueParameters[0])
        )
      }
    }

    // Add a static bridge method to the primary constructor. This contains
    // null-checks, default arguments, and anonymous initializers.
    val function = context.inlineClassReplacements.getReplacementFunction(irConstructor)!!

    val initBlocks = valueClass.declarations.filterIsInstance<IrAnonymousInitializer>()
      .filterNot { it.isStatic }

    function.valueParameters.forEach { it.transformChildrenVoid() }
    function.body = context.createIrBuilder(function.symbol).irBlockBody {
      val argument = function.valueParameters[0]
      val thisValue = irTemporary(coerceInlineClasses(irGet(argument), argument.type, function.returnType, skipCast = true))
      valueMap[valueClass.thisReceiver!!.symbol] = thisValue
      for (initBlock in initBlocks) {
        for (stmt in initBlock.body.statements) {
          +stmt.transformStatement(this@JvmInlineClassLowering).patchDeclarationParents(function)
        }
      }
      +irReturn(irGet(thisValue))
    }

    valueClass.declarations.removeAll(initBlocks)
    valueClass.declarations += function
  }

  private fun buildBoxFunction(valueClass: IrClass) {
    val function = context.inlineClassReplacements.getBoxFunction(valueClass)
    with(context.createIrBuilder(function.symbol)) {
      function.body = irExprBody(
        irCall(valueClass.primaryConstructor!!.symbol).apply {
          passTypeArgumentsFrom(function)
          putValueArgument(0, irGet(function.valueParameters[0]))
        }
      )
    }
    valueClass.declarations += function
  }

  private fun buildUnboxFunction(irClass: IrClass) {
    val function = context.inlineClassReplacements.getUnboxFunction(irClass)
    val field = getInlineClassBackingField(irClass)

    function.body = context.createIrBuilder(function.symbol).irBlockBody {
      val thisVal = irGet(function.dispatchReceiverParameter!!)
      +irReturn(irGetField(thisVal, field))
    }

    irClass.declarations += function
  }

  private fun buildSpecializedEqualsMethodIfNeeded(valueClass: IrClass) {
    val function = context.inlineClassReplacements.getSpecializedEqualsMethod(valueClass, context.irBuiltIns)
    // Return if we have already built specialized equals as static replacement of typed equals
    if (function.body != null) return
    val left = function.valueParameters[0]
    val right = function.valueParameters[1]
    val type = left.type.unboxInlineClass()

    val untypedEquals = valueClass.functions.single { it.isEquals() }

    function.body = context.createIrBuilder(valueClass.symbol).run {
      val context = this@JvmInlineClassLowering.context
      val underlyingType = getInlineClassUnderlyingType(valueClass)
      irExprBody(
        if (untypedEquals.origin == IrDeclarationOrigin.DEFINED) {
          val boxFunction = context.inlineClassReplacements.getBoxFunction(valueClass)

          fun irBox(expr: IrExpression) = irCall(boxFunction).apply { putValueArgument(0, expr) }

          irCall(untypedEquals).apply {
            dispatchReceiver = irBox(coerceInlineClasses(irGet(left), left.type, underlyingType))
            putValueArgument(0, irBox(coerceInlineClasses(irGet(right), right.type, underlyingType)))
          }
        } else {
          val underlyingClass = underlyingType.getClass()
          // We can't directly compare unboxed values of underlying inline class as this class can have custom equals
          if (underlyingClass?.isSingleFieldValueClass == true && !underlyingType.isNullable()) {
            val underlyingClassEq =
              context.inlineClassReplacements.getSpecializedEqualsMethod(underlyingClass, context.irBuiltIns)
            irCall(underlyingClassEq).apply {
              putValueArgument(0, coerceInlineClasses(irGet(left), left.type, underlyingType))
              putValueArgument(1, coerceInlineClasses(irGet(right), right.type, underlyingType))
            }
          } else {
            irEquals(coerceInlineClasses(irGet(left), left.type, type), coerceInlineClasses(irGet(right), right.type, type))
          }
        }
      )
    }

    valueClass.declarations += function
  }

}
