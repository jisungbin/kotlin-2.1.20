/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.descriptors

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptorWithTypeParameters
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorVisitor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.FieldDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.PropertyGetterDescriptor
import org.jetbrains.kotlin.descriptors.PropertySetterDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.ScriptDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.SupertypeLoopChecker
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueClassRepresentation
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableAccessorDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptorWithAccessors
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptorImpl
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.TypeAliasConstructorDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrErrorDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.isSingleFieldValueClass
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.types.typeConstructorParameters
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.fields
import org.jetbrains.kotlin.ir.util.irError
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isFileClass
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.module
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.constants.AnnotationValue
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.BooleanValue
import org.jetbrains.kotlin.resolve.constants.ByteValue
import org.jetbrains.kotlin.resolve.constants.CharValue
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.DoubleValue
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.FloatValue
import org.jetbrains.kotlin.resolve.constants.IntValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.constants.LongValue
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.resolve.constants.ShortValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.LazyScopeAdapter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.TypeIntersectionScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.AbstractClassTypeConstructor
import org.jetbrains.kotlin.types.AbstractTypeConstructor
import org.jetbrains.kotlin.types.ClassTypeConstructorImpl
import org.jetbrains.kotlin.types.DefinitelyNotNullType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.KotlinTypeFactory
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.StarProjectionImpl
import org.jetbrains.kotlin.types.TypeAttributes
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.TypeSubstitution
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.createDynamicType
import org.jetbrains.kotlin.types.error.ErrorUtils
import org.jetbrains.kotlin.types.replace
import org.jetbrains.kotlin.utils.filterIsInstanceAnd
import org.jetbrains.kotlin.utils.findIsInstanceAnd
import org.jetbrains.kotlin.utils.memoryOptimizedMap
import org.jetbrains.kotlin.utils.memoryOptimizedMapIndexed

/* Descriptors that serve purely as a view into IR structures.
   Created each time at the borderline between IR-based and descriptor-based code (such as inliner).
   Compared to WrappedDescriptors, no method calls ever return true descriptors, except when
   unbound symbols are encountered (see `Ir...Symbol.toIrBasedDescriptorIfPossible()`).
 */

abstract class IrBasedDeclarationDescriptor<T : IrDeclaration>(val owner: T) : DeclarationDescriptor {
  override val annotations: Annotations by lazy(owner::toAnnotations)

  protected fun unsupportedInIrBasedDescriptor(): Nothing {
    irError("This operation is not supported in IR-based descriptors") {
      withIrEntry("element", owner)
    }
  }

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  protected fun IrType.toIrBasedKotlinType(): KotlinType = toIrBasedKotlinType(owner.module.builtIns)

  override fun getContainingDeclaration(): DeclarationDescriptor =
    getContainingDeclaration(owner)

  override fun equals(other: Any?): Boolean =
    other is IrBasedDeclarationDescriptor<*> && owner == other.owner

  override fun hashCode(): Int = owner.hashCode()

  override fun toString(): String = javaClass.simpleName + ": " + owner.render()
}

fun IrDeclaration.toIrBasedDescriptor(): DeclarationDescriptor = when (this) {
  is IrValueParameter -> toIrBasedDescriptor()
  is IrTypeParameter -> toIrBasedDescriptor()
  is IrVariable -> toIrBasedDescriptor()
  is IrLocalDelegatedProperty -> toIrBasedDescriptor()
  is IrFunction -> toIrBasedDescriptor()
  is IrClass -> toIrBasedDescriptor()
  is IrAnonymousInitializer -> parentAsClass.toIrBasedDescriptor()
  is IrEnumEntry -> toIrBasedDescriptor()
  is IrProperty -> toIrBasedDescriptor()
  is IrField -> toIrBasedDescriptor()
  is IrTypeAlias -> toIrBasedDescriptor()
  is IrErrorDeclaration -> toIrBasedDescriptor()
  is IrScript -> toIrBasedDescriptor()
  else -> error("Unknown declaration kind")
}

abstract class IrBasedCallableDescriptor<T : IrDeclaration>(owner: T) : CallableDescriptor, IrBasedDeclarationDescriptor<T>(owner) {

  override fun getOriginal() = this

  override fun substitute(substitutor: TypeSubstitutor): CallableDescriptor =
    throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")

  override fun getOverriddenDescriptors(): Collection<CallableDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun getSource() = SourceElement.NO_SOURCE

  override fun getExtensionReceiverParameter(): ReceiverParameterDescriptor? = null

  override fun getDispatchReceiverParameter(): ReceiverParameterDescriptor? = null

  override fun getContextReceiverParameters(): List<ReceiverParameterDescriptor> = emptyList()

  override fun getTypeParameters(): List<TypeParameterDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun getReturnType(): KotlinType? {
    unsupportedInIrBasedDescriptor()
  }

  override fun getValueParameters(): List<ValueParameterDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun hasStableParameterNames(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun hasSynthesizedParameterNames() = false

  override fun getVisibility(): DescriptorVisibility {
    unsupportedInIrBasedDescriptor()
  }

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R {
    unsupportedInIrBasedDescriptor()
  }

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    unsupportedInIrBasedDescriptor()
  }

  override fun <V : Any?> getUserData(key: CallableDescriptor.UserDataKey<V>?): V? = null
}

