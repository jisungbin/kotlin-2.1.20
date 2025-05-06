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

internal class ComposableTypeTransformer(
  private val context: IrPluginContext,
  private val typeRemapper: ComposableTypeRemapper,
) : IrElementTransformerVoid() {
  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    declaration.returnType = declaration.returnType.remapType()
    declaration.remapOverriddenFunctionTypes()
    return super.visitSimpleFunction(declaration)
  }

  override fun visitFile(declaration: IrFile): IrFile =
    includeFileNameInExceptionTrace(declaration) {
      super.visitFile(declaration)
    }

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val ownerFn = expression.symbol.owner
    // If we are calling an external constructor, we want to "remap" the types of its signature
    // as well, since if it they are @Composable it will have its unmodified signature. These
    // types won't be traversed by default by the DeepCopyIrTreeWithSymbols so we have to
    // do it ourself here.
    //
    // 외부 생성자를 호출하는 경우, @Composable인 경우 수정되지 않은 시그니처를 갖게 되므로
    // 시그니처 타입도 'remap'하고 싶습니다. 이러한 유형은 기본적으로 DeepCopyIrTreeWithSymbols에
    // 의해 순회되지 않으므로 여기서 직접 수행해야 합니다.
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

    // SAM_CONVERSION types from IR stubs are not remapped normally, as the fun interface is
    // technically not a function type. This part goes over types involved in SAM_CONVERSION and
    // ensures that parameter/return types of IR stubs are remapped correctly.
    // Classes extending fun interfaces with composable types will be processed by visitFunction
    // above as normal.
    //
    // fun interface는 기술적으로 함수 타입이 아니기 때문에 IR 스텁의 SAM_CONVERSION 타입은
    // 일반적으로 리매핑되지 않습니다. 이 파트에서는 SAM_CONVERSION에 관련된 타입을 살펴보고 IR
    // 스텁의 매개변수/리턴 타입이 올바르게 리매핑되는지 확인합니다. 컴포저블 타입으로 fun interface를
    // 확장하는 클래스는 정상적으로 위의 visitFunction에 의해 처리됩니다.
    val type = expression.typeOperand
    val clsSymbol = type.classOrNull ?: return super.visitTypeOperator(expression)

    // Unbound symbols indicate they are in the current module and have not been
    // processed by copier yet.
    //
    // 바인딩되지 않은 기호는 현재 모듈에 있으며 아직 복사기(deep copy)에 의해
    // 처리되지 않았음을 나타냅니다.
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

  override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
    val owner = expression.symbol.owner
    // If we are calling an external constructor, we want to "remap" the types of its signature
    // as well, since if it they are @Composable it will have its unmodified signature. These
    // types won't be traversed by default by the DeepCopyIrTreeWithSymbols so we have to
    // do it ourself here.
    //
    // 외부 생성자를 호출하는 경우, @Composable인 경우 수정되지 않은 시그니처를 갖게 되므로
    // 시그니처 타입도 'remap'하고 싶습니다. 이러한 유형은 기본적으로 DeepCopyIrTreeWithSymbols에
    // 의해 순회되지 않으므로 여기서 직접 수행해야 합니다.
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
    //
    // 컴포저블 함수에 대한 모든 virtual call은 (right) 함수의 베이스 클래스(n+1 arity)로 호출을
    // 업데이트해야 합니다. 함수 인스턴스에서 가장 자주 virtual call하는 것은 `invoke`이며, 이는
    // ComposeParamTransformer에서 이미 수행합니다. 이 외에도 `equals`나 `hashCode`와 같은 다른
    // 호출도 가능합니다. 이 경우 이러한 호출도 업데이트하려고 합니다.
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
      val realParamsCount = containingClass.typeParameters.size - 1
      val newArgsSize =
        realParamsCount +
          1 + // %composer
          changedParamCount(realValueParamsCount = realParamsCount, thisParamsCount = 0) // %changed

      val newFnClass = context.function(newArgsSize).owner
      val newFn = newFnClass.functions.first { it.name == ownerFn.name }

      return super.visitCall(
        IrCallImpl(
          startOffset = expression.startOffset,
          endOffset = expression.endOffset,
          type = expression.type,
          symbol = newFn.symbol,
          typeArgumentsCount = expression.typeArguments.size,
          origin = expression.origin,
          superQualifierSymbol = expression.superQualifierSymbol,
        ).apply {
          copyTypeAndValueArgumentsFrom(expression)
        },
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
    //
    // 외부 함수를 호출하는 경우 @Composable인 경우 수정되지 않은 시그니처을 갖게 되므로 시그니처
    // 타입도 'remap'하고 싶습니다. 이러한 함수는 기본적으로 DeepCopyIrTreeWithSymbols에 의해 순회되지
    // 않으므로 여기서 직접 수행해야 합니다.
    //
    // 프로퍼티 getter/setter에 대한 외부 선언이 변환되면 해당 프로퍼티도 변환하여
    // `getterFun.correspondingPropertySymbol.owner.getter == getterFun` 관계가 유지되도록 해야 합니다.
    // 이 관계를 유지하지 않으면 인라인 클래스 getter가 잘못 컴파일됩니다.
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
    declaration.valueClassRepresentation?.let { valueClass ->
      declaration.valueClassRepresentation = valueClass.mapUnderlyingType { it.remapType() as IrSimpleType }
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
        //
        // 이것은 다른 컴파일 단위에 있는 외부 함수이므로 Composable 타입을 업데이트해야
        // 할 가능성이 있습니다. 함수가 현재 모듈에 있는 경우 이 deepCopy 단계에서 결국
        // 업데이트해야 합니다.
        if (overriddenFn.needsComposableRemapping()) {
          overriddenFn.transform(this@ComposableTypeTransformer, null)
        }
      }
      // traverse recursively to ensure that base function is transformed correctly
      overriddenFn.remapOverriddenFunctionTypes()
    }
  }

  private fun IrType.remapType(): IrType = typeRemapper.remapType(this)
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
    val changedParams = changedParamCount(realValueParamsCount = realParams, thisParamsCount = 1)

    extraArgs = extraArgs + List(changedParams) {
      makeTypeProjection(type = context.irBuiltIns.intType, variance = Variance.INVARIANT)
    }

    val newIrArguments =
      oldIrArguments.subList(0, oldIrArguments.size - 1) +
        extraArgs +
        oldIrArguments.last() // return type

    val newArgSize = oldIrArguments.size + extraArgs.size - 1
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
