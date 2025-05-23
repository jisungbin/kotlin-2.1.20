/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.descriptors.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import kotlin.annotations.jvm.ReadOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorVisitor;
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities;
import org.jetbrains.kotlin.descriptors.DescriptorVisibility;
import org.jetbrains.kotlin.descriptors.FieldDescriptor;
import org.jetbrains.kotlin.descriptors.FunctionDescriptor;
import org.jetbrains.kotlin.descriptors.Modality;
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyGetterDescriptor;
import org.jetbrains.kotlin.descriptors.PropertySetterDescriptor;
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor;
import org.jetbrains.kotlin.descriptors.annotations.Annotations;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.resolve.scopes.receivers.ContextReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExtensionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitContextReceiver;
import org.jetbrains.kotlin.types.DescriptorSubstitutor;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeSubstitution;
import org.jetbrains.kotlin.types.TypeSubstitutor;
import org.jetbrains.kotlin.types.Variance;
import org.jetbrains.kotlin.utils.SmartSet;
import static org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt.getBuiltIns;

@SuppressWarnings("deprecation")
public class PropertyDescriptorImpl extends VariableDescriptorWithInitializerImpl implements PropertyDescriptor {
  private final Modality modality;
  private final PropertyDescriptor original;
  private final Kind kind;
  private final boolean lateInit;
  private final boolean isConst;
  private final boolean isExpect;
  private final boolean isActual;
  private final boolean isExternal;
  private final boolean isDelegated;
  private DescriptorVisibility visibility;
  private Collection<? extends PropertyDescriptor> overriddenProperties = null;
  private List<ReceiverParameterDescriptor> contextReceiverParameters = Collections.emptyList();
  private ReceiverParameterDescriptor dispatchReceiverParameter;
  private ReceiverParameterDescriptor extensionReceiverParameter;
  private List<TypeParameterDescriptor> typeParameters;
  private PropertyGetterDescriptorImpl getter;
  private PropertySetterDescriptor setter;
  private boolean setterProjectedOut;
  private FieldDescriptor backingField;
  private FieldDescriptor delegateField;

  protected PropertyDescriptorImpl(
    @NotNull DeclarationDescriptor containingDeclaration,
    @Nullable PropertyDescriptor original,
    @NotNull Annotations annotations,
    @NotNull Modality modality,
    @NotNull DescriptorVisibility visibility,
    boolean isVar,
    @NotNull Name name,
    @NotNull Kind kind,
    @NotNull SourceElement source,
    boolean lateInit,
    boolean isConst,
    boolean isExpect,
    boolean isActual,
    boolean isExternal,
    boolean isDelegated
  ) {
    super(containingDeclaration, annotations, name, null, isVar, source);
    this.modality = modality;
    this.visibility = visibility;
    this.original = original == null ? this : original;
    this.kind = kind;
    this.lateInit = lateInit;
    this.isConst = isConst;
    this.isExpect = isExpect;
    this.isActual = isActual;
    this.isExternal = isExternal;
    this.isDelegated = isDelegated;
  }

  @NotNull
  public static PropertyDescriptorImpl create(
    @NotNull DeclarationDescriptor containingDeclaration,
    @NotNull Annotations annotations,
    @NotNull Modality modality,
    @NotNull DescriptorVisibility visibility,
    boolean isVar,
    @NotNull Name name,
    @NotNull Kind kind,
    @NotNull SourceElement source,
    boolean lateInit,
    boolean isConst,
    boolean isExpect,
    boolean isActual,
    boolean isExternal,
    boolean isDelegated
  ) {
    return new PropertyDescriptorImpl(containingDeclaration, null, annotations,
      modality, visibility, isVar, name, kind, source, lateInit, isConst,
      isExpect, isActual, isExternal, isDelegated);
  }

  private static DescriptorVisibility normalizeVisibility(DescriptorVisibility prev, Kind kind) {
    if (kind == Kind.FAKE_OVERRIDE && DescriptorVisibilities.isPrivate(prev.normalize())) {
      return DescriptorVisibilities.INVISIBLE_FAKE;
    }
    return prev;
  }

  private static ReceiverParameterDescriptor substituteParameterDescriptor(
    TypeSubstitutor substitutor,
    PropertyDescriptor substitutedPropertyDescriptor,
    ReceiverParameterDescriptor receiverParameterDescriptor
  ) {
    KotlinType substitutedType = substitutor.substitute(receiverParameterDescriptor.getType(), Variance.IN_VARIANCE);
    if (substitutedType == null) return null;
    return new ReceiverParameterDescriptorImpl(
      substitutedPropertyDescriptor,
      new ExtensionReceiver(substitutedPropertyDescriptor, substitutedType, receiverParameterDescriptor.getValue()),
      receiverParameterDescriptor.getAnnotations()
    );
  }

