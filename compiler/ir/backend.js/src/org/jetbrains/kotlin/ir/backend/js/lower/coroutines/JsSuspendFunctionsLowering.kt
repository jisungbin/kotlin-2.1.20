/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.coroutines

import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.lower.FinallyBlocksLowering
import org.jetbrains.kotlin.backend.common.lower.ReturnableBlockTransformer
import org.jetbrains.kotlin.backend.common.lower.coroutines.loweredSuspendFunctionReturnType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsCommonBackendContext
import org.jetbrains.kotlin.ir.backend.js.JsStatementOrigins
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.createTmpVariable
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irComposite
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDoWhileLoopImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addToStdlib.assertedCast

/**
 * Transforms suspend function into a `CoroutineImpl` instance and builds a state machine.
 */
class JsSuspendFunctionsLowering(ctx: JsCommonBackendContext) : AbstractSuspendFunctionsLowering<JsCommonBackendContext>(ctx) {
  private val coroutineSymbols = ctx.symbols.coroutineSymbols

  private val coroutineImplExceptionPropertyGetter = coroutineSymbols.coroutineImplExceptionPropertyGetter
  private val coroutineImplExceptionPropertySetter = coroutineSymbols.coroutineImplExceptionPropertySetter
  private val coroutineImplExceptionStatePropertyGetter = coroutineSymbols.coroutineImplExceptionStatePropertyGetter
  private val coroutineImplExceptionStatePropertySetter = coroutineSymbols.coroutineImplExceptionStatePropertySetter
  private val coroutineImplLabelPropertySetter = coroutineSymbols.coroutineImplLabelPropertySetter
  private val coroutineImplLabelPropertyGetter = coroutineSymbols.coroutineImplLabelPropertyGetter
  private val coroutineImplResultSymbolGetter = coroutineSymbols.coroutineImplResultSymbolGetter
  private val coroutineImplResultSymbolSetter = coroutineSymbols.coroutineImplResultSymbolSetter

  private var coroutineId = 0

  override val stateMachineMethodName = Name.identifier("doResume")
  override fun getCoroutineBaseClass(function: IrFunction) = context.ir.symbols.coroutineImpl

  override fun nameForCoroutineClass(function: IrFunction) = "${function.name}COROUTINE\$${coroutineId++}".synthesizedName

  override fun buildStateMachine(
    stateMachineFunction: IrFunction,
    transformingFunction: IrFunction,
    argumentToPropertiesMap: Map<IrValueParameter, IrField>,
  ) {
    val returnableBlockTransformer = ReturnableBlockTransformer(context)
    val finallyBlockTransformer = FinallyBlocksLowering(context, context.catchAllThrowableType)
    val simplifiedFunction =
      transformingFunction.transform(finallyBlockTransformer, null).transform(returnableBlockTransformer, null) as IrFunction

    val originalBody = simplifiedFunction.body as IrBlockBody

    val body = IrBlockImpl(
      simplifiedFunction.startOffset,
      simplifiedFunction.endOffset,
      context.irBuiltIns.unitType,
      JsStatementOrigins.STATEMENT_ORIGIN_COROUTINE_IMPL,
      originalBody.statements
    )

    val coroutineClass = stateMachineFunction.parent as IrClass
    val suspendResult = JsIrBuilder.buildVar(
      context.irBuiltIns.anyNType,
      stateMachineFunction,
      "suspendResult",
      true,
      initializer = JsIrBuilder.buildCall(coroutineImplResultSymbolGetter.symbol).apply {
        dispatchReceiver = JsIrBuilder.buildGetValue(stateMachineFunction.dispatchReceiverParameter!!.symbol)
      }
    )

    val suspendState = JsIrBuilder.buildVar(coroutineImplLabelPropertyGetter.returnType, stateMachineFunction, "suspendState", true)

    val unit = context.irBuiltIns.unitType

    val switch = IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, unit, JsStatementOrigins.COROUTINE_SWITCH)
    val stateVar = JsIrBuilder.buildVar(context.irBuiltIns.intType, stateMachineFunction)
    val switchBlock = IrBlockImpl(switch.startOffset, switch.endOffset, switch.type).apply {
      statements += stateVar
      statements += switch
    }
    val rootTry = IrTryImpl(body.startOffset, body.endOffset, unit).apply { tryResult = switchBlock }
    val rootLoop = IrDoWhileLoopImpl(
      body.startOffset,
      body.endOffset,
      unit,
      JsStatementOrigins.COROUTINE_ROOT_LOOP,
    ).also {
      it.condition = JsIrBuilder.buildBoolean(context.irBuiltIns.booleanType, true)
      it.body = rootTry
      it.label = "\$sm"
    }

