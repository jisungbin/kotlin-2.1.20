/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.descriptors;

import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeSubstitution;

public interface CallableMemberDescriptor extends CallableDescriptor, MemberDescriptor {
  @NotNull
  @Override
  Collection<? extends CallableMemberDescriptor> getOverriddenDescriptors();

  void setOverriddenDescriptors(@NotNull Collection<? extends CallableMemberDescriptor> overriddenDescriptors);

  @NotNull
  @Override
  CallableMemberDescriptor getOriginal();

  /**
   * Is this a real function or function projection.
   */
  @NotNull
  Kind getKind();

  @NotNull
  CallableMemberDescriptor copy(DeclarationDescriptor newOwner, Modality modality, DescriptorVisibility visibility, Kind kind, boolean copyOverrides);

  @NotNull
  CopyBuilder<? extends CallableMemberDescriptor> newCopyBuilder();

  enum Kind {
    DECLARATION,
    FAKE_OVERRIDE,
    DELEGATION,
    SYNTHESIZED;

    public boolean isReal() {
      return this != FAKE_OVERRIDE;
    }
  }

  interface CopyBuilder<D extends CallableMemberDescriptor> {
    @NotNull
    CopyBuilder<D> setOwner(@NotNull DeclarationDescriptor owner);

    @NotNull
    CopyBuilder<D> setModality(@NotNull Modality modality);

    @NotNull
    CopyBuilder<D> setVisibility(@NotNull DescriptorVisibility visibility);

    @NotNull
    CopyBuilder<D> setKind(@NotNull Kind kind);

    @NotNull
    CopyBuilder<D> setTypeParameters(@NotNull List<TypeParameterDescriptor> parameters);

    @NotNull
    CopyBuilder<D> setDispatchReceiverParameter(@Nullable ReceiverParameterDescriptor dispatchReceiverParameter);

    @NotNull
    CopyBuilder<D> setSubstitution(@NotNull TypeSubstitution substitution);

    @NotNull
    CopyBuilder<D> setCopyOverrides(boolean copyOverrides);

    @NotNull
    CopyBuilder<D> setName(@NotNull Name name);

    @NotNull
    CopyBuilder<D> setOriginal(@Nullable CallableMemberDescriptor original);

    @NotNull
    CopyBuilder<D> setPreserveSourceElement();

    @NotNull
    CopyBuilder<D> setReturnType(@NotNull KotlinType type);

    @Nullable
    D build();
  }
}