  private static ReceiverParameterDescriptor substituteContextParameterDescriptor(
    TypeSubstitutor substitutor,
    PropertyDescriptor substitutedPropertyDescriptor,
    ReceiverParameterDescriptor receiverParameterDescriptor
  ) {
    KotlinType substitutedType = substitutor.substitute(receiverParameterDescriptor.getType(), Variance.IN_VARIANCE);
    if (substitutedType == null) return null;
    return new ReceiverParameterDescriptorImpl(
      substitutedPropertyDescriptor,
      new ContextReceiver(substitutedPropertyDescriptor,
        substitutedType,
        ((ImplicitContextReceiver) receiverParameterDescriptor.getValue()).getCustomLabelName(),
        receiverParameterDescriptor.getValue()),
      receiverParameterDescriptor.getAnnotations()
    );
  }

  private static FunctionDescriptor getSubstitutedInitialSignatureDescriptor(
    @NotNull TypeSubstitutor substitutor,
    @NotNull PropertyAccessorDescriptor accessorDescriptor
  ) {
    return accessorDescriptor.getInitialSignatureDescriptor() != null
      ? accessorDescriptor.getInitialSignatureDescriptor().substitute(substitutor)
      : null;
  }

  @Override
  public KotlinType getInType() {
    return setter != null ? setter.getValueParameters().get(0).getType() : null;
  }

  public void setInType(@NotNull KotlinType inType) {
    /* Do nothing as the corresponding setter is generated by default */
  }

  @kotlin.Deprecated(message = "This method is left for binary compatibility with android.nav.safearg plugin. Used in SafeArgSyntheticDescriptorGenerator.kt")
  public void setType(
    @NotNull KotlinType outType,
    @ReadOnly @NotNull List<? extends TypeParameterDescriptor> typeParameters,
    @Nullable ReceiverParameterDescriptor dispatchReceiverParameter,
    @Nullable ReceiverParameterDescriptor extensionReceiverParameter
  ) {
    setType(outType, typeParameters, dispatchReceiverParameter, extensionReceiverParameter,
      Collections.<ReceiverParameterDescriptor>emptyList());
  }

  public void setType(
    @NotNull KotlinType outType,
    @ReadOnly @NotNull List<? extends TypeParameterDescriptor> typeParameters,
    @Nullable ReceiverParameterDescriptor dispatchReceiverParameter,
    @Nullable ReceiverParameterDescriptor extensionReceiverParameter,
    @NotNull List<ReceiverParameterDescriptor> contextReceiverParameters
  ) {
    setOutType(outType);

    this.typeParameters = new ArrayList<TypeParameterDescriptor>(typeParameters);

    this.extensionReceiverParameter = extensionReceiverParameter;
    this.dispatchReceiverParameter = dispatchReceiverParameter;
    this.contextReceiverParameters = contextReceiverParameters;
  }

  public void initialize(
    @Nullable PropertyGetterDescriptorImpl getter,
    @Nullable PropertySetterDescriptor setter
  ) {
    initialize(getter, setter, null, null);
  }

  public void initialize(
    @Nullable PropertyGetterDescriptorImpl getter,
    @Nullable PropertySetterDescriptor setter,
    @Nullable FieldDescriptor backingField,
    @Nullable FieldDescriptor delegateField
  ) {
    this.getter = getter;
    this.setter = setter;
    this.backingField = backingField;
    this.delegateField = delegateField;
  }

  @NotNull
  @Override
  public List<TypeParameterDescriptor> getTypeParameters() {
    List<TypeParameterDescriptor> parameters = typeParameters;
    // Diagnostics for EA-212070
    if (parameters == null) {
      throw new IllegalStateException("typeParameters == null for " + this);
    }
    return parameters;
  }

  @Override
  public void validate() {
    getTypeParameters();
  }

  @Override
  @NotNull
  public List<ReceiverParameterDescriptor> getContextReceiverParameters() {
    return contextReceiverParameters;
  }

  @Override
  @Nullable
  public ReceiverParameterDescriptor getExtensionReceiverParameter() {
    return extensionReceiverParameter;
  }

  @Nullable
  @Override
  public ReceiverParameterDescriptor getDispatchReceiverParameter() {
    return dispatchReceiverParameter;
  }

