/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.descriptors

import org.jetbrains.kotlin.descriptors.impl.TypeAliasConstructorDescriptor
import org.jetbrains.kotlin.mpp.TypeAliasSymbolMarker
import org.jetbrains.kotlin.types.SimpleType

interface TypeAliasDescriptor : ClassifierDescriptorWithTypeParameters, TypeAliasSymbolMarker {
  /// Right-hand side of the type alias definition.
  /// May contain type aliases.
  val underlyingType: SimpleType

  /// Fully expanded type with non-substituted type parameters.
  /// May not contain type aliases.
  val expandedType: SimpleType

  val classDescriptor: ClassDescriptor?

  override fun getOriginal(): TypeAliasDescriptor

  val constructors: Collection<TypeAliasConstructorDescriptor>
}
