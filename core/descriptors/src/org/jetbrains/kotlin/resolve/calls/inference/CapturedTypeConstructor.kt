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

package org.jetbrains.kotlin.resolve.calls.inference

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.DelegatedTypeSubstitution
import org.jetbrains.kotlin.types.IndexedParametersSubstitution
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.LazyWrappedType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.SubtypingRepresentatives
import org.jetbrains.kotlin.types.TypeAttributes
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.TypeRefinement
import org.jetbrains.kotlin.types.TypeSubstitution
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.Variance.IN_VARIANCE
import org.jetbrains.kotlin.types.Variance.OUT_VARIANCE
import org.jetbrains.kotlin.types.checker.KotlinTypeRefiner
import org.jetbrains.kotlin.types.checker.NewCapturedTypeConstructor
import org.jetbrains.kotlin.types.error.ErrorScopeKind
import org.jetbrains.kotlin.types.error.ErrorUtils
import org.jetbrains.kotlin.types.model.CapturedTypeConstructorMarker
import org.jetbrains.kotlin.types.model.CapturedTypeMarker
import org.jetbrains.kotlin.types.typeUtil.builtIns

interface CapturedTypeConstructor : CapturedTypeConstructorMarker, TypeConstructor {
  val projection: TypeProjection
}

class CapturedTypeConstructorImpl(
  override val projection: TypeProjection,
) : CapturedTypeConstructor {
  var newTypeConstructor: NewCapturedTypeConstructor? = null

  init {
    assert(projection.projectionKind != Variance.INVARIANT) {
      "Only nontrivial projections can be captured, not: $projection"
    }
  }

  override fun getParameters(): List<TypeParameterDescriptor> = listOf()

  override fun getSupertypes(): Collection<KotlinType> {
    val superType = if (projection.projectionKind == Variance.OUT_VARIANCE)
      projection.type
    else
      builtIns.nullableAnyType
    return listOf(superType)
  }

  override fun isFinal() = true

  override fun isDenotable() = false

  override fun getDeclarationDescriptor() = null

  override fun toString() = "CapturedTypeConstructor($projection)"

  override fun getBuiltIns(): KotlinBuiltIns = projection.type.constructor.builtIns

  @TypeRefinement
  override fun refine(kotlinTypeRefiner: KotlinTypeRefiner) =
    CapturedTypeConstructorImpl(projection.refine(kotlinTypeRefiner))
}

class CapturedType(
  val typeProjection: TypeProjection,
  override val constructor: CapturedTypeConstructor = CapturedTypeConstructorImpl(typeProjection),
  override val isMarkedNullable: Boolean = false,
  override val attributes: TypeAttributes = TypeAttributes.Empty,
) : SimpleType(), SubtypingRepresentatives, CapturedTypeMarker {

  override val arguments: List<TypeProjection>
    get() = listOf()

  override val memberScope: MemberScope
    get() = ErrorUtils.createErrorScope(
      ErrorScopeKind.CAPTURED_TYPE_SCOPE,
      throwExceptions = true
    )

  override val subTypeRepresentative: KotlinType
    get() = representative(OUT_VARIANCE, builtIns.nullableAnyType)

  override val superTypeRepresentative: KotlinType
    get() = representative(IN_VARIANCE, builtIns.nothingType)

  private fun representative(variance: Variance, default: KotlinType) =
    if (typeProjection.projectionKind == variance) typeProjection.type else default

  override fun sameTypeConstructor(type: KotlinType) = constructor === type.constructor

  override fun toString() = "Captured($typeProjection)" + if (isMarkedNullable) "?" else ""

  override fun makeNullableAsSpecified(newNullability: Boolean): CapturedType {
    if (newNullability == isMarkedNullable) return this
    return CapturedType(typeProjection, constructor, newNullability, attributes)
  }

  override fun replaceAttributes(newAttributes: TypeAttributes): SimpleType =
    CapturedType(typeProjection, constructor, isMarkedNullable, newAttributes)

  @TypeRefinement
  override fun refine(kotlinTypeRefiner: KotlinTypeRefiner) =
    CapturedType(typeProjection.refine(kotlinTypeRefiner), constructor, isMarkedNullable, attributes)
}

fun createCapturedType(typeProjection: TypeProjection): KotlinType = CapturedType(typeProjection)

fun KotlinType.isCaptured(): Boolean = constructor is CapturedTypeConstructor

fun TypeSubstitution.wrapWithCapturingSubstitution(needApproximation: Boolean = true): TypeSubstitution =
  if (this is IndexedParametersSubstitution)
    IndexedParametersSubstitution(
      this.parameters,
      this.arguments.zip(this.parameters).map {
        it.first.createCapturedIfNeeded(it.second)
      }.toTypedArray(),
      approximateContravariantCapturedTypes = needApproximation
    )
  else
    object : DelegatedTypeSubstitution(this@wrapWithCapturingSubstitution) {
      override fun approximateContravariantCapturedTypes() = needApproximation
      override fun get(key: KotlinType) =
        super.get(key)?.createCapturedIfNeeded(key.constructor.declarationDescriptor as? TypeParameterDescriptor)
    }

private fun TypeProjection.createCapturedIfNeeded(typeParameterDescriptor: TypeParameterDescriptor?): TypeProjection {
  if (typeParameterDescriptor == null || projectionKind == Variance.INVARIANT) return this

  // Treat consistent projections as invariant
  if (typeParameterDescriptor.variance == projectionKind) {
    // TODO: Make star projection type lazy
    return if (isStarProjection)
      TypeProjectionImpl(LazyWrappedType(LockBasedStorageManager.NO_LOCKS) {
        this@createCapturedIfNeeded.type
      })
    else
      TypeProjectionImpl(this@createCapturedIfNeeded.type)
  }

  return TypeProjectionImpl(createCapturedType(this))
}