  @NotNull
  @Override
  public KotlinType getReturnType() {
    return getType();
  }

  @NotNull
  @Override
  public Modality getModality() {
    return modality;
  }

  @NotNull
  @Override
  public DescriptorVisibility getVisibility() {
    return visibility;
  }

  public void setVisibility(@NotNull DescriptorVisibility visibility) {
    this.visibility = visibility;
  }

  @Override
  @Nullable
  public PropertyGetterDescriptorImpl getGetter() {
    return getter;
  }

  @Override
  @Nullable
  public PropertySetterDescriptor getSetter() {
    return setter;
  }

  @Override
  public boolean isSetterProjectedOut() {
    return setterProjectedOut;
  }

  public void setSetterProjectedOut(boolean setterProjectedOut) {
    this.setterProjectedOut = setterProjectedOut;
  }

  @Override
  public boolean isLateInit() {
    return lateInit;
  }

  @Override
  public boolean isConst() {
    return isConst;
  }

  @Override
  public boolean isExternal() {
    return isExternal;
  }

  @Override
  public boolean isDelegated() {
    return isDelegated;
  }

  @Override
  @NotNull
  public List<PropertyAccessorDescriptor> getAccessors() {
    List<PropertyAccessorDescriptor> result = new ArrayList<PropertyAccessorDescriptor>(2);
    if (getter != null) {
      result.add(getter);
    }
    if (setter != null) {
      result.add(setter);
    }
    return result;
  }

  @Override
  public PropertyDescriptor substitute(@NotNull TypeSubstitutor originalSubstitutor) {
    if (originalSubstitutor.isEmpty()) {
      return this;
    }

    return newCopyBuilder()
      .setSubstitution(originalSubstitutor.getSubstitution())
      .setOriginal(getOriginal())
      .build();
  }

  @NotNull
  @Override
  public CopyConfiguration newCopyBuilder() {
    return new CopyConfiguration();
  }

  @NotNull
  private SourceElement getSourceToUseForCopy(boolean preserveSource, @Nullable PropertyDescriptor original) {
    return preserveSource
      ? (original != null ? original : getOriginal()).getSource()
      : SourceElement.NO_SOURCE;
  }

