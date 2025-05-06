/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.hasComposableAnnotation
import androidx.compose.compiler.plugins.kotlin.isComposableAnnotation
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeAbbreviation
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrTypeAbbreviationImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.copyTypeAndValueArgumentsFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.memoryOptimizedMap

internal fun IrFunction.needsComposableRemapping(): Boolean {
  if (
    dispatchReceiverParameter?.type.containsComposableAnnotation() ||
    extensionReceiverParameter?.type.containsComposableAnnotation() ||
    returnType.containsComposableAnnotation()
  ) return true

  for (param in valueParameters) {
    if (param.type.containsComposableAnnotation()) return true
  }
  return false
}

internal fun IrType?.containsComposableAnnotation(): Boolean {
  if (this == null) return false
  if (hasComposableAnnotation()) return true

  return when (this) {
    is IrSimpleType -> arguments.any { it.typeOrNull.containsComposableAnnotation() }
    else -> false
  }
}

internal class ComposableTypeTransformer(
  private val context: IrPluginContext,
  private val typeRemapper: ComposableTypeRemapper,
) : IrElementTransformerVoid() {
  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    declaration.returnType = declaration.returnType.remapType()
    declaration.remapOverriddenFunctionTypes()
    return super.visitSimpleFunction(declaration)
  }

  private fun IrSimpleFunction.remapOverriddenFunctionTypes() {
    overriddenSymbols.forEach { symbol ->
      if (!symbol.isBound) {
        // symbol will be remapped by deep copy on later iteration
        return@forEach
      }
      val overriddenFn = symbol.owner
      if (overriddenFn.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) {
        // this is external function that is in a different compilation unit,
        // so we potentially need to update composable types for it.
        // if the function is in the current module, it should be updated eventually
        // by this deep copy pass.
        if (overriddenFn.needsComposableRemapping()) {
          overriddenFn.transform(this@ComposableTypeTransformer, null)
        }
      }
      // traverse recursively to ensure that base function is transformed correctly
      overriddenFn.remapOverriddenFunctionTypes()
    }
  }

  override fun visitFile(declaration: IrFile): IrFile {
    includeFileNameInExceptionTrace(declaration) {
      return super.visitFile(declaration)
    }
  }

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val ownerFn = expression.symbol.owner
    // If we are calling an external constructor, we want to "remap" the types of its signature
    // as well, since if it they are @Composable it will have its unmodified signature. These
    // types won't be traversed by default by the DeepCopyIrTreeWithSymbols so we have to
    // do it ourself here.
    if (
      ownerFn.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
      ownerFn.needsComposableRemapping()
    ) {
      ownerFn.transform(this, null)
    }
    return super.visitConstructorCall(expression)
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    expression.typeOperand = typeRemapper.remapType(expression.typeOperand)

    if (expression.operator != IrTypeOperator.SAM_CONVERSION) {
      return super.visitTypeOperator(expression)
    }

    /*
     * SAM_CONVERSION types from IR stubs are not remapped normally, as the fun interface is
     * technically not a function type. This part goes over types involved in SAM_CONVERSION and
     * ensures that parameter/return types of IR stubs are remapped correctly.
     * Classes extending fun interfaces with composable types will be processed by visitFunction
     * above as normal.
     */
    val type = expression.typeOperand
    val clsSymbol = type.classOrNull ?: return super.visitTypeOperator(expression)

    // Unbound symbols indicate they are in the current module and have not been
    // processed by copier yet.
    if (
      clsSymbol.isBound &&
      clsSymbol.owner.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
      // Only process fun interfaces with @Composable types
      clsSymbol.owner.isFun &&
      clsSymbol.functions.any { it.owner.needsComposableRemapping() }
    ) {
      clsSymbol.owner.transform(this, null)
    }

    return super.visitTypeOperator(expression)
  }

  override fun visitDelegatingConstructorCall(
    expression: IrDelegatingConstructorCall,
  ): IrExpression {
    val owner = expression.symbol.owner
    // If we are calling an external constructor, we want to "remap" the types of its signature
    // as well, since if they are @Composable it will have its unmodified signature. These
    // types won't be traversed by default by the DeepCopyIrTreeWithSymbols so we have to
    // do it ourselves here.
    if (
      owner.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
      owner.needsComposableRemapping()
    ) {
      owner.transform(this, null)
    }
    return super.visitDelegatingConstructorCall(expression)
  }

  override fun visitCall(expression: IrCall): IrExpression {
    val ownerFn = expression.symbol.owner
    val containingClass = ownerFn.parentClassOrNull

    // Any virtual calls on composable functions we want to make sure we update the call to
    // the right function base class (of n+1 arity). The most often virtual call to make on
    // a function instance is `invoke`, which we *already* do in the ComposeParamTransformer.
    // There are others that can happen though as well, such as `equals` and `hashCode`. In this
    // case, we want to update those calls as well.
    if (
      containingClass != null &&
      ownerFn.origin == IrDeclarationOrigin.FAKE_OVERRIDE && (
        // Fake override refers to composable if container is synthetic composable (K2)
        // or function type is composable (K1)
        containingClass.defaultType.isSyntheticComposableFunction() || (
          containingClass.defaultType.isFunction() &&
            expression.dispatchReceiver?.type?.hasComposableAnnotation() == true
          )
        )
    ) {
      val realParams = containingClass.typeParameters.size - 1
      // with composer and changed
      val newArgsSize = realParams + 1 + changedParamCount(realParams, 0)
      val newFnClass = context.function(newArgsSize).owner

      val newFn = newFnClass
        .functions
        .first { it.name == ownerFn.name }

      return super.visitCall(
        IrCallImpl(
          expression.startOffset,
          expression.endOffset,
          expression.type,
          newFn.symbol,
          expression.typeArguments.size,
          expression.origin,
          expression.superQualifierSymbol,
        ).apply {
          copyTypeAndValueArgumentsFrom(expression)
        }
      )
    }

    // If we are calling an external function, we want to "remap" the types of its signature
    // as well, since if it is @Composable it will have its unmodified signature. These
    // functions won't be traversed by default by the DeepCopyIrTreeWithSymbols so we have to
    // do it ourself here.
    //
    // When an external declaration for a property getter/setter is transformed, we need to
    // also transform the corresponding property so that we maintain the relationship
    // `getterFun.correspondingPropertySymbol.owner.getter == getterFun`. If we do not
    // maintain this relationship inline class getters will be incorrectly compiled.
    if (
      ownerFn.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
      ownerFn.correspondingPropertySymbol != null
    ) {
      val property = ownerFn.correspondingPropertySymbol!!.owner
      property.transform(this, null)
    }

    if (ownerFn.needsComposableRemapping()) {
      ownerFn.transform(this, null)
    }

    return super.visitCall(expression)
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    declaration.superTypes = declaration.superTypes.memoryOptimizedMap { it.remapType() }
    declaration.valueClassRepresentation?.run {
      declaration.valueClassRepresentation = mapUnderlyingType { it.remapType() as IrSimpleType }
    }
    return super.visitClass(declaration)
  }

  override fun visitValueParameter(declaration: IrValueParameter): IrStatement {
    declaration.type = declaration.type.remapType()
    declaration.varargElementType = declaration.varargElementType?.remapType()
    return super.visitValueParameter(declaration)
  }

  override fun visitTypeParameter(declaration: IrTypeParameter): IrStatement {
    declaration.superTypes = declaration.superTypes.memoryOptimizedMap { it.remapType() }
    return super.visitTypeParameter(declaration)
  }

  override fun visitVariable(declaration: IrVariable): IrStatement {
    declaration.type = declaration.type.remapType()
    return super.visitVariable(declaration)
  }

  override fun visitFunction(declaration: IrFunction): IrStatement {
    declaration.returnType = declaration.returnType.remapType()
    return super.visitFunction(declaration)
  }

  override fun visitField(declaration: IrField): IrStatement {
    declaration.type = declaration.type.remapType()
    return super.visitField(declaration)
  }

  override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
    declaration.type = declaration.type.remapType()
    return super.visitLocalDelegatedProperty(declaration)
  }

  override fun visitTypeAlias(declaration: IrTypeAlias): IrStatement {
    declaration.expandedType = declaration.expandedType.remapType()
    return super.visitTypeAlias(declaration)
  }

  override fun visitExpression(expression: IrExpression): IrExpression {
    expression.type = expression.type.remapType()
    return super.visitExpression(expression)
  }

  override fun visitMemberAccess(expression: IrMemberAccessExpression<*>): IrExpression {
    expression.typeArguments.replaceAll { it?.remapType() }
    return super.visitMemberAccess(expression)
  }

  override fun visitVararg(expression: IrVararg): IrExpression {
    expression.varargElementType = expression.varargElementType.remapType()
    return super.visitVararg(expression)
  }

  override fun visitClassReference(expression: IrClassReference): IrExpression {
    expression.classType = expression.classType.remapType()
    return super.visitClassReference(expression)
  }

  private fun IrType.remapType() = typeRemapper.remapType(this)
}

