/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.applyIf
import java.io.File
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrErrorDeclaration
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrReplSnippet
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.declarations.path
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstantArray
import org.jetbrains.kotlin.ir.expressions.IrConstantObject
import org.jetbrains.kotlin.ir.expressions.IrConstantPrimitive
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrDynamicMemberExpression
import org.jetbrains.kotlin.ir.expressions.IrDynamicOperatorExpression
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorCallExpression
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetClass
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInlinedFunctionBlock
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrRawFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBody
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeAliasSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeAbbreviation
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.impl.IrCapturedType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.SpecialNames.IMPLICIT_SET_PARAMETER
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlin.utils.addToStdlib.runUnless

fun IrElement.render(options: DumpIrTreeOptions = DumpIrTreeOptions()) =
  accept(RenderIrElementVisitor(options), null)

open class RenderIrElementVisitor(private val options: DumpIrTreeOptions = DumpIrTreeOptions()) :
  IrElementVisitor<String, Nothing?> {

  private val flagsRenderer = FlagsRenderer(options.declarationFlagsFilter, isReference = false)
  private val variableNameData = VariableNameData(options.normalizeNames)
  private var hideParameterNames = false

  fun withHiddenParameterNames(block: () -> Unit) {
    val oldHideParameterNames = hideParameterNames
    try {
      hideParameterNames = !options.printParameterNamesInOverriddenSymbols
      block()
    } finally {
      hideParameterNames = oldHideParameterNames
    }
  }

  fun renderFileEntry(fileEntry: IrFileEntry): String {
    val fullPath = fileEntry.name
    val renderedPath = if (options.printFilePath) fullPath else File(fullPath).name

    // TODO: use offsets in IR deserialization tests, KT-73171
    return "FILE_ENTRY path:$renderedPath"
  }

  fun renderType(type: IrType) = type.renderTypeWithRenderer(this@RenderIrElementVisitor, options)

  fun renderSymbolReference(symbol: IrSymbol) = symbol.renderReference()

  fun renderAsAnnotation(irAnnotation: IrConstructorCall): String =
    StringBuilder().also { it.renderAsAnnotation(irAnnotation, this, options) }.toString()

  private fun IrType.render(): String =
    this.renderTypeWithRenderer(this@RenderIrElementVisitor, options)

  private fun IrSymbol.renderReference() =
    if (isBound)
      owner.accept(BoundSymbolReferenceRenderer(variableNameData, hideParameterNames, options), null)
    else
      "UNBOUND ${javaClass.simpleName}"

  private class BoundSymbolReferenceRenderer(
    private val variableNameData: VariableNameData,
    private val hideParameterNames: Boolean,
    private val options: DumpIrTreeOptions,
  ) : IrElementVisitor<String, Nothing?> {

    private val flagsRenderer = FlagsRenderer(options.declarationFlagsFilter, isReference = true)

    override fun visitElement(element: IrElement, data: Nothing?) = buildTrimEnd {
      append('{')
      append(element.javaClass.simpleName)
      append('}')
      if (element is IrDeclaration) {
        if (element is IrDeclarationWithName) {
          append(element.name)
          append(' ')
        }
        renderDeclaredIn(element)
      }
    }

    override fun visitTypeParameter(declaration: IrTypeParameter, data: Nothing?): String =
      renderTypeParameter(declaration, null, options)

    override fun visitClass(declaration: IrClass, data: Nothing?) =
      renderClassWithRenderer(declaration, null, flagsRenderer, options)

    override fun visitEnumEntry(declaration: IrEnumEntry, data: Nothing?) =
      renderEnumEntry(declaration, options)

    override fun visitField(declaration: IrField, data: Nothing?) = buildTrimEnd {
      append(renderField(declaration, null, flagsRenderer, options))
      if (declaration.origin != IrDeclarationOrigin.PROPERTY_BACKING_FIELD) {
        append(" ")
        renderDeclaredIn(declaration)
      }
    }

    override fun visitVariable(declaration: IrVariable, data: Nothing?) =
      buildTrimEnd {
        if (declaration.isVar) append("var ") else append("val ")

        append(declaration.normalizedName(variableNameData))
        append(": ")
        append(declaration.type.renderTypeWithRenderer(null, options))
        append(' ')
        append(declaration.renderVariableFlags(flagsRenderer))

        renderDeclaredIn(declaration)
      }

    override fun visitValueParameter(declaration: IrValueParameter, data: Nothing?) =
      buildTrimEnd {
        runUnless(hideParameterNames) {
          append(declaration.renderValueParameterName(options))
          append(": ")
        }
        append(declaration.type.renderTypeWithRenderer(null, options))
        append(' ')
        append(declaration.renderValueParameterFlags(flagsRenderer))
        renderDeclaredIn(declaration)
      }

    override fun visitFunction(declaration: IrFunction, data: Nothing?) =
      buildTrimEnd {
        append(declaration.visibility)
        append(' ')

        if (declaration is IrSimpleFunction) {
          append(declaration.modality.toString().toLowerCaseAsciiOnly())
          append(' ')
        }

        when (declaration) {
          is IrSimpleFunction -> append("fun ")
          is IrConstructor -> append("constructor ")
        }

        append(declaration.name.asString())
        append(' ')

        renderTypeParameters(declaration)

        appendIterableWith(declaration.valueParameters, "(", ")", ", ") { valueParameter ->
          val varargElementType = valueParameter.varargElementType
          if (varargElementType != null) {
            append("vararg ")
            runUnless(hideParameterNames) {
              append(valueParameter.renderValueParameterName(options))
              append(": ")
            }
            append(varargElementType.renderTypeWithRenderer(null, options))
          } else {
            runUnless(hideParameterNames) {
              append(valueParameter.renderValueParameterName(options))
              append(": ")
            }
            append(valueParameter.type.renderTypeWithRenderer(null, options))
          }
        }

        if (declaration is IrSimpleFunction) {
          append(": ")
          append(declaration.renderReturnType(null, options))
        }
        append(' ')

        when (declaration) {
          is IrSimpleFunction -> append(declaration.renderSimpleFunctionFlags(flagsRenderer))
          is IrConstructor -> append(declaration.renderConstructorFlags(flagsRenderer))
        }

        renderDeclaredIn(declaration)
      }

    private fun StringBuilder.renderTypeParameters(declaration: IrTypeParametersContainer) {
      if (declaration.typeParameters.isNotEmpty()) {
        appendIterableWith(declaration.typeParameters, "<", ">", ", ") { typeParameter ->
          append(typeParameter.name.asString())
        }
        append(' ')
      }
    }

    override fun visitProperty(declaration: IrProperty, data: Nothing?) =
      buildTrimEnd {
        append(declaration.visibility)
        append(' ')
        append(declaration.modality.toString().toLowerCaseAsciiOnly())
        append(' ')

        append(declaration.name.asString())

        val getter = declaration.getter
        if (getter != null) {
          append(": ")
          append(getter.renderReturnType(null, options))
        } else declaration.backingField?.type?.let { type ->
          append(": ")
          append(type.renderTypeWithRenderer(null, options))
        }
        append(' ')

        append(declaration.renderPropertyFlags(flagsRenderer))

        renderDeclaredIn(declaration)
      }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty, data: Nothing?): String =
      buildTrimEnd {
        if (declaration.isVar) append("var ") else append("val ")
        append(declaration.name.asString())
        append(": ")
        append(declaration.type.renderTypeWithRenderer(null, options))
        append(" by (...)")
      }

    private fun StringBuilder.renderDeclaredIn(irDeclaration: IrDeclaration) {
      append("declared in ")
      renderParentOfReferencedDeclaration(irDeclaration)
    }

    private fun StringBuilder.renderParentOfReferencedDeclaration(declaration: IrDeclaration) {
      val parent = try {
        declaration.parent
      } catch (e: Exception) {
        append("<no parent>")
        return
      }
      when (parent) {
        is IrPackageFragment -> {
          val fqn = parent.packageFqName.asString()
          append(fqn.ifEmpty { "<root>" })
        }
        is IrDeclaration -> {
          renderParentOfReferencedDeclaration(parent)
          appendDeclarationNameToFqName(parent, options) {
            renderElementNameFallback(parent)
          }
        }
        else ->
          renderElementNameFallback(parent)
      }
    }

    private fun StringBuilder.renderElementNameFallback(element: Any) {
      append('{')
      append(element.javaClass.simpleName)
      append('}')
    }
  }

  override fun visitElement(element: IrElement, data: Nothing?): String =
    "?ELEMENT? ${element::class.java.simpleName} $element"

  override fun visitDeclaration(declaration: IrDeclarationBase, data: Nothing?): String =
    "?DECLARATION? ${declaration::class.java.simpleName} $declaration"

  override fun visitModuleFragment(declaration: IrModuleFragment, data: Nothing?): String {
    return buildString {
      append("MODULE_FRAGMENT")
      if (options.printModuleName) {
        append(" name:").append(declaration.name)
      }
    }
  }

  override fun visitExternalPackageFragment(declaration: IrExternalPackageFragment, data: Nothing?): String =
    "EXTERNAL_PACKAGE_FRAGMENT fqName:${declaration.packageFqName}"

  override fun visitFile(declaration: IrFile, data: Nothing?): String {
    val fileName = if (options.printFilePath) declaration.path else declaration.name
    return "FILE fqName:${declaration.packageFqName} fileName:$fileName"
  }

  override fun visitFunction(declaration: IrFunction, data: Nothing?): String =
    declaration.runTrimEnd {
      "FUN ${renderOriginIfNonTrivial(options)}"
    }

  override fun visitScript(declaration: IrScript, data: Nothing?) = "SCRIPT"

  override fun visitReplSnippet(declaration: IrReplSnippet, data: Nothing?): String = "REPL_SNIPPET name:${declaration.name}"

  override fun visitSimpleFunction(declaration: IrSimpleFunction, data: Nothing?): String =
    declaration.runTrimEnd {
      "FUN ${renderOriginIfNonTrivial(options)}" +
        "name:$name " +
        renderSignatureIfEnabled(options.printSignatures) +
        "visibility:$visibility modality:$modality " +
        renderTypeParameters() + " " +
        renderValueParameterTypes() + " " +
        "returnType:${renderReturnType(this@RenderIrElementVisitor, options)} " +
        renderSimpleFunctionFlags(flagsRenderer)
    }

  private fun IrFunction.renderValueParameterTypes(): String = buildList {
    addIfNotNull(dispatchReceiverParameter?.run { "\$this:${renderValueParameterType(options)}" })
    addIfNotNull(extensionReceiverParameter?.run { "\$receiver:${type.render()}" })
    valueParameters.mapTo(this) { "${it.renderValueParameterName(options)}:${it.type.render()}" }
  }.joinToString(separator = ", ", prefix = "(", postfix = ")")

  override fun visitConstructor(declaration: IrConstructor, data: Nothing?): String =
    declaration.runTrimEnd {
      "CONSTRUCTOR ${renderOriginIfNonTrivial(options)}" +
        renderSignatureIfEnabled(options.printSignatures) +
        "visibility:$visibility " +
        renderTypeParameters() + " " +
        renderValueParameterTypes() + " " +
        "returnType:${renderReturnType(this@RenderIrElementVisitor, options)} " +
        renderConstructorFlags(flagsRenderer)
    }

  override fun visitProperty(declaration: IrProperty, data: Nothing?): String =
    declaration.runTrimEnd {
      "PROPERTY ${renderOriginIfNonTrivial(options)}" +
        "name:$name " +
        renderSignatureIfEnabled(options.printSignatures) +
        "visibility:$visibility modality:$modality " +
        renderPropertyFlags(flagsRenderer)
    }

  override fun visitField(declaration: IrField, data: Nothing?): String =
    renderField(declaration, this, flagsRenderer, options)

  override fun visitClass(declaration: IrClass, data: Nothing?): String =
    renderClassWithRenderer(declaration, this, flagsRenderer, options)

  override fun visitVariable(declaration: IrVariable, data: Nothing?): String =
    declaration.runTrimEnd {
      "VAR ${renderOriginIfNonTrivial(options)}name:${normalizedName(variableNameData)} type:${type.render()} " +
        renderVariableFlags(flagsRenderer)
    }

  override fun visitEnumEntry(declaration: IrEnumEntry, data: Nothing?): String =
    renderEnumEntry(declaration, options)

  override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer, data: Nothing?): String =
    "ANONYMOUS_INITIALIZER isStatic=${declaration.isStatic}"

  override fun visitTypeParameter(declaration: IrTypeParameter, data: Nothing?): String =
    renderTypeParameter(declaration, this, options)

  override fun visitValueParameter(declaration: IrValueParameter, data: Nothing?): String =
    declaration.runTrimEnd {
      "VALUE_PARAMETER ${renderOriginIfNonTrivial(options)}" +
        "name:${renderValueParameterName(options)} " +
        (if (indexInOldValueParameters >= 0) "index:$indexInOldValueParameters " else "") +
        "type:${renderValueParameterType(options)} " +
        (varargElementType?.let { "varargElementType:${it.render()} " } ?: "") +
        renderValueParameterFlags(flagsRenderer)
    }

  override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty, data: Nothing?): String =
    declaration.runTrimEnd {
      "LOCAL_DELEGATED_PROPERTY ${declaration.renderOriginIfNonTrivial(options)}" +
        "name:$name type:${type.render()} flags:${renderLocalDelegatedPropertyFlags()}"
    }

  override fun visitTypeAlias(declaration: IrTypeAlias, data: Nothing?): String =
    declaration.run {
      "TYPEALIAS ${declaration.renderOriginIfNonTrivial(options)}" +
        "name:$name " +
        renderSignatureIfEnabled(options.printSignatures) +
        "visibility:$visibility expandedType:${expandedType.render()}" +
        renderTypeAliasFlags(flagsRenderer)
    }

  override fun visitExpressionBody(body: IrExpressionBody, data: Nothing?): String =
    "EXPRESSION_BODY"

  override fun visitBlockBody(body: IrBlockBody, data: Nothing?): String =
    "BLOCK_BODY"

  override fun visitSyntheticBody(body: IrSyntheticBody, data: Nothing?): String =
    "SYNTHETIC_BODY kind=${body.kind}"

  override fun visitExpression(expression: IrExpression, data: Nothing?): String =
    "? ${expression::class.java.simpleName} type=${expression.type.render()}"

  override fun visitConst(expression: IrConst, data: Nothing?): String =
    "CONST ${expression.kind} type=${expression.type.render()} value=${expression.value?.escapeIfRequired()}"

  private fun Any.escapeIfRequired() =
    when (this) {
      is String -> "\"${StringUtil.escapeStringCharacters(this)}\""
      is Char -> "'${StringUtil.escapeStringCharacters(this.toString())}'"
      else -> this
    }

  override fun visitVararg(expression: IrVararg, data: Nothing?): String =
    "VARARG type=${expression.type.render()} varargElementType=${expression.varargElementType.render()}"

  override fun visitSpreadElement(spread: IrSpreadElement, data: Nothing?): String =
    "SPREAD_ELEMENT"

  override fun visitBlock(expression: IrBlock, data: Nothing?): String {
    val prefix = when (expression) {
      is IrReturnableBlock -> "RETURNABLE_"
      is IrInlinedFunctionBlock -> "INLINED_"
      else -> ""
    }
    return "${prefix}BLOCK type=${expression.type.render()} origin=${expression.origin}"
  }

  override fun visitComposite(expression: IrComposite, data: Nothing?): String =
    "COMPOSITE type=${expression.type.render()} origin=${expression.origin}"

  override fun visitReturn(expression: IrReturn, data: Nothing?): String =
    "RETURN type=${expression.type.render()} from='${expression.returnTargetSymbol.renderReference()}'"

  override fun visitCall(expression: IrCall, data: Nothing?): String =
    "CALL '${expression.symbol.renderReference()}' ${expression.renderSuperQualifier()}" +
      "type=${expression.type.render()} origin=${expression.origin}"

  private fun IrCall.renderSuperQualifier(): String =
    superQualifierSymbol?.let { "superQualifier='${it.renderReference()}' " } ?: ""

  override fun visitConstructorCall(expression: IrConstructorCall, data: Nothing?): String =
    "CONSTRUCTOR_CALL '${expression.symbol.renderReference()}' type=${expression.type.render()} origin=${expression.origin}"

  override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: Nothing?): String =
    "DELEGATING_CONSTRUCTOR_CALL '${expression.symbol.renderReference()}'"

  override fun visitEnumConstructorCall(expression: IrEnumConstructorCall, data: Nothing?): String =
    "ENUM_CONSTRUCTOR_CALL '${expression.symbol.renderReference()}'"

  override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall, data: Nothing?): String =
    "INSTANCE_INITIALIZER_CALL classDescriptor='${expression.classSymbol.renderReference()}' type=${expression.type.render()}"

  override fun visitGetValue(expression: IrGetValue, data: Nothing?): String =
    "GET_VAR '${expression.symbol.renderReference()}' type=${expression.type.render()} origin=${expression.origin}"

  override fun visitSetValue(expression: IrSetValue, data: Nothing?): String =
    "SET_VAR '${expression.symbol.renderReference()}' type=${expression.type.render()} origin=${expression.origin}"

  override fun visitGetField(expression: IrGetField, data: Nothing?): String = buildTrimEnd {
    append("GET_FIELD '${expression.symbol.renderReference()}' type=${expression.type.render()}")
    appendSuperQualifierSymbol(expression)
    append(" origin=${expression.origin}")
  }

  override fun visitSetField(expression: IrSetField, data: Nothing?): String = buildTrimEnd {
    append("SET_FIELD '${expression.symbol.renderReference()}' type=${expression.type.render()}")
    appendSuperQualifierSymbol(expression)
    append(" origin=${expression.origin}")
  }

  private fun StringBuilder.appendSuperQualifierSymbol(expression: IrFieldAccessExpression) {
    val superQualifierSymbol = expression.superQualifierSymbol ?: return
    append(" superQualifierSymbol=")
    superQualifierSymbol.owner.renderDeclarationFqn(this, options)
  }

  override fun visitGetObjectValue(expression: IrGetObjectValue, data: Nothing?): String =
    "GET_OBJECT '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  override fun visitGetEnumValue(expression: IrGetEnumValue, data: Nothing?): String =
    "GET_ENUM '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  override fun visitStringConcatenation(expression: IrStringConcatenation, data: Nothing?): String =
    "STRING_CONCATENATION type=${expression.type.render()}"

  override fun visitTypeOperator(expression: IrTypeOperatorCall, data: Nothing?): String =
    "TYPE_OP type=${expression.type.render()} origin=${expression.operator} typeOperand=${expression.typeOperand.render()}"

  override fun visitWhen(expression: IrWhen, data: Nothing?): String =
    "WHEN type=${expression.type.render()} origin=${expression.origin}"

  override fun visitBranch(branch: IrBranch, data: Nothing?): String =
    "BRANCH"

  override fun visitWhileLoop(loop: IrWhileLoop, data: Nothing?): String =
    "WHILE label=${loop.label} origin=${loop.origin}"

  override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: Nothing?): String =
    "DO_WHILE label=${loop.label} origin=${loop.origin}"

  override fun visitBreak(jump: IrBreak, data: Nothing?): String =
    "BREAK label=${jump.label} loop.label=${jump.loop.label}"

  override fun visitContinue(jump: IrContinue, data: Nothing?): String =
    "CONTINUE label=${jump.label} loop.label=${jump.loop.label}"

  override fun visitThrow(expression: IrThrow, data: Nothing?): String =
    "THROW type=${expression.type.render()}"

  override fun visitFunctionReference(expression: IrFunctionReference, data: Nothing?): String =
    "FUNCTION_REFERENCE '${expression.symbol.renderReference()}' " +
      "type=${expression.type.render()} origin=${expression.origin} " +
      "reflectionTarget=${renderReflectionTarget(expression)}"

  override fun visitRichFunctionReference(expression: IrRichFunctionReference, data: Nothing?): String =
    "RICH_FUNCTION_REFERENCE type=${expression.type.render()} origin=${expression.origin} " +
      renderFlagsListWithoutFiltering(
        "unit_conversion".takeIf { expression.hasUnitConversion },
        "suspend_conversion".takeIf { expression.hasSuspendConversion },
        "vararg_conversion".takeIf { expression.hasVarargConversion },
        "restricted_suspension".takeIf { expression.isRestrictedSuspension },
      ) + "reflectionTarget='${expression.reflectionTargetSymbol?.renderReference()}'"

  override fun visitRichPropertyReference(expression: IrRichPropertyReference, data: Nothing?): String =
    "RICH_PROPERTY_REFERENCE type=${expression.type.render()} origin=${expression.origin} " +
      "reflectionTarget='${expression.reflectionTargetSymbol?.renderReference()}'"

  override fun visitRawFunctionReference(expression: IrRawFunctionReference, data: Nothing?): String =
    "RAW_FUNCTION_REFERENCE '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  private fun renderReflectionTarget(expression: IrFunctionReference) =
    if (expression.symbol == expression.reflectionTarget)
      "<same>"
    else
      expression.reflectionTarget?.renderReference()

  override fun visitPropertyReference(expression: IrPropertyReference, data: Nothing?): String =
    buildTrimEnd {
      append("PROPERTY_REFERENCE ")
      append("'${expression.symbol.renderReference()}' ")
      appendNullableAttribute("field=", expression.field) { "'${it.renderReference()}'" }
      appendNullableAttribute("getter=", expression.getter) { "'${it.renderReference()}'" }
      appendNullableAttribute("setter=", expression.setter) { "'${it.renderReference()}'" }
      append("type=${expression.type.render()} ")
      append("origin=${expression.origin}")
    }

  private inline fun <T : Any> StringBuilder.appendNullableAttribute(prefix: String, value: T?, toString: (T) -> String) {
    append(prefix)
    if (value != null) {
      append(toString(value))
    } else {
      append("null")
    }
    append(" ")
  }

  override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference, data: Nothing?): String =
    buildTrimEnd {
      append("LOCAL_DELEGATED_PROPERTY_REFERENCE ")
      append("'${expression.symbol.renderReference()}' ")
      append("delegate='${expression.delegate.renderReference()}' ")
      append("getter='${expression.getter.renderReference()}' ")
      appendNullableAttribute("setter=", expression.setter) { "'${it.renderReference()}'" }
      append("type=${expression.type.render()} ")
      append("origin=${expression.origin}")
    }

  override fun visitFunctionExpression(expression: IrFunctionExpression, data: Nothing?): String =
    buildTrimEnd {
      append("FUN_EXPR type=${expression.type.render()} origin=${expression.origin}")
    }

  override fun visitClassReference(expression: IrClassReference, data: Nothing?): String =
    "CLASS_REFERENCE '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  override fun visitGetClass(expression: IrGetClass, data: Nothing?): String =
    "GET_CLASS type=${expression.type.render()}"

  override fun visitTry(aTry: IrTry, data: Nothing?): String =
    "TRY type=${aTry.type.render()}"

  override fun visitCatch(aCatch: IrCatch, data: Nothing?): String =
    "CATCH parameter=${aCatch.catchParameter.symbol.renderReference()}"

  override fun visitDynamicOperatorExpression(expression: IrDynamicOperatorExpression, data: Nothing?): String =
    "DYN_OP operator=${expression.operator} type=${expression.type.render()}"

  override fun visitDynamicMemberExpression(expression: IrDynamicMemberExpression, data: Nothing?): String =
    "DYN_MEMBER memberName='${expression.memberName}' type=${expression.type.render()}"

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  override fun visitErrorDeclaration(declaration: IrErrorDeclaration, data: Nothing?): String =
    "ERROR_DECL ${declaration.descriptor::class.java.simpleName} " +
      descriptorRendererForErrorDeclarations.renderDescriptor(declaration.descriptor.original)

  override fun visitErrorExpression(expression: IrErrorExpression, data: Nothing?): String =
    "ERROR_EXPR '${expression.description}' type=${expression.type.render()}"

  override fun visitErrorCallExpression(expression: IrErrorCallExpression, data: Nothing?): String =
    "ERROR_CALL '${expression.description}' type=${expression.type.render()}"

  override fun visitConstantArray(expression: IrConstantArray, data: Nothing?): String =
    "CONSTANT_ARRAY type=${expression.type.render()}"

  override fun visitConstantObject(expression: IrConstantObject, data: Nothing?): String =
    "CONSTANT_OBJECT type=${expression.type.render()} constructor=${expression.constructor.renderReference()}"

  override fun visitConstantPrimitive(expression: IrConstantPrimitive, data: Nothing?): String =
    "CONSTANT_PRIMITIVE type=${expression.type.render()}"


  private val descriptorRendererForErrorDeclarations = DescriptorRenderer.ONLY_NAMES_WITH_SHORT_TYPES
}