// Do not create this kind of descriptor for dispatch receiver parameters
// IrBasedReceiverParameterDescriptor should be used instead
open class IrBasedValueParameterDescriptor(owner: IrValueParameter) : ValueParameterDescriptor,
  IrBasedCallableDescriptor<IrValueParameter>(owner) {

  override val index: Int
    get() {
      if (owner.indexInParameters == -1)
        return -1
      val function = owner._parent as? IrFunction
        ?: return -1

      // Find index in imaginary list that contains only Regular and Context parameters.
      return function.parameters
        .subList(0, owner.indexInParameters)
        .count { it.kind == IrParameterKind.Context || it.kind == IrParameterKind.Regular }
    }
  override val isCrossinline get() = owner.isCrossinline
  override val isNoinline get() = owner.isNoinline
  override val varargElementType get() = owner.varargElementType?.toIrBasedKotlinType()
  override fun isConst() = false
  override fun isVar() = false

  override fun getContainingDeclaration() = (owner.parent as IrFunction).toIrBasedDescriptor()
  override fun getType() = owner.type.toIrBasedKotlinType()
  override fun getName() = owner.name
  override fun declaresDefaultValue() = owner.defaultValue != null
  override fun getCompileTimeInitializer(): ConstantValue<*>? = null
  override fun cleanCompileTimeInitializerCache() {}

  override fun copy(newOwner: CallableDescriptor, newName: Name, newIndex: Int) = unsupportedInIrBasedDescriptor()

  override fun getOverriddenDescriptors(): Collection<ValueParameterDescriptor> = emptyList()
  override fun getTypeParameters(): List<TypeParameterDescriptor> = emptyList()
  override fun getValueParameters(): List<ValueParameterDescriptor> = emptyList()

  override fun getOriginal() = this

  override fun substitute(substitutor: TypeSubstitutor): ValueParameterDescriptor =
    throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")

  override fun getReturnType(): KotlinType? = owner.type.toIrBasedKotlinType()

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D) =
    visitor!!.visitValueParameterDescriptor(this, data)!!

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitValueParameterDescriptor(this, null)
  }
}

open class IrBasedReceiverParameterDescriptor(owner: IrValueParameter) : ReceiverParameterDescriptor,
  IrBasedCallableDescriptor<IrValueParameter>(owner) {

  override fun getValue(): ReceiverValue {
    unsupportedInIrBasedDescriptor()
  }

  override fun getType() = owner.type.toIrBasedKotlinType()
  override fun getName() = owner.name

  override fun copy(newOwner: DeclarationDescriptor) = unsupportedInIrBasedDescriptor()

  override fun getOverriddenDescriptors(): Collection<ValueParameterDescriptor> = emptyList()

  override fun getOriginal() = this

  override fun substitute(substitutor: TypeSubstitutor): ReceiverParameterDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun getReturnType(): KotlinType? = owner.type.toIrBasedKotlinType()

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D) =
    visitor!!.visitReceiverParameterDescriptor(this, data)!!

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitReceiverParameterDescriptor(this, null)
  }
}

fun IrValueParameter.toIrBasedDescriptor() = when (kind) {
  IrParameterKind.DispatchReceiver, IrParameterKind.ExtensionReceiver -> IrBasedReceiverParameterDescriptor(this)
  IrParameterKind.Context, IrParameterKind.Regular -> IrBasedValueParameterDescriptor(this)
}

open class IrBasedTypeParameterDescriptor(owner: IrTypeParameter) : TypeParameterDescriptor,
  IrBasedDeclarationDescriptor<IrTypeParameter>(owner) {
  override fun getName() = owner.name

  override fun isReified() = owner.isReified

  override fun getVariance() = owner.variance

  override fun getUpperBounds() = owner.superTypes.memoryOptimizedMap { it.toIrBasedKotlinType() }

  private val _typeConstructor: TypeConstructor by lazy {
    object : AbstractTypeConstructor(LockBasedStorageManager.NO_LOCKS) {
      override fun computeSupertypes() = upperBounds

      override val supertypeLoopChecker = SupertypeLoopChecker.EMPTY

      override fun getParameters(): List<TypeParameterDescriptor> = emptyList()

      override fun isFinal() = false

      override fun isDenotable() = true

      override fun getDeclarationDescriptor() = this@IrBasedTypeParameterDescriptor

      override fun getBuiltIns() = module.builtIns

      override fun isSameClassifier(classifier: ClassifierDescriptor): Boolean = declarationDescriptor === classifier
    }
  }

  override fun getTypeConstructor() = _typeConstructor

  override fun getOriginal() = this

  override fun getSource() = SourceElement.NO_SOURCE

  override fun getIndex() = owner.index

  override fun isCapturedFromOuterDeclaration() = false

  private val _defaultType: SimpleType by lazy {
    KotlinTypeFactory.simpleTypeWithNonTrivialMemberScope(
      TypeAttributes.Empty, typeConstructor, emptyList(), false,
      LazyScopeAdapter {
        TypeIntersectionScope.create(
          "Scope for type parameter " + name.asString(),
          upperBounds
        )
      }
    )
  }

  override fun getDefaultType() = _defaultType

  override fun getStorageManager() = LockBasedStorageManager.NO_LOCKS

  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitTypeParameterDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitTypeParameterDescriptor(this, null)
  }

  override fun toString(): String = super.toString() + "\nParent: $containingDeclaration"
}

fun IrTypeParameter.toIrBasedDescriptor() = IrBasedTypeParameterDescriptor(this)

open class IrBasedVariableDescriptor(owner: IrVariable) : VariableDescriptor, IrBasedCallableDescriptor<IrVariable>(owner) {

  override fun getContainingDeclaration() = (owner.parent as IrDeclaration).toIrBasedDescriptor()
  override fun getType() = owner.type.toIrBasedKotlinType()
  override fun getReturnType() = getType()
  override fun getName() = owner.name
  override fun isConst() = owner.isConst
  override fun isVar() = owner.isVar
  override fun isLateInit() = owner.isLateinit

  override fun getCompileTimeInitializer(): ConstantValue<*>? {
    unsupportedInIrBasedDescriptor()
  }

  override fun cleanCompileTimeInitializerCache() {}

  override fun getOverriddenDescriptors(): Collection<VariableDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun getOriginal() = this

  override fun substitute(substitutor: TypeSubstitutor): VariableDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitVariableDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitVariableDescriptor(this, null)
  }
}

fun IrVariable.toIrBasedDescriptor() = IrBasedVariableDescriptor(this)

open class IrBasedVariableDescriptorWithAccessor(owner: IrLocalDelegatedProperty) : VariableDescriptorWithAccessors,
  IrBasedCallableDescriptor<IrLocalDelegatedProperty>(owner) {
  override fun getName(): Name = owner.name

  override fun substitute(substitutor: TypeSubstitutor): VariableDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun isVar() = owner.isVar

  override fun getCompileTimeInitializer(): ConstantValue<*>? {
    unsupportedInIrBasedDescriptor()
  }

  override fun cleanCompileTimeInitializerCache() {}

  override fun getType(): KotlinType = owner.type.toIrBasedKotlinType()

  override fun isConst(): Boolean = false

  override fun getContainingDeclaration() = (owner.parent as IrDeclaration).toIrBasedDescriptor()

  override fun isLateInit(): Boolean = false

  override val getter: VariableAccessorDescriptor?
    get() = unsupportedInIrBasedDescriptor()
  override val setter: VariableAccessorDescriptor?
    get() = unsupportedInIrBasedDescriptor()
  override val isDelegated: Boolean = true
}