  @Nullable
  protected PropertyDescriptor doSubstitute(@NotNull CopyConfiguration copyConfiguration) {
    PropertyDescriptorImpl substitutedDescriptor = createSubstitutedCopy(
      copyConfiguration.owner, copyConfiguration.modality, copyConfiguration.visibility,
      copyConfiguration.original, copyConfiguration.kind, copyConfiguration.name,
      getSourceToUseForCopy(copyConfiguration.preserveSourceElement, copyConfiguration.original));

    List<TypeParameterDescriptor> originalTypeParameters =
      copyConfiguration.newTypeParameters == null ? getTypeParameters() : copyConfiguration.newTypeParameters;
    List<TypeParameterDescriptor> substitutedTypeParameters = new ArrayList<TypeParameterDescriptor>(originalTypeParameters.size());
    TypeSubstitutor substitutor = DescriptorSubstitutor.substituteTypeParameters(
      originalTypeParameters, copyConfiguration.substitution, substitutedDescriptor, substitutedTypeParameters
    );

    KotlinType originalOutType = copyConfiguration.returnType;
    KotlinType outType = substitutor.substitute(originalOutType, Variance.OUT_VARIANCE);
    if (outType == null) {
      return null; // TODO : tell the user that the property was projected out
    }

    KotlinType inType = substitutor.substitute(originalOutType, Variance.IN_VARIANCE);

    if (inType != null) {
      substitutedDescriptor.setInType(inType);
    }

    ReceiverParameterDescriptor substitutedDispatchReceiver;
    ReceiverParameterDescriptor dispatchReceiver = copyConfiguration.dispatchReceiverParameter;
    if (dispatchReceiver != null) {
      substitutedDispatchReceiver = dispatchReceiver.substitute(substitutor);
      if (substitutedDispatchReceiver == null) return null;
    } else {
      substitutedDispatchReceiver = null;
    }

    ReceiverParameterDescriptor substitutedExtensionReceiver;
    if (extensionReceiverParameter != null) {
      substitutedExtensionReceiver = substituteParameterDescriptor(substitutor, substitutedDescriptor, extensionReceiverParameter);
    } else {
      substitutedExtensionReceiver = null;
    }

    List<ReceiverParameterDescriptor> substitutedContextReceivers = new ArrayList<ReceiverParameterDescriptor>();
    for (ReceiverParameterDescriptor contextReceiverParameter : contextReceiverParameters) {
      ReceiverParameterDescriptor substitutedContextReceiver = substituteContextParameterDescriptor(substitutor, substitutedDescriptor,
        contextReceiverParameter);
      if (substitutedContextReceiver != null) {
        substitutedContextReceivers.add(substitutedContextReceiver);
      }
    }

    substitutedDescriptor.setType(outType, substitutedTypeParameters, substitutedDispatchReceiver, substitutedExtensionReceiver,
      substitutedContextReceivers);

    PropertyGetterDescriptorImpl newGetter = getter == null ? null : new PropertyGetterDescriptorImpl(
      substitutedDescriptor, getter.getAnnotations(), copyConfiguration.modality, normalizeVisibility(getter.getVisibility(), copyConfiguration.kind),
      getter.isDefault(), getter.isExternal(), getter.isInline(), copyConfiguration.kind,
      copyConfiguration.getOriginalGetter(),
      SourceElement.NO_SOURCE
    );
    if (newGetter != null) {
      KotlinType returnType = getter.getReturnType();
      newGetter.setInitialSignatureDescriptor(getSubstitutedInitialSignatureDescriptor(substitutor, getter));
      newGetter.initialize(returnType != null ? substitutor.substitute(returnType, Variance.OUT_VARIANCE) : null);
    }
    PropertySetterDescriptorImpl newSetter = setter == null ? null : new PropertySetterDescriptorImpl(
      substitutedDescriptor, setter.getAnnotations(), copyConfiguration.modality, normalizeVisibility(setter.getVisibility(), copyConfiguration.kind),
      setter.isDefault(), setter.isExternal(), setter.isInline(), copyConfiguration.kind,
      copyConfiguration.getOriginalSetter(),
      SourceElement.NO_SOURCE
    );
    if (newSetter != null) {
      List<ValueParameterDescriptor> substitutedValueParameters = FunctionDescriptorImpl.getSubstitutedValueParameters(
        newSetter, setter.getValueParameters(), substitutor, /* dropOriginal = */ false,
        false, null
      );
      if (substitutedValueParameters == null) {
        // The setter is projected out, e.g. in this case:
        //     trait Tr<T> { var v: T }
        //     fun test(tr: Tr<out Any?>) { ... }
        // we want to tell the user that although the property is declared as a var,
        // it can not be assigned to because of the projection
        substitutedDescriptor.setSetterProjectedOut(true);
        substitutedValueParameters = Collections.<ValueParameterDescriptor>singletonList(
          PropertySetterDescriptorImpl.createSetterParameter(
            newSetter,
            getBuiltIns(copyConfiguration.owner).getNothingType(),
            setter.getValueParameters().get(0).getAnnotations()
          )
        );
      }
      if (substitutedValueParameters.size() != 1) {
        throw new IllegalStateException();
      }
      newSetter.setInitialSignatureDescriptor(getSubstitutedInitialSignatureDescriptor(substitutor, setter));
      newSetter.initialize(substitutedValueParameters.get(0));
    }

    substitutedDescriptor.initialize(
      newGetter,
      newSetter,
      backingField == null ? null : new FieldDescriptorImpl(backingField.getAnnotations(), substitutedDescriptor),
      delegateField == null ? null : new FieldDescriptorImpl(delegateField.getAnnotations(), substitutedDescriptor)
    );

    if (copyConfiguration.copyOverrides) {
      Collection<CallableMemberDescriptor> overridden = SmartSet.create();
      for (PropertyDescriptor propertyDescriptor : getOverriddenDescriptors()) {
        overridden.add(propertyDescriptor.substitute(substitutor));
      }
      substitutedDescriptor.setOverriddenDescriptors(overridden);
    }

    if (isConst() && compileTimeInitializerFactory != null) {
      substitutedDescriptor.setCompileTimeInitializer(compileTimeInitializer, compileTimeInitializerFactory);
    }

    return substitutedDescriptor;
  }

  @NotNull
  protected PropertyDescriptorImpl createSubstitutedCopy(
    @NotNull DeclarationDescriptor newOwner,
    @NotNull Modality newModality,
    @NotNull DescriptorVisibility newVisibility,
    @Nullable PropertyDescriptor original,
    @NotNull Kind kind,
    @NotNull Name newName,
    @NotNull SourceElement source
  ) {
    return new PropertyDescriptorImpl(
      newOwner, original, getAnnotations(), newModality, newVisibility, isVar(), newName, kind, source,
      isLateInit(), isConst(), isExpect(), isActual(), isExternal(), isDelegated()
    );
  }