private fun IrValueParameter.renderValueParameterType(options: DumpIrTreeOptions): String {
  return if (!options.printDispatchReceiverTypeInFakeOverrides &&
    kind == IrParameterKind.DispatchReceiver &&
    (parent as? IrFunction)?.isFakeOverride == true
  ) {
    "HIDDEN_DISPATCH_RECEIVER_TYPE"
  } else {
    type.render(options)
  }
}

internal fun IrValueParameter.renderValueParameterName(options: DumpIrTreeOptions): String {
  val name = runIf(name == IMPLICIT_SET_PARAMETER) { options.replaceImplicitSetterParameterNameWith } ?: name
  return name.asString()
}

internal fun DescriptorRenderer.renderDescriptor(descriptor: DeclarationDescriptor): String =
  if (descriptor is ReceiverParameterDescriptor)
    "this@${descriptor.containingDeclaration.name}: ${descriptor.type}"
  else
    render(descriptor)

private fun IrDeclarationWithName.renderSignatureIfEnabled(printSignatures: Boolean): String =
  if (printSignatures) symbol.signature?.let { "signature:${it.render()} " }.orEmpty() else ""

internal fun IrDeclaration.renderOriginIfNonTrivial(options: DumpIrTreeOptions): String {
  val originsToSkipFromRendering: HashSet<IrDeclarationOrigin> = hashSetOf(IrDeclarationOrigin.DEFINED)
  if (!options.renderOriginForExternalDeclarations) {
    originsToSkipFromRendering.add(IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB)
  }
  return if (origin in originsToSkipFromRendering) "" else "$origin "
}