fun IrLocalDelegatedProperty.toIrBasedDescriptor() = IrBasedVariableDescriptorWithAccessor(this)

abstract class IrBasedFunctionDescriptor<Function : IrFunction>(owner: Function) : IrBasedCallableDescriptor<Function>(owner) {

  override fun getExtensionReceiverParameter() = owner.parameters
    .firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
    ?.toIrBasedDescriptor() as? ReceiverParameterDescriptor

  override fun getContextReceiverParameters() = owner.parameters
    .filter { it.kind == IrParameterKind.Context }
    .map(::IrBasedReceiverParameterDescriptor)
    .toList()

  override fun getValueParameters() = owner.parameters
    .filter { it.kind == IrParameterKind.Regular }
    .map(::IrBasedValueParameterDescriptor)
    .toList()
}

// We make all IR-based function descriptors instances of DescriptorWithContainerSource, and use .parentClassId to
// check whether declaration is deserialized. See IrInlineCodegen.descriptorIsDeserialized
open class IrBasedSimpleFunctionDescriptor(owner: IrSimpleFunction) : SimpleFunctionDescriptor, DescriptorWithContainerSource,
  IrBasedFunctionDescriptor<IrSimpleFunction>(owner) {

  override fun getOverriddenDescriptors(): List<FunctionDescriptor> = owner.overriddenSymbols.memoryOptimizedMap { it.owner.toIrBasedDescriptor() }

  override fun getModality() = owner.modality
  override fun getName() = owner.name
  override fun getVisibility() = owner.visibility
  override fun getReturnType() = owner.returnType.toIrBasedKotlinType()

  override fun getDispatchReceiverParameter() = owner.dispatchReceiverParameter?.run {
    (containingDeclaration as ClassDescriptor).thisAsReceiverParameter
  }

  override fun getTypeParameters() = owner.typeParameters.memoryOptimizedMap { it.toIrBasedDescriptor() }

  override fun isExternal() = owner.isExternal
  override fun isSuspend() = owner.isSuspend
  override fun isTailrec() = owner.isTailrec
  override fun isInline() = owner.isInline

  override fun isExpect() = owner.isExpect
  override fun isActual() = false
  override fun isInfix() = false
  override fun isOperator() = false

  override val containerSource: DeserializedContainerSource?
    get() = owner.containerSource

  override fun getOriginal() = this
  override fun substitute(substitutor: TypeSubstitutor): SimpleFunctionDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun setOverriddenDescriptors(overriddenDescriptors: MutableCollection<out CallableMemberDescriptor>) {
    unsupportedInIrBasedDescriptor()
  }

  override fun getKind() =
    if (owner.origin == IrDeclarationOrigin.FAKE_OVERRIDE) CallableMemberDescriptor.Kind.FAKE_OVERRIDE
    else CallableMemberDescriptor.Kind.SYNTHESIZED

  override fun copy(
    newOwner: DeclarationDescriptor?,
    modality: Modality?,
    visibility: DescriptorVisibility?,
    kind: CallableMemberDescriptor.Kind?,
    copyOverrides: Boolean,
  ): Nothing {
    unsupportedInIrBasedDescriptor()
  }

  override fun isHiddenToOvercomeSignatureClash(): Boolean = false
  override fun isHiddenForResolutionEverywhereBesideSupercalls(): Boolean = false

  override fun getInitialSignatureDescriptor(): FunctionDescriptor? = null

  override fun <V : Any?> getUserData(key: CallableDescriptor.UserDataKey<V>?): V? = null

  override fun newCopyBuilder(): FunctionDescriptor.CopyBuilder<out SimpleFunctionDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D) =
    visitor!!.visitFunctionDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitFunctionDescriptor(this, null)
  }
}

fun IrSimpleFunction.toIrBasedDescriptor() =
  when {
    isGetter -> IrBasedPropertyGetterDescriptor(this)
    isSetter -> IrBasedPropertySetterDescriptor(this)
    else -> IrBasedSimpleFunctionDescriptor(this)
  }

