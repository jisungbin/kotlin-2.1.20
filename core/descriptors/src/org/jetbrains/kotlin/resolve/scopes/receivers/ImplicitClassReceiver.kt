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

package org.jetbrains.kotlin.resolve.scopes.receivers

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.types.KotlinType

/**
 * Describes any "this" receiver inside a class
 */
interface ThisClassReceiver : ReceiverValue {
  val classDescriptor: ClassDescriptor
}

/**
 * Same but implicit only
 */
open class ImplicitClassReceiver(
  final override val classDescriptor: ClassDescriptor,
  original: ImplicitClassReceiver? = null,
) : ThisClassReceiver, ImplicitReceiver {

  private val original = original ?: this

  override fun getType() = classDescriptor.defaultType

  override val declarationDescriptor = classDescriptor

  override fun equals(other: Any?) = classDescriptor == (other as? ImplicitClassReceiver)?.classDescriptor

  override fun hashCode() = classDescriptor.hashCode()

  override fun toString() = "Class{$type}"

  override fun replaceType(newType: KotlinType) =
    throw UnsupportedOperationException("Replace type should not be called for this receiver")

  override fun getOriginal() = original
}
