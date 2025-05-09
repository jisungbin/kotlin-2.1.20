/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower.inline

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.ir.isInlineLambdaBlock
import org.jetbrains.kotlin.backend.common.lower.LocalClassPopupLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.util.isAdaptedFunctionReference
import org.jetbrains.kotlin.ir.util.isInlineParameter
import org.jetbrains.kotlin.ir.util.setDeclarationsParent
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.IrTransformer
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Extracts local classes from inline lambdas.
 *
 * The mental model of inlining is as following:
 *  - for inline lambdas, since we don't see the keyword `inline` at the call site,
 *    it is logical to think that the lambda won't be copied but will be embedded as is at the call site,
 *    so all local classes declared in those inline lambdas are NEVER COPIED.
 *  - as for the bodies of inline functions, it is the opposite - we see the `inline` keyword,
 *    so it is only logical to think that this is a macro substitution, so the bodies of inline functions
 *    are copied. But the compiler could optimize the usage of some local classes and not copy them.
 *    So in this case all local classes MIGHT BE COPIED.
 */
@PhaseDescription("LocalClassesInInlineLambdasLowering")
class LocalClassesInInlineLambdasLowering(val context: LoweringContext) : BodyLoweringPass {
  override fun lower(irFile: IrFile) {
    runOnFilePostfix(irFile)
  }

  override fun lower(irBody: IrBody, container: IrDeclaration) {
    irBody.transformChildren(object : IrTransformer<IrDeclarationParent>() {
      override fun visitDeclaration(declaration: IrDeclarationBase, data: IrDeclarationParent) =
        super.visitDeclaration(declaration, (declaration as? IrDeclarationParent) ?: data)

      override fun visitCall(expression: IrCall, data: IrDeclarationParent): IrElement {
        val rootCallee = expression.symbol.owner
        if (!rootCallee.isInline)
          return super.visitCall(expression, data)

        val inlineLambdas = mutableListOf<IrFunction>()
        for (index in expression.arguments.indices) {
          val argument = expression.arguments[index]
          val inlineLambda = when (argument) {
            is IrFunctionExpression -> argument.function
            is IrRichPropertyReference -> argument.getterFunction
            is IrRichFunctionReference -> argument.invokeFunction
            else -> null
          }?.takeIf { rootCallee.parameters[index].isInlineParameter() }
          if (inlineLambda == null)
            expression.arguments[index] = argument?.transform(this, data)
          else
            inlineLambdas.add(inlineLambda)
        }

        val localClasses = mutableSetOf<IrClass>()
        val localFunctions = mutableSetOf<IrFunction>()
        val adaptedFunctions = mutableSetOf<IrSimpleFunction>()
        val transformer = this
        for (lambda in inlineLambdas) {
          lambda.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
              element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
              declaration.transformChildren(transformer, declaration)

              localClasses.add(declaration)
            }

            override fun visitFunctionExpression(expression: IrFunctionExpression) {
              expression.function.acceptChildrenVoid(this)
            }

            override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
              expression.boundValues.forEach { it.acceptVoid(this) }
              expression.invokeFunction.acceptChildrenVoid(this)
            }

            override fun visitRichPropertyReference(expression: IrRichPropertyReference) {
              expression.boundValues.forEach { it.acceptVoid(this) }
              expression.getterFunction.acceptChildrenVoid(this)
              expression.setterFunction?.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
              declaration.transformChildren(transformer, declaration)

              localFunctions.add(declaration)
            }

            override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
              // Do not extract local delegates from the inline function.
              // Doing that can lead to inconsistent IR. Local delegated property consists of two elements: property and
              // accessors to it. Inside the accessor we have a reference to the property.
              // `LocalClassesInInlineLambdasLowering` can only extract the accessor out of inline lambda, leaving the
              // property in place. Because of this, we have not entirely correct reference.
              return
            }

            override fun visitCall(expression: IrCall) {
              val callee = expression.symbol.owner
              if (!callee.isInline) {
                expression.acceptChildrenVoid(this)
                return
              }

              expression.arguments.zip(callee.parameters).forEach { (argument, parameter) ->
                // Skip adapted function references and inline lambdas - they will be inlined later.
                val shouldSkip = argument != null && (argument.isAdaptedFunctionReference() || argument.isInlineLambdaBlock())
                if (parameter.isInlineParameter() && shouldSkip)
                  adaptedFunctions += (argument as IrBlock).statements[0] as IrSimpleFunction
                else
                  argument?.acceptVoid(this)
              }
            }
          })
        }

        if (localClasses.isEmpty() && localFunctions.isEmpty())
          return expression

        val irBlock = IrBlockImpl(expression.startOffset, expression.endOffset, expression.type).apply {
          statements += expression
        }
        LocalDeclarationsLowering(context).lower(irBlock, container, data, localClasses, adaptedFunctions)
        irBlock.statements.addAll(0, localClasses)

        for (lambda in inlineLambdas) {
          lambda.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitClass(declaration: IrClass): IrStatement {
              return IrCompositeImpl(
                declaration.startOffset, declaration.endOffset,
                context.irBuiltIns.unitType
              )
            }
          })
        }
        localClasses.forEach { it.setDeclarationsParent(data) }

        return irBlock
      }
    }, container as? IrDeclarationParent ?: container.parent)
  }
}

private fun IrFunction.collectExtractableLocalClassesInto(classesToExtract: MutableSet<IrClass>) {
  if (!isInline) return
  // Conservatively assume that functions with reified type parameters must be copied.
  if (typeParameters.any { it.isReified }) return

  val crossinlineParameters = parameters.filter { it.isCrossinline }.toSet()
  acceptChildrenVoid(object : IrElementVisitorVoid {
    override fun visitElement(element: IrElement) {
      element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
      var canExtract = true
      if (crossinlineParameters.isNotEmpty()) {
        declaration.acceptVoid(object : IrElementVisitorVoid {
          override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
          }

          override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol.owner in crossinlineParameters)
              canExtract = false
          }
        })
      }
      if (canExtract)
        classesToExtract.add(declaration)
    }
  })
}

/**
 * Rewrites local classes so that they don't capture any locals. Locals are passed to the class explicitly, and usages of those locals
 * inside the class are replaced with accesses to the class fields.
 */
class LocalClassesInInlineFunctionsLowering(val context: LoweringContext) : BodyLoweringPass {
  override fun lower(irFile: IrFile) {
    runOnFilePostfix(irFile)
  }

  override fun lower(irBody: IrBody, container: IrDeclaration) {
    val function = container as? IrFunction ?: return
    val classesToExtract = mutableSetOf<IrClass>()
    function.collectExtractableLocalClassesInto(classesToExtract)
    if (classesToExtract.isEmpty())
      return

    LocalDeclarationsLowering(context).lower(function, function, classesToExtract)
  }
}

/**
 * Moves local classes from inline functions into the nearest declaration container.
 */
class LocalClassesExtractionFromInlineFunctionsLowering(context: LoweringContext) : LocalClassPopupLowering(context) {
  private val classesToExtract = mutableSetOf<IrClass>()

  override fun lower(irBody: IrBody, container: IrDeclaration) {
    val function = container as? IrFunction ?: return
    function.collectExtractableLocalClassesInto(classesToExtract)
    if (classesToExtract.isEmpty())
      return

    super.lower(irBody, container)

    classesToExtract.clear()
  }

  override fun shouldPopUp(klass: IrClass, currentScope: ScopeWithIr?): Boolean {
    return classesToExtract.contains(klass)
  }
}