open class IrBasedClassConstructorDescriptor(owner: IrConstructor) : ClassConstructorDescriptor,
  IrBasedFunctionDescriptor<IrConstructor>(owner) {
  override fun getContainingDeclaration() = (owner.parent as IrClass).toIrBasedDescriptor()

  override fun getDispatchReceiverParameter() = owner.dispatchReceiverParameter?.run {
    (containingDeclaration.containingDeclaration as ClassDescriptor).thisAsReceiverParameter
  }

  override fun getTypeParameters() =
    (owner.constructedClass.typeParameters + owner.typeParameters).memoryOptimizedMap { it.toIrBasedDescriptor() }

  override fun getOriginal() = this

  override fun substitute(substitutor: TypeSubstitutor): ClassConstructorDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun copy(
    newOwner: DeclarationDescriptor,
    modality: Modality,
    visibility: DescriptorVisibility,
    kind: CallableMemberDescriptor.Kind,
    copyOverrides: Boolean,
  ): ClassConstructorDescriptor {
    throw UnsupportedOperationException()
  }

  override fun getModality() = Modality.FINAL


  override fun setOverriddenDescriptors(overriddenDescriptors: MutableCollection<out CallableMemberDescriptor>) {
    unsupportedInIrBasedDescriptor()
  }

  override fun getKind() = CallableMemberDescriptor.Kind.SYNTHESIZED

  override fun getConstructedClass() = (owner.parent as IrClass).toIrBasedDescriptor()

  override fun getName() = owner.name

  override fun getOverriddenDescriptors(): MutableCollection<out FunctionDescriptor> = mutableListOf()

  override fun getInitialSignatureDescriptor(): FunctionDescriptor? = null

  override fun getVisibility() = owner.visibility

  override fun isHiddenToOvercomeSignatureClash(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun isOperator() = false

  override fun isInline() = owner.isInline

  override fun isHiddenForResolutionEverywhereBesideSupercalls(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun getReturnType() = owner.returnType.toIrBasedKotlinType()

  override fun isPrimary() = owner.isPrimary

  override fun isExpect() = owner.isExpect

  override fun isTailrec() = false

  override fun isActual() = false

  override fun isInfix() = false

  override fun isSuspend() = false

  override fun <V : Any?> getUserData(key: CallableDescriptor.UserDataKey<V>?): V? = null

  override fun isExternal() = owner.isExternal

  override fun newCopyBuilder(): FunctionDescriptor.CopyBuilder<out FunctionDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitConstructorDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitConstructorDescriptor(this, null)
  }
}

fun IrConstructor.toIrBasedDescriptor() = IrBasedClassConstructorDescriptor(this)

fun IrFunction.toIrBasedDescriptor(): FunctionDescriptor =
  when (this) {
    is IrSimpleFunction -> toIrBasedDescriptor()
    is IrConstructor -> toIrBasedDescriptor()
  }

open class IrBasedClassDescriptor(owner: IrClass) : ClassDescriptor, IrBasedDeclarationDescriptor<IrClass>(owner) {
  override fun getName() = owner.name

  override fun getMemberScope(typeArguments: MutableList<out TypeProjection>) = MemberScope.Empty

  override fun getMemberScope(typeSubstitution: TypeSubstitution) = MemberScope.Empty

  override fun getUnsubstitutedMemberScope() = MemberScope.Empty

  override fun getUnsubstitutedInnerClassesScope() = MemberScope.Empty

  override fun getStaticScope() = MemberScope.Empty

  override fun getSource() = owner.source

  override fun getConstructors() =
    owner.declarations.filterIsInstanceAnd<IrConstructor> { !it.origin.isSynthetic }.memoryOptimizedMap { it.toIrBasedDescriptor() }

  private val _defaultType: SimpleType by lazy {
    TypeUtils.makeUnsubstitutedType(this, unsubstitutedMemberScope, KotlinTypeFactory.EMPTY_REFINED_TYPE_FACTORY)
  }

  override fun getDefaultType(): SimpleType = _defaultType

  override fun getKind() = owner.kind

  override fun getModality() = owner.modality

  override fun getCompanionObjectDescriptor() =
    owner.declarations.findIsInstanceAnd<IrClass> { it.isCompanion }?.toIrBasedDescriptor()

  override fun getVisibility() = owner.visibility

  override fun isCompanionObject() = owner.isCompanion

  override fun isData() = owner.isData

  override fun isInline() = owner.isSingleFieldValueClass

  override fun isFun() = owner.isFun

  override fun isValue() = owner.isValue

  override fun getThisAsReceiverParameter() = owner.thisReceiver?.toIrBasedDescriptor() as ReceiverParameterDescriptor

  override fun getContextReceivers(): List<ReceiverParameterDescriptor> {
    return owner
      .fields
      .filter { it.origin == IrDeclarationOrigin.FIELD_FOR_CLASS_CONTEXT_RECEIVER }
      .map(::IrBasedContextReceiverFieldDescriptor)
      .toList()
  }

  override fun getUnsubstitutedPrimaryConstructor() =
    owner.declarations.filterIsInstance<IrConstructor>().singleOrNull { it.isPrimary }?.toIrBasedDescriptor()

  override fun getDeclaredTypeParameters() = owner.typeParameters.memoryOptimizedMap { it.toIrBasedDescriptor() }

  override fun getSealedSubclasses(): Collection<ClassDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun getValueClassRepresentation(): ValueClassRepresentation<SimpleType>? =
    owner.valueClassRepresentation?.mapUnderlyingType { it.toIrBasedKotlinType() as SimpleType }

  override fun getOriginal() = this

  override fun isExpect() = owner.isExpect

  override fun substitute(substitutor: TypeSubstitutor): ClassifierDescriptorWithTypeParameters =
    throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")

  override fun isActual() = false

  private val _typeConstructor: TypeConstructor by lazy {
    LazyTypeConstructor(
      this,
      ::collectTypeParameters,
      { owner.superTypes.memoryOptimizedMap { it.toIrBasedKotlinType() } },
      LockBasedStorageManager.NO_LOCKS
    )
  }

  private fun collectTypeParameters(): List<TypeParameterDescriptor> =
    owner.typeConstructorParameters
      .map { it.toIrBasedDescriptor() }
      .toList()

  override fun getTypeConstructor(): TypeConstructor = _typeConstructor

  override fun isInner() = owner.isInner

  override fun isExternal() = owner.isExternal

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitClassDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitClassDescriptor(this, null)
  }

  override fun getDefaultFunctionTypeForSamInterface(): SimpleType? {
    unsupportedInIrBasedDescriptor()
  }

  override fun isDefinitelyNotSamInterface(): Boolean {
    unsupportedInIrBasedDescriptor()
  }
}

fun IrClass.toIrBasedDescriptor() = IrBasedClassDescriptor(this)

class LazyTypeConstructor(
  val classDescriptor: ClassDescriptor,
  val parametersBuilder: () -> List<TypeParameterDescriptor>,
  val superTypesBuilder: () -> List<KotlinType>,
  storageManager: StorageManager,
) : AbstractClassTypeConstructor(storageManager) {
  val parameters_ by lazy { parametersBuilder() }
  val superTypes_ by lazy { superTypesBuilder() }

  override fun getParameters() = parameters_

  override fun computeSupertypes() = superTypes_

  override fun isDenotable() = true

  override fun getDeclarationDescriptor() = classDescriptor

  override val supertypeLoopChecker: SupertypeLoopChecker
    get() = SupertypeLoopChecker.EMPTY
}

open class IrBasedEnumEntryDescriptor(owner: IrEnumEntry) : ClassDescriptor, IrBasedDeclarationDescriptor<IrEnumEntry>(owner) {
  override fun getName() = owner.name

  override fun getMemberScope(typeArguments: MutableList<out TypeProjection>) = MemberScope.Empty

  override fun getMemberScope(typeSubstitution: TypeSubstitution) = MemberScope.Empty

  override fun getUnsubstitutedMemberScope() = MemberScope.Empty

  override fun getUnsubstitutedInnerClassesScope() = MemberScope.Empty

  override fun getStaticScope() = MemberScope.Empty

