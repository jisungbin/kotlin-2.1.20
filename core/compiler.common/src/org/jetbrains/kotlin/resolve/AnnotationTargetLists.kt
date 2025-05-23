/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("FunctionName")

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.ANONYMOUS_FUNCTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.BACKING_FIELD
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.CLASS
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.CONSTRUCTOR
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.DESTRUCTURING_DECLARATION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.EXPRESSION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.FIELD
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.FILE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.FUNCTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.INITIALIZER
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.LAMBDA_EXPRESSION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.LOCAL_FUNCTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.LOCAL_VARIABLE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.MEMBER_FUNCTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.MEMBER_PROPERTY
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.MEMBER_PROPERTY_WITHOUT_FIELD_OR_DELEGATE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.MEMBER_PROPERTY_WITH_BACKING_FIELD
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.MEMBER_PROPERTY_WITH_DELEGATE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.OBJECT_LITERAL
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.PROPERTY
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.PROPERTY_GETTER
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.PROPERTY_SETTER
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.STAR_PROJECTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TOP_LEVEL_FUNCTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TOP_LEVEL_PROPERTY
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TOP_LEVEL_PROPERTY_WITHOUT_FIELD_OR_DELEGATE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TOP_LEVEL_PROPERTY_WITH_BACKING_FIELD
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TOP_LEVEL_PROPERTY_WITH_DELEGATE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TYPE
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TYPEALIAS
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TYPE_PARAMETER
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.TYPE_PROJECTION
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.VALUE_PARAMETER

object AnnotationTargetLists {
  val T_CLASSIFIER = targetList(CLASS)
  val T_TYPEALIAS = targetList(TYPEALIAS)

  val T_LOCAL_VARIABLE = targetList(LOCAL_VARIABLE) {
    onlyWithUseSiteTarget(PROPERTY_SETTER, VALUE_PARAMETER)
  }

  val T_CATCH_PARAMETER = targetList(LOCAL_VARIABLE, VALUE_PARAMETER)

  val T_DESTRUCTURING_DECLARATION = targetList(DESTRUCTURING_DECLARATION)

  private fun TargetListBuilder.propertyTargets(backingField: Boolean, delegate: Boolean) {
    if (backingField) extraTargets(FIELD)
    if (delegate) {
      onlyWithUseSiteTarget(VALUE_PARAMETER, PROPERTY_GETTER, PROPERTY_SETTER, FIELD)
    } else {
      onlyWithUseSiteTarget(VALUE_PARAMETER, PROPERTY_GETTER, PROPERTY_SETTER)
    }
  }

  fun T_MEMBER_PROPERTY(backingField: Boolean, delegate: Boolean) =
    targetList(
      when {
        backingField -> MEMBER_PROPERTY_WITH_BACKING_FIELD
        delegate -> MEMBER_PROPERTY_WITH_DELEGATE
        else -> MEMBER_PROPERTY_WITHOUT_FIELD_OR_DELEGATE
      }, MEMBER_PROPERTY, PROPERTY
    ) {
      propertyTargets(backingField, delegate)
    }

  @AnnotationTargetListForDeprecation
  val T_MEMBER_PROPERTY_IN_ANNOTATION = T_MEMBER_PROPERTY(backingField = false, delegate = false)

  fun T_TOP_LEVEL_PROPERTY(backingField: Boolean, delegate: Boolean) =
    targetList(
      when {
        backingField -> TOP_LEVEL_PROPERTY_WITH_BACKING_FIELD
        delegate -> TOP_LEVEL_PROPERTY_WITH_DELEGATE
        else -> TOP_LEVEL_PROPERTY_WITHOUT_FIELD_OR_DELEGATE
      }, TOP_LEVEL_PROPERTY, PROPERTY
    ) {
      propertyTargets(backingField, delegate)
    }

  val T_PROPERTY_GETTER = targetList(PROPERTY_GETTER)
  val T_PROPERTY_SETTER = targetList(PROPERTY_SETTER)
  val T_BACKING_FIELD = targetList(BACKING_FIELD) {
    extraTargets(FIELD)
  }

  val T_VALUE_PARAMETER_WITHOUT_VAL = targetList(VALUE_PARAMETER)