internal fun IrClassifierSymbol.renderClassifierFqn(options: DumpIrTreeOptions): String =
  if (isBound)
    when (val owner = owner) {
      is IrClass -> owner.renderClassFqn(options)
      is IrScript -> owner.renderScriptFqn(options)
      is IrTypeParameter -> owner.renderTypeParameterFqn(options)
      else -> "`unexpected classifier: ${owner.render(options)}`"
    }
  else
    "<unbound ${this.javaClass.simpleName}>"

internal fun IrTypeAliasSymbol.renderTypeAliasFqn(options: DumpIrTreeOptions): String =
  if (isBound)
    StringBuilder().also { owner.renderDeclarationFqn(it, options) }.toString()
  else
    "<unbound $this>"

internal fun IrClass.renderClassFqn(options: DumpIrTreeOptions): String =
  StringBuilder().also { renderDeclarationFqn(it, options) }.toString()

internal fun IrScript.renderScriptFqn(options: DumpIrTreeOptions): String =
  StringBuilder().also { renderDeclarationFqn(it, options) }.toString()

internal fun IrTypeParameter.renderTypeParameterFqn(options: DumpIrTreeOptions): String =
  StringBuilder().also { sb ->
    sb.append(name.asString())
    sb.append(" of ")
    renderDeclarationParentFqn(sb, options)
  }.toString()