    val suspendableNodes = collectSuspendableNodes(body)
    val thisReceiver = (stateMachineFunction.dispatchReceiverParameter as IrValueParameter).symbol
    stateVar.initializer = JsIrBuilder.buildCall(coroutineImplLabelPropertyGetter.symbol).apply {
      dispatchReceiver = JsIrBuilder.buildGetValue(thisReceiver)
    }

    val stateMachineBuilder = StateMachineBuilder(
      suspendableNodes,
      context,
      stateMachineFunction.symbol,
      rootLoop,
      coroutineImplExceptionPropertyGetter,
      coroutineImplExceptionPropertySetter,
      coroutineImplExceptionStatePropertyGetter,
      coroutineImplExceptionStatePropertySetter,
      coroutineImplLabelPropertySetter,
      thisReceiver,
      getSuspendResultAsType = { type ->
        JsIrBuilder.buildImplicitCast(
          JsIrBuilder.buildGetValue(suspendResult.symbol),
          type
        )
      },
      setSuspendResultValue = { value ->
        JsIrBuilder.buildSetVariable(
          suspendResult.symbol,
          JsIrBuilder.buildImplicitCast(
            value,
            context.irBuiltIns.anyNType
          ),
          unit
        )
      }
    )

    body.acceptVoid(stateMachineBuilder)

    stateMachineBuilder.finalizeStateMachine()

    rootTry.catches += stateMachineBuilder.globalCatch

    assignStateIds(stateMachineBuilder.entryState, stateVar.symbol, switch, rootLoop)

    // Set exceptionState to the global catch block
    stateMachineBuilder.entryState.entryBlock.run {
      val receiver = JsIrBuilder.buildGetValue(coroutineClass.thisReceiver!!.symbol)
      val exceptionTrapId = stateMachineBuilder.rootExceptionTrap.id
      check(exceptionTrapId >= 0)
      val id = JsIrBuilder.buildInt(context.irBuiltIns.intType, exceptionTrapId)
      statements.add(0, JsIrBuilder.buildCall(coroutineImplExceptionStatePropertySetter.symbol).also { call ->
        call.arguments[0] = receiver
        call.arguments[1] = id
      })
    }

    val functionBody = context.irFactory.createBlockBody(
      stateMachineFunction.startOffset, stateMachineFunction.endOffset, listOf(suspendResult, rootLoop)
    )

    stateMachineFunction.body = functionBody