  override fun getSource() = SourceElement.NO_SOURCE

  override fun getConstructors() =
    getCorrespondingClass().declarations.asSequence().filterIsInstance<IrConstructor>().map { it.toIrBasedDescriptor() }.toList()

  private fun getCorrespondingClass() = owner.correspondingClass ?: (owner.parent as IrClass)

  private val _defaultType: SimpleType by lazy {
    TypeUtils.makeUnsubstitutedType(this, unsubstitutedMemberScope, KotlinTypeFactory.EMPTY_REFINED_TYPE_FACTORY)
  }

  override fun getDefaultType(): SimpleType = _defaultType

  override fun getKind() = ClassKind.ENUM_ENTRY

  override fun getModality() = Modality.FINAL

  override fun getCompanionObjectDescriptor() = null

  override fun getVisibility() = DescriptorVisibilities.DEFAULT_VISIBILITY

  override fun isCompanionObject() = false

  override fun isData() = false

  override fun isInline() = false

  override fun isFun() = false

  override fun isValue() = false

  override fun getThisAsReceiverParameter() = (owner.parent as IrClass).toIrBasedDescriptor().thisAsReceiverParameter

  override fun getContextReceivers(): List<ReceiverParameterDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override fun getUnsubstitutedPrimaryConstructor(): ClassConstructorDescriptor? {
    unsupportedInIrBasedDescriptor()
  }

  override fun getDeclaredTypeParameters(): List<TypeParameterDescriptor> = emptyList()

  override fun getSealedSubclasses(): Collection<ClassDescriptor> = unsupportedInIrBasedDescriptor()

  override fun getValueClassRepresentation(): ValueClassRepresentation<SimpleType>? = unsupportedInIrBasedDescriptor()

  override fun getOriginal() = this

  override fun isExpect() = false

  override fun substitute(substitutor: TypeSubstitutor): ClassifierDescriptorWithTypeParameters =
    throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")

  override fun isActual() = false

  private val _typeConstructor: TypeConstructor by lazy {
    ClassTypeConstructorImpl(
      this,
      emptyList(),
      getCorrespondingClass().superTypes.memoryOptimizedMap { it.toIrBasedKotlinType() },
      LockBasedStorageManager.NO_LOCKS
    )
  }

  override fun getTypeConstructor(): TypeConstructor = _typeConstructor

  override fun isInner() = false

  override fun isExternal() = false

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitClassDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitClassDescriptor(this, null)
  }

  override fun getDefaultFunctionTypeForSamInterface(): SimpleType? = null

  override fun isDefinitelyNotSamInterface() = true
}

fun IrEnumEntry.toIrBasedDescriptor() = IrBasedEnumEntryDescriptor(this)

open class IrBasedPropertyDescriptor(owner: IrProperty) :
  PropertyDescriptor, DescriptorWithContainerSource, IrBasedDeclarationDescriptor<IrProperty>(owner) {
  override fun getModality() = owner.modality

  override fun setOverriddenDescriptors(overriddenDescriptors: MutableCollection<out CallableMemberDescriptor>) {
    unsupportedInIrBasedDescriptor()
  }

  override fun getKind() = CallableMemberDescriptor.Kind.SYNTHESIZED

  override fun getName() = owner.name

  override fun getSource() = SourceElement.NO_SOURCE

  override fun hasSynthesizedParameterNames(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun getOverriddenDescriptors(): MutableCollection<out PropertyDescriptor> = mutableListOf()

  override fun copy(
    newOwner: DeclarationDescriptor?,
    modality: Modality?,
    visibility: DescriptorVisibility?,
    kind: CallableMemberDescriptor.Kind?,
    copyOverrides: Boolean,
  ): CallableMemberDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun getValueParameters(): MutableList<ValueParameterDescriptor> = mutableListOf()

  override fun getCompileTimeInitializer(): ConstantValue<*>? {
    return null
  }

  override fun cleanCompileTimeInitializerCache() {}

  override fun isSetterProjectedOut(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun getAccessors(): List<PropertyAccessorDescriptor> = listOfNotNull(getter, setter)

  override fun getTypeParameters(): List<TypeParameterDescriptor> = getter?.typeParameters.orEmpty()

  override fun getVisibility() = owner.visibility

  override val setter: PropertySetterDescriptor? get() = owner.setter?.toIrBasedDescriptor() as? PropertySetterDescriptor

  override val containerSource: DeserializedContainerSource? = owner.containerSource

  override fun getOriginal() = this

  override fun isExpect() = owner.isExpect

  override fun substitute(substitutor: TypeSubstitutor): PropertyDescriptor =
    throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")

  override fun isActual() = false

  override fun getReturnType() = (owner.getter?.returnType ?: owner.backingField?.type!!).toIrBasedKotlinType()

  override fun hasStableParameterNames() = false

  override fun getType(): KotlinType = returnType

  override fun isVar() = owner.isVar

  override fun getDispatchReceiverParameter() =
    owner.getter?.dispatchReceiverParameter?.toIrBasedDescriptor() as? ReceiverParameterDescriptor

  override fun isConst() = owner.isConst

  override fun isLateInit() = owner.isLateinit

  override fun getExtensionReceiverParameter() = owner.getter?.parameters
    ?.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
    ?.toIrBasedDescriptor() as? ReceiverParameterDescriptor

  override fun getContextReceiverParameters(): List<ReceiverParameterDescriptor> {
    return getter?.contextReceiverParameters ?: emptyList()
  }

  override fun isExternal() = owner.isExternal

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D) =
    visitor!!.visitPropertyDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitPropertyDescriptor(this, null)
  }

  override val getter: PropertyGetterDescriptor? get() = owner.getter?.toIrBasedDescriptor() as? PropertyGetterDescriptor

  override fun newCopyBuilder(): CallableMemberDescriptor.CopyBuilder<out PropertyDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override val isDelegated get() = owner.isDelegated

  override fun getBackingField(): FieldDescriptor? =
    owner.backingField?.toIrBackingFieldDescriptor(isDelegate = false, correspondingProperty = this)

  override fun getDelegateField(): FieldDescriptor? =
    owner.backingField?.toIrBackingFieldDescriptor(isDelegate = true, correspondingProperty = this)

  override fun getInType(): KotlinType? = setter?.valueParameters?.get(0)?.type

  override fun <V : Any?> getUserData(key: CallableDescriptor.UserDataKey<V>?): V? = null
}

