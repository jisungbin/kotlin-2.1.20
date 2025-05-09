/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrValueAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.isInlineParameter
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.utils.addToStdlib.runIf

/**
 * Transforms declarations and usages of variables captured in lambdas ("shared variables") to `ObjectRef`/`IntRef`/...
 *
 * For example:
 *
 *     var x = "a"
 *     run {
 *         x = "b"
 *         println(x)
 *     }
 *
 * becomes
 *
 *     var x = ObjectRef<String>()
 *     x.element = "a"
 *     run {
 *         x.element = "b"
 *         println(x.element)
 *     }
 */
@PhaseDescription(name = "SharedVariables")
class SharedVariablesLowering(val context: LoweringContext) : BodyLoweringPass {
  override fun lower(irBody: IrBody, container: IrDeclaration) {
    SharedVariablesTransformer(irBody, container).lowerSharedVariables()
  }

  private inner class SharedVariablesTransformer(val irBody: IrBody, val irDeclaration: IrDeclaration) {
    private val sharedVariables = HashSet<IrVariable>()

    fun lowerSharedVariables() {
      collectSharedVariables()
      if (sharedVariables.isEmpty()) return

      rewriteSharedVariables()
    }

    private fun collectSharedVariables() {
      val skippedFunctionsParents = mutableMapOf<IrFunction, IrDeclarationParent>()
      irBody.accept(object : IrElementVisitor<Unit, IrDeclarationParent?> {
        val relevantVars = HashSet<IrVariable>()
        val relevantVals = HashSet<IrVariable>()

        override fun visitElement(element: IrElement, data: IrDeclarationParent?) {
          element.acceptChildren(this, data)
        }

        override fun visitCall(expression: IrCall, data: IrDeclarationParent?) {
          val callee = expression.symbol.owner
          if (!callee.isInline) {
            super.visitCall(expression, data)
            return
          }
          for (param in callee.parameters) {
            val arg = expression.arguments[param] ?: continue
            val toSkip = runIf(param.isInlineParameter() && !param.isCrossinline) {
              when (arg) {
                is IrFunctionExpression -> arg.function
                is IrRichFunctionReference -> arg.invokeFunction
                is IrRichPropertyReference -> arg.getterFunction
                else -> null
              }
            }
            if (toSkip != null) {
              skippedFunctionsParents[toSkip] = data!!
            }
            arg.accept(this, data)
            if (toSkip != null) {
              skippedFunctionsParents.remove(toSkip)
            }
          }
        }

        override fun visitDeclaration(declaration: IrDeclarationBase, data: IrDeclarationParent?) {
          super.visitDeclaration(declaration, declaration.takeIf { it !in skippedFunctionsParents } as? IrDeclarationParent ?: data)
        }

        override fun visitVariable(declaration: IrVariable, data: IrDeclarationParent?) {
          declaration.acceptChildren(this, data)

          if (declaration.isVar) {
            relevantVars.add(declaration)
          } else if (declaration.initializer == null) {
            // A val-variable can be initialized from another container (and thus can require shared variable transformation)
            // in case that container is a lambda with a corresponding contract, e.g. with invocation kind EXACTLY_ONCE.
            // Here, we collect all val-variables without immediate initializer to relevantVals, and later we copy only those
            // variables which are initialized in a foreign container, to sharedVariables.
            relevantVals.add(declaration)
          }
        }

        override fun visitValueAccess(expression: IrValueAccessExpression, data: IrDeclarationParent?) {
          expression.acceptChildren(this, data)

          val value = expression.symbol.owner
          if (value in relevantVars && getRealParent(value as IrVariable) != data) {
            sharedVariables.add(value)
          }
        }

        override fun visitSetValue(expression: IrSetValue, data: IrDeclarationParent?) {
          super.visitSetValue(expression, data)

          val variable = expression.symbol.owner
          if (variable is IrVariable && variable.initializer == null && getRealParent(variable) != data && variable in relevantVals) {
            sharedVariables.add(variable)
          }
        }

        private fun getRealParent(variable: IrVariable) =
          variable.parent.let { skippedFunctionsParents[it] ?: it }

      }, irDeclaration as? IrDeclarationParent ?: irDeclaration.parent)
    }

    private fun rewriteSharedVariables() {
      val transformedSymbols = HashMap<IrValueSymbol, IrVariableSymbol>()

      irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
        override fun visitVariable(declaration: IrVariable): IrStatement {
          declaration.transformChildrenVoid(this)

          if (declaration !in sharedVariables) return declaration

          val newDeclaration = context.sharedVariablesManager.declareSharedVariable(declaration)
          newDeclaration.parent = declaration.parent
          transformedSymbols[declaration.symbol] = newDeclaration.symbol

          return context.sharedVariablesManager.defineSharedValue(declaration, newDeclaration)
        }
      })

      irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
        override fun visitGetValue(expression: IrGetValue): IrExpression {
          expression.transformChildrenVoid(this)

          val newDeclaration = getTransformedSymbol(expression.symbol) ?: return expression

          return context.sharedVariablesManager.getSharedValue(newDeclaration, expression)
        }

        override fun visitSetValue(expression: IrSetValue): IrExpression {
          expression.transformChildrenVoid(this)

          val newDeclaration = getTransformedSymbol(expression.symbol) ?: return expression

          return context.sharedVariablesManager.setSharedValue(newDeclaration, expression)
        }

        private fun getTransformedSymbol(oldSymbol: IrValueSymbol): IrVariableSymbol? =
          transformedSymbols.getOrElse(oldSymbol) {
            assert(oldSymbol.owner !in sharedVariables) {
              "Shared variable is not transformed: ${oldSymbol.owner.dump()}"
            }
            null
          }
      })
    }
  }
}