    // Move return targets to new function
    functionBody.transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitReturn(expression: IrReturn): IrExpression {
        expression.transformChildrenVoid(this)

        return if (expression.returnTargetSymbol != simplifiedFunction.symbol)
          expression
        else
          JsIrBuilder.buildReturn(stateMachineFunction.symbol, expression.value, expression.type)
      }
    })

    val liveLocals = computeLivenessAtSuspensionPoints(functionBody).values.flatten().toSet()

    val localToPropertyMap = hashMapOf<IrValueSymbol, IrFieldSymbol>()
    var localCounter = 0
    // TODO: optimize by using the same property for different locals.
    liveLocals.forEach {
      if (it !== suspendState && it !== suspendResult && it !== stateVar) {
        localToPropertyMap.getOrPut(it.symbol) {
          coroutineClass.addField(Name.identifier("${it.name}${localCounter++}"), it.type, (it as? IrVariable)?.isVar ?: false)
            .symbol
        }
      }
    }
    val isSuspendLambda = transformingFunction.parent === coroutineClass
    val parameters = if (isSuspendLambda) simplifiedFunction.nonDispatchParameters else simplifiedFunction.parameters
    for (parameter in parameters) {
      localToPropertyMap.getOrPut(parameter.symbol) {
        argumentToPropertiesMap.getValue(parameter).symbol
      }
    }

    // TODO find out why some parents are incorrect
    stateMachineFunction.body!!.patchDeclarationParents(stateMachineFunction)
    stateMachineFunction.transform(LiveLocalsTransformer(localToPropertyMap, { JsIrBuilder.buildGetValue(thisReceiver) }, unit), null)
  }

  private fun assignStateIds(entryState: SuspendState, subject: IrVariableSymbol, switch: IrWhen, rootLoop: IrLoop) {
    val visited = mutableSetOf<SuspendState>()

    val sortedStates = DFS.topologicalOrder(listOf(entryState), { it.successors }, { visited.add(it) })
    sortedStates.withIndex().forEach { it.value.id = it.index }

    val eqeqeqInt = context.irBuiltIns.eqeqeqSymbol

    for (state in sortedStates) {
      val condition = JsIrBuilder.buildCall(eqeqeqInt).apply {
        arguments[0] = JsIrBuilder.buildGetValue(subject)
        arguments[1] = JsIrBuilder.buildInt(context.irBuiltIns.intType, state.id)
      }

      switch.branches += IrBranchImpl(state.entryBlock.startOffset, state.entryBlock.endOffset, condition, state.entryBlock)
    }

    val dispatchPointTransformer = DispatchPointTransformer {
      assert(it.id >= 0)
      JsIrBuilder.buildInt(context.irBuiltIns.intType, it.id)
    }

    rootLoop.transformChildrenVoid(dispatchPointTransformer)
  }

  private fun computeLivenessAtSuspensionPoints(body: IrBody): Map<IrCall, List<IrValueDeclaration>> {
    // TODO: data flow analysis.
    // Just save all visible for now.
    val result = mutableMapOf<IrCall, List<IrValueDeclaration>>()
    body.acceptChildrenVoid(object : VariablesScopeTracker() {
      override fun visitCall(expression: IrCall) {
        if (!expression.isSuspend) return super.visitCall(expression)

        expression.acceptChildrenVoid(this)
        val visibleVariables = mutableListOf<IrValueDeclaration>()
        scopeStack.forEach { visibleVariables += it }
        result[expression] = visibleVariables
      }
    })

    return result
  }

  private fun needUnboxingOrUnit(fromType: IrType, toType: IrType): Boolean {
    val icUtils = context.inlineClassesUtils

    return (icUtils.getInlinedClass(fromType) == null && icUtils.getInlinedClass(toType) != null) ||
      (fromType.isUnit() && !toType.isUnit())
  }

  override fun IrBuilderWithScope.generateDelegatedCall(expectedType: IrType, delegatingCall: IrExpression): IrExpression {
    val functionReturnType = (delegatingCall as? IrCall)?.symbol?.owner?.let { function ->
      loweredSuspendFunctionReturnType(function, context.irBuiltIns)
    } ?: delegatingCall.type

    if (!needUnboxingOrUnit(functionReturnType, expectedType)) return delegatingCall

    return irComposite(resultType = expectedType) {
      val tmp = createTmpVariable(delegatingCall, irType = functionReturnType)
      val coroutineSuspended = irCall(coroutineSymbols.coroutineSuspendedGetter)
      val condition = irEqeqeq(irGet(tmp), coroutineSuspended)
      +irIfThen(context.irBuiltIns.unitType, condition, irReturn(irGet(tmp)))
      +irImplicitCast(irGet(tmp), expectedType)
    }
  }

  override fun IrBlockBodyBuilder.generateCoroutineStart(invokeSuspendFunction: IrFunction, receiver: IrExpression) {
    val dispatchReceiverVar = createTmpVariable(receiver, irType = receiver.type)
    +irCall(coroutineImplResultSymbolSetter).apply {
      arguments[0] = irGet(dispatchReceiverVar)
      arguments[1] = irGetObject(context.irBuiltIns.unitClass)
    }
    +irCall(coroutineImplExceptionPropertySetter).apply {
      arguments[0] = irGet(dispatchReceiverVar)
      arguments[1] = irNull()
    }
    val call = irCall(invokeSuspendFunction.symbol).apply {
      arguments[0] = irGet(dispatchReceiverVar)
    }
    val functionReturnType = scope.scopeOwnerSymbol.assertedCast<IrSimpleFunctionSymbol> { "Expected function symbol" }.owner.returnType
    +irReturn(generateDelegatedCall(functionReturnType, call))
  }
}