private fun IrField.toIrBackingFieldDescriptor(
  isDelegate: Boolean,
  correspondingProperty: PropertyDescriptor,
): IrBasedBackingFieldDescriptor? =
  if (isDelegate == (origin == IrDeclarationOrigin.PROPERTY_DELEGATE)) {
    IrBasedBackingFieldDescriptor(this, correspondingProperty)
  } else {
    null
  }

fun IrProperty.toIrBasedDescriptor() = IrBasedPropertyDescriptor(this)

abstract class IrBasedPropertyAccessorDescriptor(owner: IrSimpleFunction) : IrBasedSimpleFunctionDescriptor(owner),
  PropertyAccessorDescriptor {
  override fun isDefault(): Boolean = false

  override fun getOriginal(): IrBasedPropertyAccessorDescriptor = this

  override fun getOverriddenDescriptors() = super.getOverriddenDescriptors().memoryOptimizedMap { it as PropertyAccessorDescriptor }

  override fun getCorrespondingProperty(): PropertyDescriptor = owner.correspondingPropertySymbol!!.owner.toIrBasedDescriptor()

  override val correspondingVariable: VariableDescriptorWithAccessors get() = correspondingProperty
}

open class IrBasedPropertyGetterDescriptor(owner: IrSimpleFunction) : IrBasedPropertyAccessorDescriptor(owner), PropertyGetterDescriptor {
  override fun getOverriddenDescriptors() = super.getOverriddenDescriptors().memoryOptimizedMap { it as PropertyGetterDescriptor }

  override fun getOriginal(): IrBasedPropertyGetterDescriptor = this

  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitPropertyGetterDescriptor(this, data)
}

class IrBasedBackingFieldDescriptor(val owner: IrField, override val correspondingProperty: PropertyDescriptor) : FieldDescriptor {

  override val annotations: Annotations by lazy(owner::toAnnotations)

  override fun equals(other: Any?): Boolean = other is IrBasedBackingFieldDescriptor && owner == other.owner

  override fun hashCode(): Int = owner.hashCode()
}

open class IrBasedPropertySetterDescriptor(owner: IrSimpleFunction) : IrBasedPropertyAccessorDescriptor(owner), PropertySetterDescriptor {
  override fun getOverriddenDescriptors() = super.getOverriddenDescriptors().memoryOptimizedMap { it as PropertySetterDescriptor }

  override fun getOriginal(): IrBasedPropertySetterDescriptor = this

  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R =
    visitor!!.visitPropertySetterDescriptor(this, data)
}

open class IrBasedTypeAliasDescriptor(owner: IrTypeAlias) : IrBasedDeclarationDescriptor<IrTypeAlias>(owner), TypeAliasDescriptor {

  override val underlyingType: SimpleType
    get() = throw UnsupportedOperationException("Unexpected use of IrBasedTypeAliasDescriptor $this")

  override val constructors: Collection<TypeAliasConstructorDescriptor>
    get() = throw UnsupportedOperationException("Unexpected use of IrBasedTypeAliasDescriptor $this")

  override fun substitute(substitutor: TypeSubstitutor): ClassifierDescriptorWithTypeParameters =
    throw UnsupportedOperationException("IrBased descriptors should not be substituted")

  override fun getDefaultType(): SimpleType =
    throw UnsupportedOperationException("Unexpected use of IrBasedTypeAliasDescriptor $this")

  override fun getTypeConstructor(): TypeConstructor =
    throw UnsupportedOperationException("Unexpected use of IrBasedTypeAliasDescriptor $this")

  override val expandedType: SimpleType
    get() = owner.expandedType.toIrBasedKotlinType() as SimpleType

  override val classDescriptor: ClassDescriptor?
    get() = unsupportedInIrBasedDescriptor()

  override fun getOriginal(): TypeAliasDescriptor = this

  override fun isInner(): Boolean = false

  override fun getDeclaredTypeParameters(): List<TypeParameterDescriptor> = owner.typeParameters.memoryOptimizedMap { it.toIrBasedDescriptor() }

  override fun getName(): Name = owner.name

  override fun getModality(): Modality = Modality.FINAL

  override fun getSource(): SourceElement = SourceElement.NO_SOURCE

  override fun getVisibility(): DescriptorVisibility = owner.visibility

  override fun isExpect(): Boolean = false

  override fun isActual(): Boolean = owner.isActual

  override fun isExternal(): Boolean = false

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>, data: D): R =
    visitor.visitTypeAliasDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>) {
    visitor.visitTypeAliasDescriptor(this, null)
  }
}

fun IrTypeAlias.toIrBasedDescriptor() = IrBasedTypeAliasDescriptor(this)

open class IrBasedFieldDescriptor(owner: IrField) : PropertyDescriptor, IrBasedDeclarationDescriptor<IrField>(owner) {
  override fun getModality() = if (owner.isFinal) Modality.FINAL else Modality.OPEN

  override fun setOverriddenDescriptors(overriddenDescriptors: MutableCollection<out CallableMemberDescriptor>) {
    unsupportedInIrBasedDescriptor()
  }

  override fun getKind() = CallableMemberDescriptor.Kind.SYNTHESIZED

  override fun getName() = owner.name

  override fun getSource() = SourceElement.NO_SOURCE

  override fun hasSynthesizedParameterNames() = false

  override fun getOverriddenDescriptors(): MutableCollection<out PropertyDescriptor> = mutableListOf()

  override fun copy(
    newOwner: DeclarationDescriptor?,
    modality: Modality?,
    visibility: DescriptorVisibility?,
    kind: CallableMemberDescriptor.Kind?,
    copyOverrides: Boolean,
  ): CallableMemberDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun getValueParameters(): MutableList<ValueParameterDescriptor> = mutableListOf()

  override fun getCompileTimeInitializer(): ConstantValue<*>? {
    unsupportedInIrBasedDescriptor()
  }

  override fun cleanCompileTimeInitializerCache() {}

