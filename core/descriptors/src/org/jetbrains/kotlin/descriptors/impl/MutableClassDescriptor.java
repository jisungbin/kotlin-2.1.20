/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor;
import org.jetbrains.kotlin.descriptors.ClassDescriptor;
import org.jetbrains.kotlin.descriptors.ClassKind;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.DescriptorVisibility;
import org.jetbrains.kotlin.descriptors.FunctionDescriptor;
import org.jetbrains.kotlin.descriptors.Modality;
import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ValueClassRepresentation;
import org.jetbrains.kotlin.descriptors.annotations.Annotations;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.resolve.scopes.MemberScope;
import org.jetbrains.kotlin.storage.StorageManager;
import org.jetbrains.kotlin.types.ClassTypeConstructorImpl;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.KotlinTypeKt;
import org.jetbrains.kotlin.types.SimpleType;
import org.jetbrains.kotlin.types.TypeConstructor;
import org.jetbrains.kotlin.types.TypeUtils;
import org.jetbrains.kotlin.types.checker.KotlinTypeRefiner;

public class MutableClassDescriptor extends ClassDescriptorBase {
  private final ClassKind kind;
  private final boolean isInner;
  private final Collection<KotlinType> supertypes = new ArrayList<KotlinType>();
  private final StorageManager storageManager;
  private Modality modality;
  private DescriptorVisibility visibility;
  private TypeConstructor typeConstructor;
  private List<TypeParameterDescriptor> typeParameters;

  public MutableClassDescriptor(
    @NotNull DeclarationDescriptor containingDeclaration,
    @NotNull ClassKind kind,
    boolean isInner,
    boolean isExternal,
    @NotNull Name name,
    @NotNull SourceElement source,
    @NotNull StorageManager storageManager
  ) {
    super(storageManager, containingDeclaration, name, source, isExternal);
    this.storageManager = storageManager;
    assert kind != ClassKind.OBJECT : "Fix isCompanionObject()";

    this.kind = kind;
    this.isInner = isInner;
  }

  @Nullable
  @Override
  public ClassDescriptor getCompanionObjectDescriptor() {
    return null;
  }

  @NotNull
  @Override
  public Annotations getAnnotations() {
    return Annotations.Companion.getEMPTY();
  }

  @Override
  @NotNull
  public Modality getModality() {
    return modality;
  }

  public void setModality(@NotNull Modality modality) {
    assert modality != Modality.SEALED : "Implement getSealedSubclasses() for this class: " + getClass();
    this.modality = modality;
  }

  @NotNull
  @Override
  public ClassKind getKind() {
    return kind;
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
  public boolean isInner() {
    return isInner;
  }

  @Override
  public boolean isData() {
    return false;
  }

  @Override
  public boolean isInline() {
    return false;
  }

  @Override
  public boolean isFun() {
    return false;
  }

  @Override
  public boolean isValue() {
    return false;
  }

  @Override
  public boolean isCompanionObject() {
    return false;
  }

  @Override
  public boolean isExpect() {
    return false;
  }

  @Override
  public boolean isActual() {
    return false;
  }

  @NotNull
  @Override
  public TypeConstructor getTypeConstructor() {
    return typeConstructor;
  }

  public void addSupertype(@NotNull KotlinType supertype) {
    assert !KotlinTypeKt.isError(supertype) : "Error types must be filtered out in DescriptorResolver";
    if (TypeUtils.getClassDescriptor(supertype) != null) {
      // See the Errors.SUPERTYPE_NOT_A_CLASS_OR_INTERFACE
      supertypes.add(supertype);
    }
  }

  @NotNull
  @Override
  public Set<ClassConstructorDescriptor> getConstructors() {
    return Collections.emptySet();
  }

  @Override
  @Nullable
  public ClassConstructorDescriptor getUnsubstitutedPrimaryConstructor() {
    return null;
  }

  public void setTypeParameterDescriptors(@NotNull List<TypeParameterDescriptor> typeParameters) {
    if (this.typeParameters != null) {
      throw new IllegalStateException("Type parameters are already set for " + getName());
    }
    this.typeParameters = new ArrayList<TypeParameterDescriptor>(typeParameters);
  }

  @NotNull
  @Override
  public List<TypeParameterDescriptor> getDeclaredTypeParameters() {
    return typeParameters;
  }

  public void createTypeConstructor() {
    assert typeConstructor == null : typeConstructor;
    this.typeConstructor = new ClassTypeConstructorImpl(this, typeParameters, supertypes, storageManager);
    for (FunctionDescriptor functionDescriptor : getConstructors()) {
      ((ClassConstructorDescriptorImpl) functionDescriptor).setReturnType(getDefaultType());
    }
  }

  @Override
  @NotNull
  public MemberScope getUnsubstitutedMemberScope(@NotNull KotlinTypeRefiner kotlinTypeRefiner) {
    return MemberScope.Empty.INSTANCE; // used for getDefaultType
  }

  @NotNull
  @Override
  public MemberScope getStaticScope() {
    return MemberScope.Empty.INSTANCE;
  }

  @NotNull
  @Override
  public Collection<ClassDescriptor> getSealedSubclasses() {
    return Collections.emptyList();
  }

  @Nullable
  @Override
  public ValueClassRepresentation<SimpleType> getValueClassRepresentation() {
    return null;
  }

  @Override
  public String toString() {
    return DeclarationDescriptorImpl.toString(this);
  }
}
