/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.lower.bridgeTarget
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

internal fun dce(
        context: Context,
        irModule: IrModuleFragment,
        moduleDFG: ModuleDFG,
): Set<IrSimpleFunction> {
    val callGraph = CallGraphBuilder(
            context,
            irModule,
            moduleDFG,
            // Do not devirtualize anything to keep the graph smaller (albeit less precise which is fine for DCE).
            devirtualizedCallSitesUnfoldFactor = -1,
            nonDevirtualizedCallSitesUnfoldFactor = -1,
    ).build()
    val referencedFunctions = mutableSetOf<IrSimpleFunction>()

    fun referenceFunction(functionSymbol: DataFlowIR.FunctionSymbol) {
        val irFunction = functionSymbol.irFunction ?: error("No IR for: $functionSymbol")
        referencedFunctions.add(irFunction)
        // Need to keep the bridges' targets to not get them DCE-ed, as they are used during classes layout construction.
        irFunction.bridgeTarget?.let { referencedFunctions.add(it) }
    }

    callGraph.rootExternalFunctions.forEach { referenceFunction(it) }
    for (node in callGraph.directEdges.values) {
        if (!node.symbol.isStaticFieldInitializer)
            referenceFunction(node.symbol)

        for (callSite in node.callSites) {
            if (!callSite.isVirtual)
                referenceFunction(callSite.actualCallee)
        }
    }

    irModule.acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            // TODO: Generalize somehow, not that graceful.
            if (declaration.name == OperatorNameConventions.INVOKE
                    && declaration.parent.let { it is IrClass && it.defaultType.isFunction() }) {
                referencedFunctions.add(declaration)
            }
            super.visitFunction(declaration)
        }
    })

    irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
        override fun visitFile(declaration: IrFile): IrFile {
            declaration.declarations.removeAll {
                (it is IrSimpleFunction && !referencedFunctions.contains(it))
            }
            return super.visitFile(declaration)
        }

        override fun visitClass(declaration: IrClass): IrStatement {
            if (declaration == context.ir.symbols.nativePointed)
                return super.visitClass(declaration)
            declaration.declarations.removeAll {
                (it is IrSimpleFunction && it.isReal && !referencedFunctions.contains(it))
            }
            return super.visitClass(declaration)
        }

        override fun visitProperty(declaration: IrProperty): IrStatement {
            if (declaration.getter.let { it != null && it.isReal && !referencedFunctions.contains(it) }) {
                declaration.getter = null
            }
            if (declaration.setter.let { it != null && it.isReal && !referencedFunctions.contains(it) }) {
                declaration.setter = null
            }
            return super.visitProperty(declaration)
        }
    })

    return referencedFunctions
}
