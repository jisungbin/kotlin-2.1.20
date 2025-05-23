/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.createJvmIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.irArray
import org.jetbrains.kotlin.backend.jvm.needsMfvcFlattening
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallOp
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.isSuspendFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Adds bridges for invoke functions with a large number of arguments.
 *
 * There are concrete function classes for functions with fewer than [BuiltInFunctionArity.BIG_ARITY] arguments. Above that, we inherit
 * from the generic FunctionN class which has a vararg `invoke` method. This phase adds a bridge method for such large arity functions,
 * which checks the number of arguments dynamically.
 */
@PhaseDescription(name = "FunctionBridgePhase")
internal class FunctionNVarargBridgeLowering(val context: JvmBackendContext) :
  FileLoweringPass, IrElementTransformerVoidWithContext() {
  override fun lower(irFile: IrFile) = irFile.transformChildrenVoid(this)

  // Change calls to big arity invoke functions to vararg calls.
  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    if (expression.valueArgumentsCount < BuiltInFunctionArity.BIG_ARITY ||
      !(expression.symbol.owner.parentAsClass.defaultType.isFunctionOrKFunction() ||
        expression.symbol.owner.parentAsClass.defaultType.isSuspendFunctionOrKFunction()) ||
      expression.symbol.owner.name.asString() != "invoke"
    ) return super.visitFunctionAccess(expression)

    return context.createJvmIrBuilder(currentScope!!).run {
      at(expression)
      irCall(functionNInvokeFun).apply {
        dispatchReceiver = irImplicitCast(
          expression.dispatchReceiver!!.transformVoid(),
          this@FunctionNVarargBridgeLowering.context.ir.symbols.functionN.defaultType
        )
        putValueArgument(0, irArray(irSymbols.array.typeWith(context.irBuiltIns.anyNType)) {
          (0 until expression.valueArgumentsCount).forEach {
            +expression.getValueArgument(it)!!.transformVoid()
          }
        })
      }
    }
  }

  private fun IrExpression.transformVoid() =
    transform(this@FunctionNVarargBridgeLowering, null)

  override fun visitClassNew(declaration: IrClass): IrStatement {
    val bigArityFunctionSuperTypes = declaration.superTypes.filterIsInstance<IrSimpleType>().filter {
      it.isFunctionType && it.arguments.size > BuiltInFunctionArity.BIG_ARITY
    }

    if (bigArityFunctionSuperTypes.isEmpty())
      return super.visitClassNew(declaration)
    declaration.transformChildrenVoid(this)

    // Note that we allow classes with multiple function supertypes, so long as only one of them has more than 22 arguments.
    // Code below will generate one 'invoke' for each function supertype,
    // which will cause conflicting inherited JVM signatures error diagnostics in case of multiple big arity function supertypes.
    for (superType in bigArityFunctionSuperTypes) {
      declaration.superTypes -= superType
      declaration.superTypes += context.ir.symbols.functionN.typeWith(
        (superType.arguments.last() as IrTypeProjection).type
      )

      // Add vararg invoke bridge
      val invokeFunction = declaration.functions.single { function ->
        val overridesInvoke = function.overriddenSymbols.any { symbol ->
          symbol.owner.name.asString() == "invoke"
        }
        overridesInvoke && function.valueParameters.size == superType.arguments.sumOf {
          if (it.typeOrNull?.needsMfvcFlattening() != true) 1
          else context.multiFieldValueClassReplacements.getRootMfvcNode(it.typeOrNull!!.erasedUpperBound).leavesCount
        } - if (function.isSuspend) 0 else 1
      }
      invokeFunction.overriddenSymbols = emptyList()
      declaration.addBridge(invokeFunction, functionNInvokeFun.owner)
    }

    return declaration
  }

  private fun IrClass.addBridge(invoke: IrSimpleFunction, superFunction: IrSimpleFunction) =
    addFunction {
      name = superFunction.name
      returnType = context.irBuiltIns.anyNType
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PUBLIC
      origin = IrDeclarationOrigin.BRIDGE
    }.apply {
      overriddenSymbols += superFunction.symbol
      dispatchReceiverParameter = thisReceiver!!.copyTo(this)
      valueParameters += superFunction.valueParameters.single().copyTo(this)

      body = context.createIrBuilder(symbol).irBlockBody(startOffset, endOffset) {
        // Check the number of arguments
        val argumentCount = invoke.valueParameters.size
        +irIfThen(
          irNotEquals(
            irCall(arraySizePropertyGetter).apply {
              dispatchReceiver = irGet(valueParameters.single())
            },
            irInt(argumentCount)
          ),
          irCall(context.irBuiltIns.illegalArgumentExceptionSymbol).apply {
            putValueArgument(0, irString("Expected $argumentCount arguments"))
          }
        )

        +irReturn(irCall(invoke).apply {
          dispatchReceiver = irGet(dispatchReceiverParameter!!)

          for (parameter in invoke.valueParameters) {
            val index = parameter.indexInOldValueParameters
            val argArray = irGet(valueParameters.single())
            val argument = irCallOp(arrayGetFun, context.irBuiltIns.anyNType, argArray, irInt(index))
            putValueArgument(index, irImplicitCast(argument, invoke.valueParameters[index].type))
          }
        })
      }
    }

  // Check if a type is an instance of kotlin.Function*, kotlin.jvm.functions.Function*
  // or kotlin.reflect.KFunction*. Note that `IrType.isFunctionOrKFunction()` in IrTypeUtils
  // does not check for the jvm specific kotlin.jvm.functions package and can't be used here.
  private val IrType.isFunctionType: Boolean
    get() {
      val clazz = classOrNull?.owner ?: return false
      val name = clazz.name.asString()
      val fqName = (clazz.parent as? IrPackageFragment)?.packageFqName ?: return false
      return when {
        name.startsWith("Function") ->
          fqName == StandardNames.BUILT_INS_PACKAGE_FQ_NAME || fqName == FUNCTIONS_PACKAGE_FQ_NAME
        name.startsWith("KFunction") ->
          fqName == REFLECT_PACKAGE_FQ_NAME
        else -> false
      }
    }

  private val functionNInvokeFun =
    context.ir.symbols.functionN.functions.single { it.owner.name.toString() == "invoke" }

  private val arraySizePropertyGetter by lazy {
    context.irBuiltIns.arrayClass.owner.properties.single { it.name.toString() == "size" }.getter!!
  }

  private val arrayGetFun by lazy {
    context.irBuiltIns.arrayClass.functions.single { it.owner.name.toString() == "get" }
  }

  private val FUNCTIONS_PACKAGE_FQ_NAME =
    StandardNames.BUILT_INS_PACKAGE_FQ_NAME
      .child(Name.identifier("jvm"))
      .child(Name.identifier("functions"))

  private val REFLECT_PACKAGE_FQ_NAME =
    StandardNames.BUILT_INS_PACKAGE_FQ_NAME
      .child(Name.identifier("reflect"))
}