  override fun isSetterProjectedOut(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun getAccessors(): MutableList<PropertyAccessorDescriptor> = mutableListOf()

  override fun getTypeParameters(): List<TypeParameterDescriptor> = emptyList()

  override fun getVisibility() = owner.visibility

  override val setter: PropertySetterDescriptor? get() = null

  override fun getOriginal() = this

  override fun isExpect() = false

  override fun substitute(substitutor: TypeSubstitutor): PropertyDescriptor =
    throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")

  override fun isActual() = false

  override fun getReturnType() = owner.type.toIrBasedKotlinType()

  override fun hasStableParameterNames(): Boolean {
    unsupportedInIrBasedDescriptor()
  }

  override fun getType(): KotlinType = owner.type.toIrBasedKotlinType()

  override fun isVar() = !owner.isFinal

  override fun getDispatchReceiverParameter(): ReceiverParameterDescriptor? =
    if (owner.isStatic) null else (owner.parentAsClass.thisReceiver?.toIrBasedDescriptor() as ReceiverParameterDescriptor)

  override fun isConst() = false

  override fun isLateInit() = owner.correspondingPropertySymbol?.owner?.isLateinit ?: false

  override fun getExtensionReceiverParameter(): ReceiverParameterDescriptor? =
    owner.correspondingPropertySymbol?.owner?.toIrBasedDescriptor()?.extensionReceiverParameter

  override fun getContextReceiverParameters(): List<ReceiverParameterDescriptor> {
    return owner.correspondingPropertySymbol?.owner?.toIrBasedDescriptor()?.contextReceiverParameters ?: emptyList()
  }

  override fun isExternal() = owner.isExternal

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D) =
    visitor!!.visitPropertyDescriptor(this, data)

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    visitor!!.visitPropertyDescriptor(this, null)
  }

  override val getter: PropertyGetterDescriptor? get() = null

  override fun newCopyBuilder(): CallableMemberDescriptor.CopyBuilder<out PropertyDescriptor> {
    unsupportedInIrBasedDescriptor()
  }

  override val isDelegated get() = false

  // Following functions are used in error reporting when rendering annotations on properties
  override fun getBackingField(): FieldDescriptor? =
    owner.toIrBackingFieldDescriptor(isDelegate = false, correspondingProperty = this)

  override fun getDelegateField(): FieldDescriptor? =
    owner.toIrBackingFieldDescriptor(isDelegate = true, correspondingProperty = this)

  override fun getInType(): KotlinType? = setter?.valueParameters?.get(0)?.type

  override fun <V : Any?> getUserData(key: CallableDescriptor.UserDataKey<V>?): V? = null
}

class IrBasedDelegateFieldDescriptor(owner: IrField) : IrBasedFieldDescriptor(owner), IrImplementingDelegateDescriptor {

  override val correspondingSuperType: KotlinType
    get() = unsupportedInIrBasedDescriptor()

  override val isDelegated: Boolean
    get() = true
}

class IrBasedContextReceiverFieldDescriptor(owner: IrField) : IrBasedCallableDescriptor<IrField>(owner), ReceiverParameterDescriptor {
  override fun getOriginal() = this

  override fun substitute(substitutor: TypeSubstitutor): Nothing {
    unsupportedInIrBasedDescriptor()
  }

  override fun getName(): Name = owner.name

  override fun getValue(): ReceiverValue {
    unsupportedInIrBasedDescriptor()
  }

  override fun copy(newOwner: DeclarationDescriptor): ReceiverParameterDescriptor {
    unsupportedInIrBasedDescriptor()
  }

  override fun getType(): KotlinType = owner.type.toIrBasedKotlinType()
}

fun IrField.toIrBasedDescriptor() = if (origin == IrDeclarationOrigin.DELEGATE) {
  IrBasedDelegateFieldDescriptor(this)
} else {
  IrBasedFieldDescriptor(this)
}

class IrBasedErrorDescriptor(owner: IrErrorDeclaration) : IrBasedDeclarationDescriptor<IrErrorDeclaration>(owner) {
  override fun getName(): Name = error("IrBasedErrorDescriptor.getName: Should not be reached")

  override fun getOriginal(): DeclarationDescriptorWithSource =
    error("IrBasedErrorDescriptor.getOriginal: Should not be reached")

  override fun getContainingDeclaration(): DeclarationDescriptor =
    error("IrBasedErrorDescriptor.getContainingDeclaration: Should not be reached")

  override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R {
    error("IrBasedErrorDescriptor.accept: Should not be reached")
  }

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
  }
}

fun IrErrorDeclaration.toIrBasedDescriptor() = IrBasedErrorDescriptor(this)

@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun getContainingDeclaration(declaration: IrDeclaration): DeclarationDescriptor {
  val parent = declaration.parent
  if (declaration is IrTypeParameter && parent is IrSimpleFunction) {
    parent.correspondingPropertySymbol?.owner?.let { return it.toIrBasedDescriptor() }
  }
  val parentDescriptor = (parent as IrSymbolOwner).let {
    if (it is IrDeclaration) it.toIrBasedDescriptor() else it.symbol.descriptor
  }
  return if (parent is IrClass && parent.isFileClass) {
    // JVM IR adds facade classes for IR of functions/properties loaded both from sources and dependencies. However, these shouldn't
    // exist in the descriptor hierarchy, since this is what the old backend (dealing with descriptors) expects.
    parentDescriptor.containingDeclaration!!
  } else {
    parentDescriptor
  }
}

fun IrType.toIrBasedKotlinType(builtins: KotlinBuiltIns? = null): KotlinType = when (this) {
  is IrSimpleType ->
    makeKotlinType(classifier, arguments, isMarkedNullable(), builtins).let {
      if (classifier is IrTypeParameterSymbol && nullability == SimpleTypeNullability.DEFINITELY_NOT_NULL) {
        // avoidCheckingActualTypeNullability = true is necessary because in recursive cases `makesSenseToBeDefinitelyNotNull` triggers
        // supertype computation for a type parameter and for which bounds we need to call `toIrBasedKotlinType` again
        DefinitelyNotNullType.makeDefinitelyNotNull(it.unwrap(), avoidCheckingActualTypeNullability = true) ?: it
      } else {
        it
      }
    }
  is IrDynamicType -> originalKotlinType ?: builtins?.let(::createDynamicType) ?: error("Couldn't instantiate DynamicType")
  is IrErrorType -> originalKotlinType ?: error("Can't find KotlinType in IrErrorType: " + (this as IrType).render())
}