private inline fun StringBuilder.appendDeclarationNameToFqName(
  declaration: IrDeclaration,
  options: DumpIrTreeOptions,
  fallback: () -> Unit,
) {
  if (!declaration.isFileClass || options.printFacadeClassInFqNames) {
    append('.')
    if (declaration is IrDeclarationWithName) {
      append(declaration.name)
    } else {
      fallback()
    }
  }
}

private fun IrDeclaration.renderDeclarationFqn(sb: StringBuilder, options: DumpIrTreeOptions) {
  renderDeclarationParentFqn(sb, options)
  sb.appendDeclarationNameToFqName(this, options) {
    sb.append(this)
  }
}

private fun IrDeclaration.renderDeclarationParentFqn(sb: StringBuilder, options: DumpIrTreeOptions) {
  try {
    val parent = this.parent
    if (parent is IrDeclaration) {
      parent.renderDeclarationFqn(sb, options)
    } else if (parent is IrPackageFragment) {
      sb.append(parent.packageFqName.toString())
    }
  } catch (e: UninitializedPropertyAccessException) {
    sb.append("<uninitialized parent>")
  }
}

fun IrType.render(options: DumpIrTreeOptions = DumpIrTreeOptions()) =
  renderTypeWithRenderer(RenderIrElementVisitor(options), options)

