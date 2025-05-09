/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorVisitor;
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities;
import org.jetbrains.kotlin.descriptors.DescriptorVisibility;
import org.jetbrains.kotlin.descriptors.FunctionDescriptor;
import org.jetbrains.kotlin.descriptors.Modality;
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor;
import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor;
import org.jetbrains.kotlin.descriptors.VariableDescriptor;
import org.jetbrains.kotlin.descriptors.annotations.Annotations;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationsKt;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.resolve.DescriptorFactory;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExtensionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitContextReceiver;
import org.jetbrains.kotlin.types.DescriptorSubstitutor;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeSubstitution;
import org.jetbrains.kotlin.types.TypeSubstitutor;
import org.jetbrains.kotlin.types.Variance;
import org.jetbrains.kotlin.utils.SmartList;

public abstract class FunctionDescriptorImpl extends DeclarationDescriptorNonRootImpl implements FunctionDescriptor {
  private final FunctionDescriptor original;
  private final Kind kind;
  protected Map<UserDataKey<?>, Object> userDataMap = null;
  private List<TypeParameterDescriptor> typeParameters;
  private List<ValueParameterDescriptor> unsubstitutedValueParameters;
  private KotlinType unsubstitutedReturnType;
  private List<ReceiverParameterDescriptor> contextReceiverParameters;
  private ReceiverParameterDescriptor extensionReceiverParameter;
  private ReceiverParameterDescriptor dispatchReceiverParameter;
  private Modality modality;
  private DescriptorVisibility visibility = DescriptorVisibilities.UNKNOWN;
  private boolean isOperator = false;
  private boolean isInfix = false;
  private boolean isExternal = false;
  private boolean isInline = false;
  private boolean isTailrec = false;
  private boolean isExpect = false;
  private boolean isActual = false;
  // Difference between these hidden kinds:
  // 1. isHiddenToOvercomeSignatureClash prohibit calling such functions even in super-call context
  // 2. isHiddenForResolutionEverywhereBesideSupercalls propagates to it's overrides descriptors while isHiddenToOvercomeSignatureClash does not
  private boolean isHiddenToOvercomeSignatureClash = false;
  private boolean isHiddenForResolutionEverywhereBesideSupercalls = false;
  private boolean isSuspend = false;
  private boolean hasStableParameterNames = true;
  private boolean hasSynthesizedParameterNames = false;
  private Collection<? extends FunctionDescriptor> overriddenFunctions = null;
  private volatile Function0<Collection<FunctionDescriptor>> lazyOverriddenFunctionsTask = null;
  @Nullable
  private FunctionDescriptor initialSignatureDescriptor = null;

  protected FunctionDescriptorImpl(
    @NotNull DeclarationDescriptor containingDeclaration,
    @Nullable FunctionDescriptor original,
    @NotNull Annotations annotations,
    @NotNull Name name,
    @NotNull Kind kind,
    @NotNull SourceElement source
  ) {
    super(containingDeclaration, annotations, name, source);
    this.original = original == null ? this : original;
    this.kind = kind;
  }

  @Nullable
  public static List<ValueParameterDescriptor> getSubstitutedValueParameters(
    FunctionDescriptor substitutedDescriptor,
    @NotNull List<ValueParameterDescriptor> unsubstitutedValueParameters,
    @NotNull TypeSubstitutor substitutor
  ) {
    return getSubstitutedValueParameters(substitutedDescriptor, unsubstitutedValueParameters, substitutor, false, false, null);
  }