  @Override
  public <R, D> R accept(DeclarationDescriptorVisitor<R, D> visitor, D data) {
    return visitor.visitPropertyDescriptor(this, data);
  }

  @NotNull
  @Override
  public PropertyDescriptor getOriginal() {
    return original == this ? this : original.getOriginal();
  }

  @NotNull
  @Override
  public Kind getKind() {
    return kind;
  }

  @Override
  public boolean isExpect() {
    return isExpect;
  }

  @Override
  public boolean isActual() {
    return isActual;
  }

  @Override
  @Nullable
  public FieldDescriptor getBackingField() {
    return backingField;
  }

  @Override
  @Nullable
  public FieldDescriptor getDelegateField() {
    return delegateField;
  }

  @NotNull
  @Override
  public Collection<? extends PropertyDescriptor> getOverriddenDescriptors() {
    return overriddenProperties != null ? overriddenProperties : Collections.<PropertyDescriptor>emptyList();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void setOverriddenDescriptors(@NotNull Collection<? extends CallableMemberDescriptor> overriddenDescriptors) {
    this.overriddenProperties = (Collection<? extends PropertyDescriptor>) overriddenDescriptors;
  }

  @NotNull
  @Override
  public PropertyDescriptor copy(DeclarationDescriptor newOwner, Modality modality, DescriptorVisibility visibility, Kind kind, boolean copyOverrides) {
    //noinspection ConstantConditions
    return newCopyBuilder()
      .setOwner(newOwner)
      .setOriginal(null)
      .setModality(modality)
      .setVisibility(visibility)
      .setKind(kind)
      .setCopyOverrides(copyOverrides)
      .build();
  }

  @Nullable
  @Override
  public <V> V getUserData(UserDataKey<V> key) {
    return null;
  }

  public class CopyConfiguration implements PropertyDescriptor.CopyBuilder<PropertyDescriptor> {
    private DeclarationDescriptor owner = getContainingDeclaration();
    private Modality modality = getModality();
    private DescriptorVisibility visibility = getVisibility();
    private PropertyDescriptor original = null;
    private boolean preserveSourceElement = false;
    private Kind kind = getKind();
    private TypeSubstitution substitution = TypeSubstitution.EMPTY;
    private boolean copyOverrides = true;
    private ReceiverParameterDescriptor dispatchReceiverParameter = PropertyDescriptorImpl.this.dispatchReceiverParameter;
    private List<TypeParameterDescriptor> newTypeParameters = null;
    private Name name = getName();
    private KotlinType returnType = getType();

    @NotNull
    @Override
    public CopyConfiguration setOwner(@NotNull DeclarationDescriptor owner) {
      this.owner = owner;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setOriginal(@Nullable CallableMemberDescriptor original) {
      this.original = (PropertyDescriptor) original;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setPreserveSourceElement() {
      preserveSourceElement = true;
      return this;
    }

    @NotNull
    @Override
    public CopyBuilder<PropertyDescriptor> setReturnType(@NotNull KotlinType type) {
      returnType = type;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setModality(@NotNull Modality modality) {
      this.modality = modality;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setVisibility(@NotNull DescriptorVisibility visibility) {
      this.visibility = visibility;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setKind(@NotNull Kind kind) {
      this.kind = kind;
      return this;
    }

    @NotNull
    @Override
    public CopyBuilder<PropertyDescriptor> setTypeParameters(@NotNull List<TypeParameterDescriptor> typeParameters) {
      this.newTypeParameters = typeParameters;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setDispatchReceiverParameter(@Nullable ReceiverParameterDescriptor dispatchReceiverParameter) {
      this.dispatchReceiverParameter = dispatchReceiverParameter;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setSubstitution(@NotNull TypeSubstitution substitution) {
      this.substitution = substitution;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setCopyOverrides(boolean copyOverrides) {
      this.copyOverrides = copyOverrides;
      return this;
    }

    @NotNull
    @Override
    public CopyBuilder<PropertyDescriptor> setName(@NotNull Name name) {
      this.name = name;
      return this;
    }

    @Nullable
    @Override
    public PropertyDescriptor build() {
      return doSubstitute(this);
    }

    PropertyGetterDescriptor getOriginalGetter() {
      if (original == null) return null;
      return original.getGetter();
    }

    PropertySetterDescriptor getOriginalSetter() {
      if (original == null) return null;
      return original.getSetter();
    }
  }
}