class ComposableTypeRemapper(
  private val context: IrPluginContext,
  private val composerType: IrType,
) : TypeRemapper {
  private val kotlinFunctionsBuiltinsPackageFqName: FqName =
    StandardNames.BUILT_INS_PACKAGE_FQ_NAME
      .child(Name.identifier("jvm"))
      .child(Name.identifier("functions"))

  override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}
  override fun leaveScope() {}

  override fun remapType(type: IrType): IrType {
    if (type !is IrSimpleType) return type
    if (!type.isComposableFunction()) {
      if (type.hasComposableTypeArgument()) {
        return underlyingRemapType(type)
      }
      return type
    }

    val oldIrArguments = type.arguments
    val realParams = oldIrArguments.size - 1

    var extraArgs: List<IrTypeProjection> = listOf(
      // composer param
      makeTypeProjection(type = composerType, variance = Variance.INVARIANT),
    )
    val changedParams = changedParamCount(realValueParams = realParams, thisParams = 1)

    extraArgs = extraArgs + List(changedParams) {
      makeTypeProjection(type = context.irBuiltIns.intType, variance = Variance.INVARIANT)
    }

    val newIrArguments =
      oldIrArguments.subList(0, oldIrArguments.size - 1) +
        extraArgs +
        oldIrArguments.last() // return type

    val newArgSize = oldIrArguments.size - 1 + extraArgs.size
    val functionCls = context.function(newArgSize)

    return IrSimpleTypeImpl(
      classifier = functionCls,
      nullability = type.nullability,
      arguments = newIrArguments.map { remapTypeArgument(it) },
      annotations = type.annotations.filter { !it.isComposableAnnotation() },
      abbreviation = null,
    )
  }

  // underlying: (겉으로 잘 드러나지는 않지만) 근본적인[근원적인]
  private fun underlyingRemapType(type: IrSimpleType): IrType =
    IrSimpleTypeImpl(
      classifier = type.classifier,
      nullability = type.nullability,
      arguments = type.arguments.map { remapTypeArgument(it) },
      annotations = type.annotations,
      abbreviation = type.abbreviation?.remapTypeAbbreviation(),
    )

  private fun IrTypeAbbreviation.remapTypeAbbreviation(): IrTypeAbbreviation =
    IrTypeAbbreviationImpl(
      typeAlias = typeAlias,
      hasQuestionMark = hasQuestionMark,
      arguments = arguments.map { remapTypeArgument(it) },
      annotations = annotations,
    )

  private fun remapTypeArgument(typeArgument: IrTypeArgument): IrTypeArgument =
    if (typeArgument is IrTypeProjection)
      makeTypeProjection(type = remapType(typeArgument.type), variance = typeArgument.variance)
    else
      typeArgument

  private fun IrType.isFunction(): Boolean {
    val cls = classOrNull ?: return false

    val name = cls.owner.name.asString()
    if (!name.startsWith("Function")) return false

    val packageFqName = cls.owner.packageFqName
    return packageFqName == StandardNames.BUILT_INS_PACKAGE_FQ_NAME ||
      packageFqName == kotlinFunctionsBuiltinsPackageFqName
  }

  private fun IrType.isComposableFunction(): Boolean =
    isSyntheticComposableFunction() ||
      (isFunction() && hasComposableAnnotation())

  private fun IrType.hasComposableType(): Boolean =
    isComposableFunction() || hasComposableTypeArgument()

  private fun IrType.hasComposableTypeArgument(): Boolean {
    if (this is IrSimpleType) {
      val argument = arguments.any { it.typeOrNull?.hasComposableType() == true }

      // abbreviate: (단어·구 등을) 줄여 쓰다[축약하다] (IrTypeAbbreviation은 typealias에 해당함)
      val abbreviationArgument = abbreviation?.arguments?.any { it.typeOrNull?.hasComposableType() == true } == true

      return argument || abbreviationArgument
    }
    return false
  }
}