fun IrSimpleType.render(options: DumpIrTreeOptions = DumpIrTreeOptions()) = (this as IrType).render(options)

fun IrTypeArgument.render(options: DumpIrTreeOptions = DumpIrTreeOptions()) =
  when (this) {
    is IrStarProjection -> "*"
    is IrTypeProjection -> "$variance ${type.render(options)}"
  }

internal inline fun <T, Buffer : Appendable> Buffer.appendIterableWith(
  iterable: Iterable<T>,
  prefix: String,
  postfix: String,
  separator: String,
  renderItem: Buffer.(T) -> Unit,
) {
  append(prefix)
  var isFirst = true
  for (item in iterable) {
    if (!isFirst) append(separator)
    renderItem(item)
    isFirst = false
  }
  append(postfix)
}

private inline fun buildTrimEnd(fn: StringBuilder.() -> Unit): String =
  buildString(fn).trimEnd()

private inline fun <T> T.runTrimEnd(fn: T.() -> String): String =
  run(fn).trimEnd()

private class FlagsRenderer(
  private val flagsFilter: DumpIrTreeOptions.FlagsFilter,
  private val isReference: Boolean,
) {
  fun renderFlagsList(declaration: IrDeclaration, vararg flags: String?): String {
    val flagsList = flagsFilter.filterFlags(declaration, isReference, flags.filterNotNull())
    if (flagsList.isEmpty()) return ""
    return flagsList.joinToString(prefix = "[", postfix = "] ", separator = ",")
  }
}

