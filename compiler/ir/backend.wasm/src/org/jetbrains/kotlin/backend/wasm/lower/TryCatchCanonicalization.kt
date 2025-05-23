/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.FinallyBlocksLowering
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlock
import org.jetbrains.kotlin.backend.common.lower.irCatch
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.utils.isCanonical
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irTry
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.runIf

/**
 * Transforms try/catch statements into a simple canonical form which is easily mapped into Wasm instruction set.
 *
 * From this:
 *
 *    try {
 *        ...exprs
 *    } catch (e: Foo) {
 *        ...exprs
 *    } catch (e: Bar) {
 *        ...exprs
 *    } finally {
 *        ...exprs
 *    }
 *
 * We get this:
 *
 *    try {
 *        ...exprs
 *    } catch (e: Throwable) {
 *        when (e) {
 *            is Foo -> ...exprs
 *            is Bar -> ...exprs
 *        }
 *    }
 *
 * With `finally` we transform this:
 *
 *    try {
 *        ...exprs
 *    } catch (e: Throwable) {
 *        ...exprs
 *    } finally {
 *        ...<finally exprs>
 *    }
 *
 * Into something like this (tmp variable is used only if we return some result):
 *
 *    val tmp = block { // this is where we return if we return from original try/catch with the result
 *        try {
 *            try {
 *                return@block ...exprs
 *            } catch (e: Throwable) {
 *                return@block ...exprs
 *            }
 *        }
 *        catch (e: Throwable) {
 *            ...<finally exprs>
 *            throw e // rethrow exception if it happened inside of the catch statement
 *        }
 *    }
 *    ...<finally exprs>
 *    tmp // result
 */
internal class TryCatchCanonicalization(private val ctx: WasmBackendContext) : FileLoweringPass {
  override fun lower(irFile: IrFile) {
    if (ctx.isWasmJsTarget) {
      irFile.transformChildrenVoid(JsExceptionHandlerForThrowableOnly(ctx))
    }

    irFile.transformChildrenVoid(CatchMerger(ctx))

    irFile.transformChildrenVoid(FinallyBlocksLowering(ctx, ctx.irBuiltIns.throwableType))

    irFile.acceptVoid(object : IrElementVisitorVoid {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitTry(aTry: IrTry) {
        check(aTry.isCanonical(ctx)) { "Found non canonical try/catch $aTry" }
      }
    })
  }
}

val SYNTHETIC_JS_EXCEPTION_HANDLER_TO_SUPPORT_CATCH_THROWABLE by IrStatementOriginImpl

internal class JsExceptionHandlerForThrowableOnly(private val ctx: WasmBackendContext) : IrElementTransformerVoidWithContext() {
  override fun visitTry(aTry: IrTry): IrExpression {
    aTry.transformChildrenVoid(this)

    val throwableHandlerIndex = aTry.catches.indexOfFirst { it.catchParameter.type == ctx.irBuiltIns.throwableType }
    val activeCatches = when (throwableHandlerIndex) {
      -1 -> aTry.catches
      else -> aTry.catches.subList(0, throwableHandlerIndex + 1)
    }
    // Nothing to do
    if (activeCatches.isEmpty() ||
      activeCatches.any { it.catchParameter.type == ctx.wasmSymbols.jsRelatedSymbols.jsException.defaultType } ||
      throwableHandlerIndex == -1
    ) return super.visitTry(aTry)


    val jsExceptionCatch = ctx.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).run {
      val jsExceptionCatchParameter = buildVariable(
        currentScope!!.scope.getLocalDeclarationParent(),
        startOffset,
        endOffset,
        IrDeclarationOrigin.CATCH_PARAMETER,
        Name.identifier("e"),
        ctx.wasmSymbols.jsRelatedSymbols.jsException.defaultType,
      )
      irCatch(
        jsExceptionCatchParameter,
        irBlock(aTry) { },
        SYNTHETIC_JS_EXCEPTION_HANDLER_TO_SUPPORT_CATCH_THROWABLE
      )
    }

    aTry.catches.add(throwableHandlerIndex, jsExceptionCatch)

    return super.visitTry(aTry)
  }
}

internal class CatchMerger(private val ctx: WasmBackendContext) : IrElementTransformerVoidWithContext() {
  private val allowedCatchParameterTypes = buildSet {
    add(ctx.irBuiltIns.throwableType)
    if (ctx.isWasmJsTarget) {
      add(ctx.wasmSymbols.jsRelatedSymbols.jsException.defaultType)
    }
  }

  override fun visitTry(aTry: IrTry): IrExpression {
    // First, handle all nested constructs
    aTry.transformChildrenVoid(this)

    val throwableHandlerIndex = aTry.catches.indexOfFirst { it.catchParameter.type == ctx.irBuiltIns.throwableType }
    val activeCatches = aTry.catches.apply {
      if (throwableHandlerIndex != -1 && throwableHandlerIndex != lastIndex) {
        subList(throwableHandlerIndex + 1, size).clear()
      }
    }

    // Nothing to do
    if (activeCatches.isEmpty() || activeCatches.all { it.catchParameter.type in allowedCatchParameterTypes })
      return super.visitTry(aTry)

    ctx.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).run {
      val newCatchParameter = buildVariable(
        currentScope!!.scope.getLocalDeclarationParent(),
        startOffset,
        endOffset,
        IrDeclarationOrigin.CATCH_PARAMETER,
        Name.identifier("merged_catch_param"),
        ctx.irBuiltIns.throwableType
      )

      val jsExceptionCatchIndex = runIf(ctx.isWasmJsTarget) {
        activeCatches.indexOfFirst {
          it.catchParameter.type == ctx.wasmSymbols.jsRelatedSymbols.jsException.defaultType
        }
      } ?: -1

      val newCatchBody = irBlock(aTry, startOffset = UNDEFINED_OFFSET, endOffset = UNDEFINED_OFFSET) {
        +irWhen(
          aTry.type,
          activeCatches.mapIndexedNotNull { i, it ->
            runIf(i != jsExceptionCatchIndex) {
              irBranch(
                irIs(irGet(newCatchParameter), it.catchParameter.type),
                irBlock(it.result, startOffset = UNDEFINED_OFFSET, endOffset = UNDEFINED_OFFSET) {

                  it.catchParameter.initializer = irImplicitCast(irGet(newCatchParameter), it.catchParameter.type)
                  it.catchParameter.origin = IrDeclarationOrigin.DEFINED
                  +it.catchParameter
                  +it.result
                }
              )
            }
          } + irElseBranch(irThrow(irGet(newCatchParameter)))
        )
      }

      val newCatch = irCatch(newCatchParameter, newCatchBody)

      return irTry(
        aTry.type,
        aTry.tryResult,
        listOfNotNull(activeCatches.getOrNull(jsExceptionCatchIndex), newCatch),
        aTry.finallyExpression
      )
    }
  }
}

