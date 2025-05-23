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

package org.jetbrains.kotlin.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.CallableDescriptor;
import org.jetbrains.kotlin.descriptors.ClassDescriptor;

public interface ExternalOverridabilityCondition {
  @NotNull
  Result isOverridable(
    @NotNull CallableDescriptor superDescriptor,
    @NotNull CallableDescriptor subDescriptor,
    @Nullable ClassDescriptor subClassDescriptor
  );

  @NotNull
  Contract getContract();

  enum Result {
    OVERRIDABLE, INCOMPATIBLE, UNKNOWN
  }

  enum Contract {
    CONFLICTS_ONLY, SUCCESS_ONLY, BOTH
  }
}