  @Nullable
  public static List<ValueParameterDescriptor> getSubstitutedValueParameters(
    FunctionDescriptor substitutedDescriptor,
    @NotNull List<ValueParameterDescriptor> unsubstitutedValueParameters,
    @NotNull TypeSubstitutor substitutor,
    boolean dropOriginal,
    boolean preserveSourceElement,
    @Nullable boolean[] wereChanges
  ) {
    List<ValueParameterDescriptor> result = new ArrayList<ValueParameterDescriptor>(unsubstitutedValueParameters.size());
    for (ValueParameterDescriptor unsubstitutedValueParameter : unsubstitutedValueParameters) {
      // TODO : Lazy?
      KotlinType substitutedType = substitutor.substitute(unsubstitutedValueParameter.getType(), Variance.IN_VARIANCE);
      KotlinType varargElementType = unsubstitutedValueParameter.getVarargElementType();
      KotlinType substituteVarargElementType =
        varargElementType == null ? null : substitutor.substitute(varargElementType, Variance.IN_VARIANCE);
      if (substitutedType == null) return null;
      if (substitutedType != unsubstitutedValueParameter.getType() || varargElementType != substituteVarargElementType) {
        if (wereChanges != null) {
          wereChanges[0] = true;
        }
      }

      Function0<List<VariableDescriptor>> destructuringVariablesAction = null;
      if (unsubstitutedValueParameter instanceof ValueParameterDescriptorImpl.WithDestructuringDeclaration) {
        final List<VariableDescriptor> destructuringVariables =
          ((ValueParameterDescriptorImpl.WithDestructuringDeclaration) unsubstitutedValueParameter)
            .getDestructuringVariables();
        destructuringVariablesAction = new Function0<List<VariableDescriptor>>() {
          @Override
          public List<VariableDescriptor> invoke() {
            return destructuringVariables;
          }
        };
      }

      result.add(
        ValueParameterDescriptorImpl.createWithDestructuringDeclarations(
          substitutedDescriptor,
          dropOriginal ? null : unsubstitutedValueParameter,
          unsubstitutedValueParameter.getIndex(),
          unsubstitutedValueParameter.getAnnotations(),
          unsubstitutedValueParameter.getName(),
          substitutedType,
          unsubstitutedValueParameter.declaresDefaultValue(),
          unsubstitutedValueParameter.isCrossinline(),
          unsubstitutedValueParameter.isNoinline(),
          substituteVarargElementType,
          preserveSourceElement ? unsubstitutedValueParameter.getSource() : SourceElement.NO_SOURCE,
          destructuringVariablesAction
        )
      );
    }
    return result;
  }

  @NotNull
  public FunctionDescriptorImpl initialize(
    @Nullable ReceiverParameterDescriptor extensionReceiverParameter,
    @Nullable ReceiverParameterDescriptor dispatchReceiverParameter,
    @NotNull List<ReceiverParameterDescriptor> contextReceiverParameters,
    @NotNull List<? extends TypeParameterDescriptor> typeParameters,
    @NotNull List<ValueParameterDescriptor> unsubstitutedValueParameters,
    @Nullable KotlinType unsubstitutedReturnType,
    @Nullable Modality modality,
    @NotNull DescriptorVisibility visibility
  ) {
    this.typeParameters = CollectionsKt.toList(typeParameters);
    this.unsubstitutedValueParameters = CollectionsKt.toList(unsubstitutedValueParameters);
    this.unsubstitutedReturnType = unsubstitutedReturnType;
    this.modality = modality;
    this.visibility = visibility;
    this.extensionReceiverParameter = extensionReceiverParameter;
    this.dispatchReceiverParameter = dispatchReceiverParameter;
    this.contextReceiverParameters = contextReceiverParameters;

    for (int i = 0; i < typeParameters.size(); ++i) {
      TypeParameterDescriptor typeParameterDescriptor = typeParameters.get(i);
      if (typeParameterDescriptor.getIndex() != i) {
        throw new IllegalStateException(typeParameterDescriptor + " index is " + typeParameterDescriptor.getIndex() + " but position is " + i);
      }
    }

    for (int i = 0; i < unsubstitutedValueParameters.size(); ++i) {
      // TODO fill me
      int firstValueParameterOffset = 0; // receiverParameter.exists() ? 1 : 0;
      ValueParameterDescriptor valueParameterDescriptor = unsubstitutedValueParameters.get(i);
      if (valueParameterDescriptor.getIndex() != i + firstValueParameterOffset) {
        throw new IllegalStateException(valueParameterDescriptor + "index is " + valueParameterDescriptor.getIndex() + " but position is " + i);
      }
    }

    return this;
  }