  val T_VALUE_PARAMETER_WITH_VAL = targetList(VALUE_PARAMETER, PROPERTY, MEMBER_PROPERTY) {
    extraTargets(FIELD)
    onlyWithUseSiteTarget(PROPERTY_GETTER, PROPERTY_SETTER)
  }

  val T_FILE = targetList(FILE)

  val T_CONSTRUCTOR = targetList(CONSTRUCTOR)

  val T_LOCAL_FUNCTION = targetList(LOCAL_FUNCTION, FUNCTION) {
    onlyWithUseSiteTarget(VALUE_PARAMETER)
  }

  val T_MEMBER_FUNCTION = targetList(MEMBER_FUNCTION, FUNCTION) {
    onlyWithUseSiteTarget(VALUE_PARAMETER)
  }

  val T_TOP_LEVEL_FUNCTION = targetList(TOP_LEVEL_FUNCTION, FUNCTION) {
    onlyWithUseSiteTarget(VALUE_PARAMETER)
  }

  val T_EXPRESSION = targetList(EXPRESSION)

  val T_FUNCTION_LITERAL = targetList(LAMBDA_EXPRESSION, FUNCTION, EXPRESSION)

  val T_FUNCTION_EXPRESSION = targetList(ANONYMOUS_FUNCTION, FUNCTION, EXPRESSION)

  val T_OBJECT_LITERAL = targetList(OBJECT_LITERAL, CLASS, EXPRESSION)

  val T_TYPE_REFERENCE = targetList(TYPE) {
    onlyWithUseSiteTarget(VALUE_PARAMETER)
  }

  val T_TYPE_PARAMETER = targetList(TYPE_PARAMETER)

  val T_STAR_PROJECTION = targetList(STAR_PROJECTION)
  val T_TYPE_PROJECTION = targetList(TYPE_PROJECTION)

  val T_INITIALIZER = targetList(INITIALIZER)


  private fun targetList(vararg target: KotlinTarget, otherTargets: TargetListBuilder.() -> Unit = {}): AnnotationTargetList {
    val builder = TargetListBuilder(*target)
    builder.otherTargets()
    return builder.build()
  }

  val EMPTY = targetList()

  private class TargetListBuilder(vararg val defaultTargets: KotlinTarget) {
    private var canBeSubstituted: List<KotlinTarget> = listOf()
    private var onlyWithUseSiteTarget: List<KotlinTarget> = listOf()

    fun extraTargets(vararg targets: KotlinTarget) {
      canBeSubstituted = targets.toList()
    }

    fun onlyWithUseSiteTarget(vararg targets: KotlinTarget) {
      onlyWithUseSiteTarget = targets.toList()
    }

    fun build() = AnnotationTargetList(defaultTargets.toList(), canBeSubstituted, onlyWithUseSiteTarget)
  }
}

/**
 * This class represents possible target lists for a given annotation.
 *
 * Note: not intended for a structural comparison. Referential comparison can be used in exceptional cases,
 * see [AnnotationTargetLists.T_MEMBER_PROPERTY_IN_ANNOTATION] as an example.
 */
class AnnotationTargetList(
  val defaultTargets: List<KotlinTarget>,
  val canBeSubstituted: List<KotlinTarget> = emptyList(),
  val onlyWithUseSiteTarget: List<KotlinTarget> = emptyList(),
) {
  @Suppress("POTENTIALLY_NON_REPORTED_ANNOTATION")
  @Deprecated(level = DeprecationLevel.ERROR, message = "These lists are not intended to compare them")
  override fun equals(other: Any?): Boolean {
    return super.equals(other)
  }

  override fun hashCode(): Int {
    return super.hashCode()
  }
}

object UseSiteTargetsList {
  val T_CONSTRUCTOR_PARAMETER = listOf(
    AnnotationUseSiteTarget.CONSTRUCTOR_PARAMETER,
    AnnotationUseSiteTarget.PROPERTY,
    AnnotationUseSiteTarget.FIELD
  )

  val T_PROPERTY = listOf(
    AnnotationUseSiteTarget.PROPERTY,
    AnnotationUseSiteTarget.FIELD
  )
}

@RequiresOptIn(message = "This target list is for deprecation reporting only and not intended to be widely used.")
annotation class AnnotationTargetListForDeprecation