private fun renderFlagsListWithoutFiltering(vararg flags: String?) =
  flags.filterNotNull().run {
    if (isNotEmpty())
      joinToString(prefix = "[", postfix = "] ", separator = ",")
    else
      ""
  }

private fun IrClass.renderClassFlags(renderer: FlagsRenderer) =
  renderer.renderFlagsList(
    declaration = this,
    "companion".takeIf { isCompanion },
    "inner".takeIf { isInner },
    "data".takeIf { isData },
    "external".takeIf { isExternal },
    "value".takeIf { isValue },
    "expect".takeIf { isExpect },
    "fun".takeIf { isFun }
  )

private fun IrField.renderFieldFlags(renderer: FlagsRenderer) =
  renderer.renderFlagsList(
    declaration = this,
    "final".takeIf { isFinal },
    "external".takeIf { isExternal },
    "static".takeIf { isStatic },
  )

private fun IrSimpleFunction.renderSimpleFunctionFlags(renderer: FlagsRenderer): String =
  renderer.renderFlagsList(
    declaration = this,
    "tailrec".takeIf { isTailrec },
    "inline".takeIf { isInline },
    "external".takeIf { isExternal },
    "suspend".takeIf { isSuspend },
    "expect".takeIf { isExpect },
    "fake_override".takeIf { isFakeOverride },
    "operator".takeIf { isOperator },
    "infix".takeIf { isInfix }
  )