  public void setHasStableParameterNames(boolean hasStableParameterNames) {
    this.hasStableParameterNames = hasStableParameterNames;
  }

  public void setHasSynthesizedParameterNames(boolean hasSynthesizedParameterNames) {
    this.hasSynthesizedParameterNames = hasSynthesizedParameterNames;
  }

  @NotNull
  @Override
  public List<ReceiverParameterDescriptor> getContextReceiverParameters() {
    return contextReceiverParameters;
  }

  @Nullable
  @Override
  public ReceiverParameterDescriptor getExtensionReceiverParameter() {
    return extensionReceiverParameter;
  }

  public void setExtensionReceiverParameter(@NotNull ReceiverParameterDescriptor extensionReceiverParameter) {
    this.extensionReceiverParameter = extensionReceiverParameter;
  }

  @Nullable
  @Override
  public ReceiverParameterDescriptor getDispatchReceiverParameter() {
    return dispatchReceiverParameter;
  }

  @NotNull
  @Override
  public Collection<? extends FunctionDescriptor> getOverriddenDescriptors() {
    performOverriddenLazyCalculationIfNeeded();
    return overriddenFunctions != null ? overriddenFunctions : Collections.<FunctionDescriptor>emptyList();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void setOverriddenDescriptors(@NotNull Collection<? extends CallableMemberDescriptor> overriddenDescriptors) {
    overriddenFunctions = (Collection<? extends FunctionDescriptor>) overriddenDescriptors;
    for (FunctionDescriptor function : overriddenFunctions) {
      if (function.isHiddenForResolutionEverywhereBesideSupercalls()) {
        isHiddenForResolutionEverywhereBesideSupercalls = true;
        break;
      }
    }
  }

  private void performOverriddenLazyCalculationIfNeeded() {
    Function0<Collection<FunctionDescriptor>> overriddenTask = lazyOverriddenFunctionsTask;
    if (overriddenTask != null) {
      overriddenFunctions = overriddenTask.invoke();
      // Here it's important that this assignment is strictly after previous one
      // `lazyOverriddenFunctionsTask` is volatile, so when someone will see that it's null,
      // he can read consistent collection from `overriddenFunctions`,
      // because it's assignment happens-before of "lazyOverriddenFunctionsTask = null"
      lazyOverriddenFunctionsTask = null;
    }
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
  public boolean isOperator() {
    if (isOperator) return true;

    for (FunctionDescriptor descriptor : getOriginal().getOverriddenDescriptors()) {
      if (descriptor.isOperator()) return true;
    }

    return false;
  }

  public void setOperator(boolean isOperator) {
    this.isOperator = isOperator;
  }

  @Override
  public boolean isInfix() {
    if (isInfix) return true;

    for (FunctionDescriptor descriptor : getOriginal().getOverriddenDescriptors()) {
      if (descriptor.isInfix()) return true;
    }

    return false;
  }

  public void setInfix(boolean isInfix) {
    this.isInfix = isInfix;
  }

  @Override
  public boolean isExternal() {
    return isExternal;
  }

  public void setExternal(boolean isExternal) {
    this.isExternal = isExternal;
  }

  @Override
  public boolean isInline() {
    return isInline;
  }

  public void setInline(boolean isInline) {
    this.isInline = isInline;
  }

  @Override
  public boolean isTailrec() {
    return isTailrec;
  }

  public void setTailrec(boolean isTailrec) {
    this.isTailrec = isTailrec;
  }

  @Override
  public boolean isSuspend() {
    return isSuspend;
  }

  public void setSuspend(boolean suspend) {
    isSuspend = suspend;
  }

  @Override
  public boolean isExpect() {
    return isExpect;
  }

  public void setExpect(boolean isExpect) {
    this.isExpect = isExpect;
  }

  @Override
  public boolean isActual() {
    return isActual;
  }

  public void setActual(boolean isActual) {
    this.isActual = isActual;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <V> V getUserData(UserDataKey<V> key) {
    if (userDataMap == null) return null;
    return (V) userDataMap.get(key);
  }

  @Override
  public boolean isHiddenToOvercomeSignatureClash() {
    return isHiddenToOvercomeSignatureClash;
  }

  private void setHiddenToOvercomeSignatureClash(boolean hiddenToOvercomeSignatureClash) {
    isHiddenToOvercomeSignatureClash = hiddenToOvercomeSignatureClash;
  }

  @Override
  @NotNull
  public List<TypeParameterDescriptor> getTypeParameters() {
    List<TypeParameterDescriptor> parameters = typeParameters;
    // Diagnostics for EA-141456
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
  public List<ValueParameterDescriptor> getValueParameters() {
    return unsubstitutedValueParameters;
  }

  @Override
  public boolean hasStableParameterNames() {
    return hasStableParameterNames;
  }

  @Override
  public boolean hasSynthesizedParameterNames() {
    return hasSynthesizedParameterNames;
  }

  @Override
  public KotlinType getReturnType() {
    return unsubstitutedReturnType;
  }

  public void setReturnType(@NotNull KotlinType unsubstitutedReturnType) {
    if (this.unsubstitutedReturnType != null) {
      // TODO: uncomment and fix tests
      //throw new IllegalStateException("returnType already set");
    }
    this.unsubstitutedReturnType = unsubstitutedReturnType;
  }

  @NotNull
  @Override
  public FunctionDescriptor getOriginal() {
    return original == this ? this : original.getOriginal();
  }

  @NotNull
  @Override
  public Kind getKind() {
    return kind;
  }

  @Override
  public FunctionDescriptor substitute(@NotNull TypeSubstitutor originalSubstitutor) {
    if (originalSubstitutor.isEmpty()) {
      return this;
    }

    return newCopyBuilder(originalSubstitutor)
      .setOriginal(getOriginal())
      .setPreserveSourceElement()
      .setJustForTypeSubstitution(true)
      .build();
  }

  @Nullable
  private KotlinType getExtensionReceiverParameterType() {
    if (extensionReceiverParameter == null) return null;
    return extensionReceiverParameter.getType();
  }

  @Override
  public boolean isHiddenForResolutionEverywhereBesideSupercalls() {
    return isHiddenForResolutionEverywhereBesideSupercalls;
  }

  private void setHiddenForResolutionEverywhereBesideSupercalls(boolean hiddenForResolutionEverywhereBesideSupercalls) {
    isHiddenForResolutionEverywhereBesideSupercalls = hiddenForResolutionEverywhereBesideSupercalls;
  }

  @Override
  @NotNull
  public CopyBuilder<? extends FunctionDescriptor> newCopyBuilder() {
    return newCopyBuilder(TypeSubstitutor.EMPTY);
  }

  @NotNull
  protected CopyConfiguration newCopyBuilder(@NotNull TypeSubstitutor substitutor) {
    return new CopyConfiguration(
      substitutor.getSubstitution(),
      getContainingDeclaration(), getModality(), getVisibility(), getKind(), getValueParameters(), getContextReceiverParameters(),
      getExtensionReceiverParameter(), getReturnType(), null);
  }

  @Nullable
  protected FunctionDescriptor doSubstitute(@NotNull CopyConfiguration configuration) {
    boolean[] wereChanges = new boolean[1];
    Annotations resultAnnotations =
      configuration.additionalAnnotations != null
        ? AnnotationsKt.composeAnnotations(getAnnotations(), configuration.additionalAnnotations)
        : getAnnotations();

    FunctionDescriptorImpl substitutedDescriptor = createSubstitutedCopy(
      configuration.newOwner, configuration.original, configuration.kind, configuration.name, resultAnnotations,
      getSourceToUseForCopy(configuration.preserveSourceElement, configuration.original));

    List<TypeParameterDescriptor> unsubstitutedTypeParameters =
      configuration.newTypeParameters == null ? getTypeParameters() : configuration.newTypeParameters;

    wereChanges[0] |= !unsubstitutedTypeParameters.isEmpty();

    List<TypeParameterDescriptor> substitutedTypeParameters =
      new ArrayList<TypeParameterDescriptor>(unsubstitutedTypeParameters.size());
    final TypeSubstitutor substitutor = DescriptorSubstitutor.substituteTypeParameters(
      unsubstitutedTypeParameters, configuration.substitution, substitutedDescriptor, substitutedTypeParameters, wereChanges
    );
    if (substitutor == null) return null;

    List<ReceiverParameterDescriptor> substitutedContextReceiverParameters = new ArrayList<ReceiverParameterDescriptor>();
    if (!configuration.newContextReceiverParameters.isEmpty()) {
      int index = 0;
      for (ReceiverParameterDescriptor newContextReceiverParameter : configuration.newContextReceiverParameters) {
        KotlinType substitutedContextReceiverType =
          substitutor.substitute(newContextReceiverParameter.getType(), Variance.IN_VARIANCE);
        if (substitutedContextReceiverType == null) {
          return null;
        }
        ReceiverParameterDescriptor substitutedContextReceiverParameter =
          DescriptorFactory.createContextReceiverParameterForCallable(substitutedDescriptor, substitutedContextReceiverType,
            ((ImplicitContextReceiver) newContextReceiverParameter.getValue()).getCustomLabelName(),
            newContextReceiverParameter.getAnnotations(),
            index++);
        substitutedContextReceiverParameters.add(substitutedContextReceiverParameter);

        wereChanges[0] |= substitutedContextReceiverType != newContextReceiverParameter.getType();
      }
    }

    ReceiverParameterDescriptor substitutedReceiverParameter = null;
    if (configuration.newExtensionReceiverParameter != null) {
      KotlinType substitutedExtensionReceiverType =
        substitutor.substitute(configuration.newExtensionReceiverParameter.getType(), Variance.IN_VARIANCE);
      if (substitutedExtensionReceiverType == null) {
        return null;
      }
      substitutedReceiverParameter = new ReceiverParameterDescriptorImpl(
        substitutedDescriptor,
        new ExtensionReceiver(
          substitutedDescriptor, substitutedExtensionReceiverType, configuration.newExtensionReceiverParameter.getValue()
        ),
        configuration.newExtensionReceiverParameter.getAnnotations()
      );

      wereChanges[0] |= substitutedExtensionReceiverType != configuration.newExtensionReceiverParameter.getType();
    }

    ReceiverParameterDescriptor substitutedExpectedThis = null;
    if (configuration.dispatchReceiverParameter != null) {
      // When generating fake-overridden member it's dispatch receiver parameter has type of Base, and it's correct.
      // E.g.
      // class Base { fun foo() }
      // class Derived : Base
      // val x: Base
      // if (x is Derived) {
      //    // `x` shouldn't be marked as smart-cast
      //    // but it would if fake-overridden `foo` had `Derived` as it's dispatch receiver parameter type
      //    x.foo()
      // }
      substitutedExpectedThis = configuration.dispatchReceiverParameter.substitute(substitutor);
      if (substitutedExpectedThis == null) {
        return null;
      }

      wereChanges[0] |= substitutedExpectedThis != configuration.dispatchReceiverParameter;
    }

    List<ValueParameterDescriptor> substitutedValueParameters = getSubstitutedValueParameters(
      substitutedDescriptor, configuration.newValueParameterDescriptors, substitutor, configuration.dropOriginalInContainingParts,
      configuration.preserveSourceElement, wereChanges
    );
    if (substitutedValueParameters == null) {
      return null;
    }

    KotlinType substitutedReturnType = substitutor.substitute(configuration.newReturnType, Variance.OUT_VARIANCE);
    if (substitutedReturnType == null) {
      return null;
    }

    wereChanges[0] |= substitutedReturnType != configuration.newReturnType;

    if (!wereChanges[0] && configuration.justForTypeSubstitution) {
      return this;
    }

    substitutedDescriptor.initialize(
      substitutedReceiverParameter, substitutedExpectedThis, substitutedContextReceiverParameters,
      substitutedTypeParameters,
      substitutedValueParameters,
      substitutedReturnType,
      configuration.newModality,
      configuration.newVisibility
    );
    substitutedDescriptor.setOperator(isOperator);
    substitutedDescriptor.setInfix(isInfix);
    substitutedDescriptor.setExternal(isExternal);
    substitutedDescriptor.setInline(isInline);
    substitutedDescriptor.setTailrec(isTailrec);
    substitutedDescriptor.setSuspend(isSuspend);
    substitutedDescriptor.setExpect(isExpect);
    substitutedDescriptor.setActual(isActual);
    substitutedDescriptor.setHasStableParameterNames(hasStableParameterNames);
    substitutedDescriptor.setHiddenToOvercomeSignatureClash(configuration.isHiddenToOvercomeSignatureClash);
    substitutedDescriptor.setHiddenForResolutionEverywhereBesideSupercalls(configuration.isHiddenForResolutionEverywhereBesideSupercalls);

    substitutedDescriptor.setHasSynthesizedParameterNames(
      configuration.newHasSynthesizedParameterNames != null ? configuration.newHasSynthesizedParameterNames : hasSynthesizedParameterNames
    );

    if (!configuration.userDataMap.isEmpty() || userDataMap != null) {
      Map<UserDataKey<?>, Object> newMap = configuration.userDataMap;

      if (userDataMap != null) {
        for (Map.Entry<UserDataKey<?>, Object> entry : userDataMap.entrySet()) {
          if (!newMap.containsKey(entry.getKey())) {
            newMap.put(entry.getKey(), entry.getValue());
          }
        }
      }

      if (newMap.size() == 1) {
        substitutedDescriptor.userDataMap =
          Collections.<UserDataKey<?>, Object>singletonMap(
            newMap.keySet().iterator().next(), newMap.values().iterator().next());
      } else {
        substitutedDescriptor.userDataMap = newMap;
      }
    }

    if (configuration.signatureChange || getInitialSignatureDescriptor() != null) {
      FunctionDescriptor initialSignature = (getInitialSignatureDescriptor() != null ? getInitialSignatureDescriptor() : this);
      FunctionDescriptor initialSignatureSubstituted = initialSignature.substitute(substitutor);
      substitutedDescriptor.setInitialSignatureDescriptor(initialSignatureSubstituted);
    }

    if (configuration.copyOverrides && !getOriginal().getOverriddenDescriptors().isEmpty()) {
      if (configuration.substitution.isEmpty()) {
        Function0<Collection<FunctionDescriptor>> overriddenFunctionsTask = lazyOverriddenFunctionsTask;
        if (overriddenFunctionsTask != null) {
          substitutedDescriptor.lazyOverriddenFunctionsTask = overriddenFunctionsTask;
        } else {
          substitutedDescriptor.setOverriddenDescriptors(getOverriddenDescriptors());
        }
      } else {
        substitutedDescriptor.lazyOverriddenFunctionsTask = new Function0<Collection<FunctionDescriptor>>() {
          @Override
          public Collection<FunctionDescriptor> invoke() {
            Collection<FunctionDescriptor> result = new SmartList<FunctionDescriptor>();
            for (FunctionDescriptor overriddenFunction : getOverriddenDescriptors()) {
              result.add(overriddenFunction.substitute(substitutor));
            }
            return result;
          }
        };
      }
    }

    return substitutedDescriptor;
  }

  @NotNull
  @Override
  public FunctionDescriptor copy(
    DeclarationDescriptor newOwner,
    Modality modality,
    DescriptorVisibility visibility,
    Kind kind,
    boolean copyOverrides
  ) {
    return newCopyBuilder()
      .setOwner(newOwner)
      .setModality(modality)
      .setVisibility(visibility)
      .setKind(kind)
      .setCopyOverrides(copyOverrides)
      .build();
  }

  @NotNull
  protected abstract FunctionDescriptorImpl createSubstitutedCopy(
    @NotNull DeclarationDescriptor newOwner,
    @Nullable FunctionDescriptor original,
    @NotNull Kind kind,
    @Nullable Name newName,
    @NotNull Annotations annotations,
    @NotNull SourceElement source
  );

  @NotNull
  private SourceElement getSourceToUseForCopy(boolean preserveSource, @Nullable FunctionDescriptor original) {
    return preserveSource
      ? (original != null ? original : getOriginal()).getSource()
      : SourceElement.NO_SOURCE;
  }

  @Override
  public <R, D> R accept(DeclarationDescriptorVisitor<R, D> visitor, D data) {
    return visitor.visitFunctionDescriptor(this, data);
  }

  @Override
  @Nullable
  public FunctionDescriptor getInitialSignatureDescriptor() {
    return initialSignatureDescriptor;
  }

  private void setInitialSignatureDescriptor(@Nullable FunctionDescriptor initialSignatureDescriptor) {
    this.initialSignatureDescriptor = initialSignatureDescriptor;
  }

  // Don't use on published descriptors
  public <V> void putInUserDataMap(UserDataKey<V> key, Object value) {
    if (userDataMap == null) {
      userDataMap = new LinkedHashMap<UserDataKey<?>, Object>();
    }
    userDataMap.put(key, value);
  }

  public class CopyConfiguration implements SimpleFunctionDescriptor.CopyBuilder<FunctionDescriptor> {
    protected @NotNull TypeSubstitution substitution;
    protected @NotNull DeclarationDescriptor newOwner;
    protected @NotNull Modality newModality;
    protected @NotNull
    DescriptorVisibility newVisibility;
    protected @Nullable FunctionDescriptor original = null;
    protected @NotNull Kind kind;
    protected @NotNull List<ValueParameterDescriptor> newValueParameterDescriptors;
    protected @NotNull List<ReceiverParameterDescriptor> newContextReceiverParameters;
    protected @Nullable ReceiverParameterDescriptor newExtensionReceiverParameter;
    protected @Nullable ReceiverParameterDescriptor dispatchReceiverParameter = FunctionDescriptorImpl.this.dispatchReceiverParameter;
    protected @NotNull KotlinType newReturnType;
    protected @Nullable Name name;
    protected boolean copyOverrides = true;
    protected boolean signatureChange = false;
    protected boolean preserveSourceElement = false;
    protected boolean dropOriginalInContainingParts = false;
    protected boolean justForTypeSubstitution = false;
    private boolean isHiddenToOvercomeSignatureClash = isHiddenToOvercomeSignatureClash();
    private List<TypeParameterDescriptor> newTypeParameters = null;
    private Annotations additionalAnnotations = null;
    private boolean isHiddenForResolutionEverywhereBesideSupercalls = isHiddenForResolutionEverywhereBesideSupercalls();
    private Map<UserDataKey<?>, Object> userDataMap = new LinkedHashMap<UserDataKey<?>, Object>();
    private Boolean newHasSynthesizedParameterNames = null;

    public CopyConfiguration(
      @NotNull TypeSubstitution substitution,
      @NotNull DeclarationDescriptor newOwner,
      @NotNull Modality newModality,
      @NotNull DescriptorVisibility newVisibility,
      @NotNull Kind kind,
      @NotNull List<ValueParameterDescriptor> newValueParameterDescriptors,
      @NotNull List<ReceiverParameterDescriptor> newContextReceiverParameters,
      @Nullable ReceiverParameterDescriptor newExtensionReceiverParameter,
      @NotNull KotlinType newReturnType,
      @Nullable Name name
    ) {
      this.substitution = substitution;
      this.newOwner = newOwner;
      this.newModality = newModality;
      this.newVisibility = newVisibility;
      this.kind = kind;
      this.newValueParameterDescriptors = newValueParameterDescriptors;
      this.newContextReceiverParameters = newContextReceiverParameters;
      this.newExtensionReceiverParameter = newExtensionReceiverParameter;
      this.newReturnType = newReturnType;
      this.name = name;
    }

    @Override
    @NotNull
    public CopyConfiguration setOwner(@NotNull DeclarationDescriptor owner) {
      this.newOwner = owner;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setModality(@NotNull Modality modality) {
      this.newModality = modality;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setVisibility(@NotNull DescriptorVisibility visibility) {
      this.newVisibility = visibility;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setKind(@NotNull Kind kind) {
      this.kind = kind;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setCopyOverrides(boolean copyOverrides) {
      this.copyOverrides = copyOverrides;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setName(@NotNull Name name) {
      this.name = name;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setValueParameters(@NotNull List<ValueParameterDescriptor> parameters) {
      this.newValueParameterDescriptors = parameters;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setTypeParameters(@NotNull List<TypeParameterDescriptor> parameters) {
      this.newTypeParameters = parameters;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setReturnType(@NotNull KotlinType type) {
      this.newReturnType = type;
      return this;
    }

    @NotNull
    @Override
    public CopyBuilder<FunctionDescriptor> setContextReceiverParameters(@NotNull List<ReceiverParameterDescriptor> contextReceiverParameters) {
      this.newContextReceiverParameters = contextReceiverParameters;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setExtensionReceiverParameter(@Nullable ReceiverParameterDescriptor extensionReceiverParameter) {
      this.newExtensionReceiverParameter = extensionReceiverParameter;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setDispatchReceiverParameter(@Nullable ReceiverParameterDescriptor dispatchReceiverParameter) {
      this.dispatchReceiverParameter = dispatchReceiverParameter;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setSignatureChange() {
      this.signatureChange = true;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setPreserveSourceElement() {
      this.preserveSourceElement = true;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setDropOriginalInContainingParts() {
      this.dropOriginalInContainingParts = true;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setHiddenToOvercomeSignatureClash() {
      isHiddenToOvercomeSignatureClash = true;
      return this;
    }

    @Override
    @NotNull
    public CopyConfiguration setHiddenForResolutionEverywhereBesideSupercalls() {
      isHiddenForResolutionEverywhereBesideSupercalls = true;
      return this;
    }

    @NotNull
    @Override
    public CopyConfiguration setAdditionalAnnotations(@NotNull Annotations additionalAnnotations) {
      this.additionalAnnotations = additionalAnnotations;
      return this;
    }

    public CopyConfiguration setHasSynthesizedParameterNames(boolean value) {
      this.newHasSynthesizedParameterNames = value;
      return this;
    }

    @NotNull
    @Override
    public <V> CopyBuilder<FunctionDescriptor> putUserData(@NotNull UserDataKey<V> userDataKey, V value) {
      userDataMap.put(userDataKey, value);
      return this;
    }

    @Override
    @Nullable
    public FunctionDescriptor build() {
      return doSubstitute(this);
    }

    @Nullable
    public FunctionDescriptor getOriginal() {
      return original;
    }

    @Override
    @NotNull
    public CopyConfiguration setOriginal(@Nullable CallableMemberDescriptor original) {
      this.original = (FunctionDescriptor) original;
      return this;
    }

    @NotNull
    public TypeSubstitution getSubstitution() {
      return substitution;
    }

    @NotNull
    @Override
    public CopyConfiguration setSubstitution(@NotNull TypeSubstitution substitution) {
      this.substitution = substitution;
      return this;
    }

    @NotNull
    public CopyConfiguration setJustForTypeSubstitution(boolean value) {
      justForTypeSubstitution = value;
      return this;
    }
  }
}
