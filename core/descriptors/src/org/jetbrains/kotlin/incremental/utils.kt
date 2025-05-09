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

package org.jetbrains.kotlin.incremental

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils

// These methods are called many times, please pay attention to performance here

fun LookupTracker.record(from: LookupLocation, scopeOwner: ClassDescriptor, name: Name) {
  if (this === LookupTracker.DO_NOTHING) return
  val location = from.location ?: return
  val position = if (requiresPosition) location.position else Position.NO_POSITION
  record(location.filePath, position, DescriptorUtils.getFqName(scopeOwner).asString(), ScopeKind.CLASSIFIER, name.asString())
}

fun LookupTracker.record(from: LookupLocation, scopeOwner: PackageFragmentDescriptor, name: Name) {
  recordPackageLookup(from, scopeOwner.fqName.asString(), name.asString())
}

fun LookupTracker.recordPackageLookup(from: LookupLocation, packageFqName: String, name: String) {
  if (this === LookupTracker.DO_NOTHING) return
  val location = from.location ?: return
  val position = if (requiresPosition) location.position else Position.NO_POSITION
  record(location.filePath, position, packageFqName, ScopeKind.PACKAGE, name)
}

const val ANDROID_LAYOUT_CONTENT_LOOKUP_NAME = "<LAYOUT-CONTENT>"