private fun IrConstructor.renderConstructorFlags(renderer: FlagsRenderer) =
  renderer.renderFlagsList(
    declaration = this,
    "inline".takeIf { isInline },
    "external".takeIf { isExternal },
    "primary".takeIf { isPrimary },
    "expect".takeIf { isExpect }
  )

private fun IrProperty.renderPropertyFlags(renderer: FlagsRenderer) =
  renderer.renderFlagsList(
    declaration = this,
    "external".takeIf { isExternal },
    "const".takeIf { isConst },
    "lateinit".takeIf { isLateinit },
    "delegated".takeIf { isDelegated },
    "expect".takeIf { isExpect },
    "fake_override".takeIf { isFakeOverride },
    if (isVar) "var" else "val"
  )

private fun IrVariable.renderVariableFlags(renderer: FlagsRenderer): String =
  renderer.renderFlagsList(
    declaration = this,
    "const".takeIf { isConst },
    "lateinit".takeIf { isLateinit },
    if (isVar) "var" else "val"
  )

private fun IrValueParameter.renderValueParameterFlags(renderer: FlagsRenderer): String =
  renderer.renderFlagsList(
    declaration = this,
    "vararg".takeIf { varargElementType != null },
    "crossinline".takeIf { isCrossinline },
    "noinline".takeIf { isNoinline },
    "assignable".takeIf { isAssignable }
  )

private fun IrTypeAlias.renderTypeAliasFlags(renderer: FlagsRenderer): String =
  renderer.renderFlagsList(
    declaration = this,
    "actual".takeIf { isActual }
  )

private fun IrFunction.renderTypeParameters(): String =
  typeParameters.joinToString(separator = ", ", prefix = "<", postfix = ">") { it.name.toString() }

private val IrFunction.safeReturnType: IrType?
  get() = try {
    returnType
  } catch (e: UninitializedPropertyAccessException) {
    null
  }

private fun IrLocalDelegatedProperty.renderLocalDelegatedPropertyFlags() =
  if (isVar) "var" else "val"

internal class VariableNameData(val normalizeNames: Boolean) {
  val nameMap: MutableMap<IrVariableSymbol, String> = mutableMapOf()
  var temporaryIndex: Int = 0
}

internal fun IrVariable.normalizedName(data: VariableNameData): String {
  if (data.normalizeNames && (origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE || origin == IrDeclarationOrigin.FOR_LOOP_ITERATOR)) {
    return data.nameMap.getOrPut(symbol) { "tmp_${data.temporaryIndex++}" }
  }
  return name.asString()
}

private fun IrFunction.renderReturnType(renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions): String =
  safeReturnType?.renderTypeWithRenderer(renderer, options) ?: "<Uninitialized>"

private fun IrType.renderTypeWithRenderer(renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions): String =
  "${renderTypeAnnotations(annotations, renderer, options)}${renderTypeInner(renderer, options)}"

private fun IrType.renderTypeInner(renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions) =
  when (this) {
    is IrDynamicType -> "dynamic"

    is IrErrorType -> "IrErrorType(${options.verboseErrorTypes.ifTrue { originalKotlinType }})"

    is IrCapturedType -> "IrCapturedType(${constructor.argument.render()}"

    is IrSimpleType -> buildTrimEnd {
      val isDefinitelyNotNullType =
        classifier is IrTypeParameterSymbol && nullability == SimpleTypeNullability.DEFINITELY_NOT_NULL
      if (isDefinitelyNotNullType) append("{")
      append(classifier.renderClassifierFqn(options))
      if (arguments.isNotEmpty()) {
        append(
          arguments.joinToString(prefix = "<", postfix = ">", separator = ", ") {
            it.renderTypeArgument(renderer, options)
          }
        )
      }
      if (isDefinitelyNotNullType) {
        append(" & Any}")
      } else if (isMarkedNullable()) {
        append('?')
      }
      if (options.printTypeAbbreviations)
        abbreviation?.let {
          append(it.renderTypeAbbreviation(renderer, options))
        }
    }
  }

private fun IrTypeAbbreviation.renderTypeAbbreviation(renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions): String =
  buildString {
    append("{ ")
    append(renderTypeAnnotations(annotations, renderer, options))
    append(typeAlias.renderTypeAliasFqn(options))
    if (arguments.isNotEmpty()) {
      append(
        arguments.joinToString(prefix = "<", postfix = ">", separator = ", ") {
          it.renderTypeArgument(renderer, options)
        }
      )
    }
    if (hasQuestionMark) {
      append('?')
    }
    append(" }")
  }