private fun makeKotlinType(
  classifier: IrClassifierSymbol,
  arguments: List<IrTypeArgument>,
  hasQuestionMark: Boolean,
  builtins: KotlinBuiltIns?,
): SimpleType =
  when (classifier) {
    is IrTypeParameterSymbol ->
      classifier.toIrBasedDescriptorIfPossible().defaultType.makeNullableAsSpecified(hasQuestionMark)
    is IrClassSymbol -> {
      val classDescriptor = classifier.toIrBasedDescriptorIfPossible()
      val kotlinTypeArguments = arguments.memoryOptimizedMapIndexed { index, it ->
        when (it) {
          is IrTypeProjection -> TypeProjectionImpl(it.variance, it.type.toIrBasedKotlinType(builtins))
          is IrStarProjection -> StarProjectionImpl(classDescriptor.typeConstructor.parameters[index])
        }
      }

      try {
        classDescriptor.defaultType.replace(newArguments = kotlinTypeArguments).makeNullableAsSpecified(hasQuestionMark)
      } catch (e: Throwable) {
        throw RuntimeException(
          "Classifier: ${classifier.owner.render()}\n" +
            "Type parameters:\n" +
            classDescriptor.defaultType.constructor.parameters.withIndex()
              .joinToString(separator = "\n") {
                val irTypeParameter = (it.value as IrBasedTypeParameterDescriptor).owner
                "${it.index}: ${irTypeParameter.render()} " +
                  "of ${irTypeParameter.parent.render()}"
              } +
            "\nType arguments:\n" +
            arguments.withIndex()
              .joinToString(separator = "\n") {
                "${it.index}: ${it.value.render()}"
              },
          e
        )
      }
    }
    is IrScriptSymbol -> {
      TypeUtils.makeUnsubstitutedType(
        classifier.toIrBasedDescriptorIfPossible(),
        MemberScope.Empty,
        KotlinTypeFactory.EMPTY_REFINED_TYPE_FACTORY
      ).makeNullableAsSpecified(hasQuestionMark)
    }
  }

/* When IR-based descriptors are used from Psi2Ir, symbols may be unbound, thus we may need to resort to real descriptors. */
@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun IrClassSymbol.toIrBasedDescriptorIfPossible(): ClassDescriptor =
  if (isBound) owner.toIrBasedDescriptor() else descriptor

@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun IrTypeParameterSymbol.toIrBasedDescriptorIfPossible(): TypeParameterDescriptor =
  if (isBound) owner.toIrBasedDescriptor() else descriptor

@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun IrSimpleFunctionSymbol.toIrBasedDescriptorIfPossible(): FunctionDescriptor =
  if (isBound) owner.toIrBasedDescriptor() else descriptor

@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun IrPropertySymbol.toIrBasedDescriptorIfPossible(): PropertyDescriptor =
  if (isBound) owner.toIrBasedDescriptor() else descriptor

// this is a temporary solution for scripts
// TODO: implement IR-based descriptors for scripts, see KT-60631
@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun IrScriptSymbol.toIrBasedDescriptorIfPossible(): ScriptDescriptor = descriptor

// see comment above to IrScriptSymbol.toIrBasedDescriptorIfPossible()
@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun IrScript.toIrBasedDescriptor() = descriptor

private fun IrElement.toConstantValue(): ConstantValue<*> {
  return when (this) {
    is IrConst -> when (kind) {
      IrConstKind.Null -> NullValue()
      IrConstKind.Boolean -> BooleanValue(value as Boolean)
      IrConstKind.Char -> CharValue(value as Char)
      IrConstKind.Byte -> ByteValue(value as Byte)
      IrConstKind.Short -> ShortValue(value as Short)
      IrConstKind.Int -> IntValue(value as Int)
      IrConstKind.Long -> LongValue(value as Long)
      IrConstKind.String -> StringValue(value as String)
      IrConstKind.Float -> FloatValue(value as Float)
      IrConstKind.Double -> DoubleValue(value as Double)
    }

    is IrVararg -> {
      val elements = elements.memoryOptimizedMap { if (it is IrSpreadElement) error("$it is not expected") else it.toConstantValue() }
      ArrayValue(elements) { moduleDescriptor ->
        // TODO: substitute.
        moduleDescriptor.builtIns.array.defaultType
      }
    }

    is IrGetEnumValue -> EnumValue(symbol.owner.parentAsClass.toIrBasedDescriptor().classId!!, symbol.owner.name)

    is IrClassReference -> KClassValue((classType.classifierOrFail.owner as IrClass).toIrBasedDescriptor().classId!!, /*TODO*/0)

    is IrConstructorCall -> AnnotationValue(this.toAnnotationDescriptor())

    else -> error("$this is not expected: ${this.dump()}")
  }
}

private fun IrConstructorCall.toAnnotationDescriptor(): AnnotationDescriptor {
  val annotationClass = symbol.owner.parentAsClass

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  if (annotationClass.symbol.descriptor == ErrorUtils.errorClass || type is IrErrorType) {
    // This should be possible only in case of KAPT3 where IR generated by psi2ir/fir2ir can have annotations with unresolved types.
    // Apparently annotations with unresolved types is a useful feature for KAPT3 in the "correct error types" mode, see kt32596.kt.
    // This hack ensures that KAPT3 can obtain the original element (PSI or FIR) to generate the annotation name.
    return AnnotationDescriptorImpl(type.toKotlinType(), emptyMap(), source)
  }

  assert(annotationClass.isAnnotationClass) {
    "Expected call to constructor of annotation class but was: ${this.dump()}"
  }
  return AnnotationDescriptorImpl(
    annotationClass.defaultType.toIrBasedKotlinType(),
    symbol.owner.parameters.memoryOptimizedMap { it.name to arguments[it.indexInParameters] }
      .filter { it.second != null }
      .associate { it.first to it.second!!.toConstantValue() },
    source
  )
}

private fun IrDeclaration.toAnnotations(): Annotations {
  val ownerAnnotations = (this as? IrAnnotationContainer)?.annotations ?: return Annotations.EMPTY
  return Annotations.create(ownerAnnotations.memoryOptimizedMap(IrConstructorCall::toAnnotationDescriptor))
}