private fun IrTypeArgument.renderTypeArgument(renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions): String =
  when (this) {
    is IrStarProjection -> "*"

    is IrTypeProjection -> buildTrimEnd {
      append(variance.label)
      if (variance != Variance.INVARIANT) append(' ')
      append(type.renderTypeWithRenderer(renderer, options))
    }
  }

internal fun List<IrConstructorCall>.filterOutSourceRetentions(options: DumpIrTreeOptions): List<IrConstructorCall> =
  applyIf(!options.printSourceRetentionAnnotations) {
    filterNot { it: IrConstructorCall ->
      (it.symbol.owner.returnType.classifierOrNull?.owner as? IrClass)?.annotations?.any { it: IrConstructorCall ->
        it.symbol.owner.returnType.classFqName?.asString() == Retention::class.java.name &&
          (it.arguments.first() as? IrGetEnumValue)?.symbol?.owner?.name?.asString() == AnnotationRetention.SOURCE.name
      } == true
    }
  }

private fun renderTypeAnnotations(annotations: List<IrConstructorCall>, renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions): String =
  annotations.filterOutSourceRetentions(options).let {
    if (it.isEmpty())
      ""
    else
      buildString {
        appendIterableWith(it, prefix = "", postfix = " ", separator = " ") {
          append("@[")
          renderAsAnnotation(it, renderer, options)
          append("]")
        }
      }
  }

private fun StringBuilder.renderAsAnnotation(
  irAnnotation: IrConstructorCall,
  renderer: RenderIrElementVisitor?,
  options: DumpIrTreeOptions,
) {
  val annotationClassName = irAnnotation.symbol.takeIf { it.isBound }?.owner?.parentAsClass?.name?.asString() ?: "<unbound>"
  append(annotationClassName)

  if (irAnnotation.typeArguments.isNotEmpty()) {
    irAnnotation.typeArguments.joinTo(this, ", ", "<", ">") {
      it?.renderTypeWithRenderer(renderer, options) ?: "null"
    }
  }

  if (irAnnotation.valueArgumentsCount == 0) return

  val valueParameterNames = irAnnotation.getValueParameterNamesForDebug(options)

  appendIterableWith(0 until irAnnotation.valueArgumentsCount, separator = ", ", prefix = "(", postfix = ")") {
    append(valueParameterNames[it])
    append(" = ")
    renderAsAnnotationArgument(irAnnotation.getValueArgument(it), renderer, options)
  }
}

private fun StringBuilder.renderAsAnnotationArgument(irElement: IrElement?, renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions) {
  when (irElement) {
    null -> append("<null>")
    is IrConstructorCall -> renderAsAnnotation(irElement, renderer, options)
    is IrConst -> {
      renderIrConstAsAnnotationArgument(irElement)
    }
    is IrVararg -> {
      appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ") {
        renderAsAnnotationArgument(it, renderer, options)
      }
      append(" type=${irElement.type.render()}")
      append(" varargElementType=${irElement.varargElementType.render()}")
    }
    else -> if (renderer != null) {
      append(irElement.accept(renderer, null))
    } else {
      append("...")
    }
  }
}

private fun StringBuilder.renderIrConstAsAnnotationArgument(const: IrConst) {
  val quotes = when (const.kind) {
    IrConstKind.String -> "\""
    IrConstKind.Char -> "'"
    else -> ""
  }
  append(quotes)
  append(const.value.toString())
  append(quotes)
}

private fun renderClassWithRenderer(
  declaration: IrClass,
  renderer: RenderIrElementVisitor?,
  flagsRenderer: FlagsRenderer,
  options: DumpIrTreeOptions,
) = declaration.runTrimEnd {
  "CLASS ${renderOriginIfNonTrivial(options)}" +
    "$kind name:$name " +
    renderSignatureIfEnabled(options.printSignatures) +
    "modality:$modality visibility:$visibility " +
    renderClassFlags(flagsRenderer) +
    "superTypes:[${superTypes.joinToString(separator = "; ") { it.renderTypeWithRenderer(renderer, options) }}]"
}

private fun renderEnumEntry(declaration: IrEnumEntry, options: DumpIrTreeOptions) = declaration.runTrimEnd {
  "ENUM_ENTRY " +
    renderOriginIfNonTrivial(options) +
    "name:$name " +
    renderSignatureIfEnabled(options.printSignatures)
}

private fun renderField(
  declaration: IrField,
  renderer: RenderIrElementVisitor?,
  flagsRenderer: FlagsRenderer,
  options: DumpIrTreeOptions,
) = declaration.runTrimEnd {
  "FIELD ${renderOriginIfNonTrivial(options)}name:$name ${renderSignatureIfEnabled(options.printSignatures)}type:${
    type.renderTypeWithRenderer(
      renderer,
      options
    )
  } visibility:$visibility ${renderFieldFlags(flagsRenderer)}"
}

private fun renderTypeParameter(declaration: IrTypeParameter, renderer: RenderIrElementVisitor?, options: DumpIrTreeOptions) =
  declaration.runTrimEnd {
    "TYPE_PARAMETER ${renderOriginIfNonTrivial(options)}" +
      "name:$name index:$index variance:$variance " +
      renderSignatureIfEnabled(options.printSignatures) +
      "superTypes:[${
        superTypes.joinToString(separator = "; ") {
          it.renderTypeWithRenderer(
            renderer, options
          )
        }
      }] " +
      "reified:$isReified"
  }
