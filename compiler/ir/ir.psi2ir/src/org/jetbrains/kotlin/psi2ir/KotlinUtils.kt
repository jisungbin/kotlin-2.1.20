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

package org.jetbrains.kotlin.psi2ir

import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SyntheticPropertyDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtPureElement
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils

internal fun KotlinType.containsNull(): Boolean =
  TypeUtils.isNullableType(this)

fun KtElement.deparenthesize(): KtElement =
  if (this is KtExpression) KtPsiUtil.safeDeparenthesize(this) else this

internal fun ResolvedCall<*>.isValueArgumentReorderingRequired(): Boolean {
  var lastValueParameterIndex = -1
  for (valueArgument in call.valueArguments) {
    val argumentMapping =
      getArgumentMapping(valueArgument) as? ArgumentMatch ?: throw Exception("Value argument in function call is mapped with error")
    val argumentIndex = argumentMapping.valueParameter.index
    if (argumentIndex < lastValueParameterIndex) {
      return true
    }
    lastValueParameterIndex = argumentIndex
  }
  return false
}

internal fun KtSecondaryConstructor.isConstructorDelegatingToSuper(bindingContext: BindingContext): Boolean {
  val constructorDescriptor = bindingContext.get(BindingContext.CONSTRUCTOR, this) ?: return false
  val delegatingResolvedCall = getDelegationCall().getResolvedCall(bindingContext)
  return if (delegatingResolvedCall != null) {
    val ownerClassDescriptor = constructorDescriptor.containingDeclaration
    val targetClassDescriptor = delegatingResolvedCall.resultingDescriptor.containingDeclaration
    targetClassDescriptor != ownerClassDescriptor
  } else {
    constructorDescriptor.constructedClass.kind == ClassKind.ENUM_CLASS
  }
}

fun MemberScope.findSingleFunction(name: Name): FunctionDescriptor =
  getContributedFunctions(name, NoLookupLocation.FROM_BACKEND).single()

internal val PsiElement?.startOffsetOrUndefined get() = this?.startOffsetSkippingComments ?: UNDEFINED_OFFSET
internal val PsiElement?.endOffsetOrUndefined get() = this?.endOffset ?: UNDEFINED_OFFSET

internal val PropertyDescriptor.unwrappedGetMethod: FunctionDescriptor?
  get() = if (this is SyntheticPropertyDescriptor) this.getMethod else getter

internal val PropertyDescriptor.unwrappedSetMethod: FunctionDescriptor?
  get() = if (this is SyntheticPropertyDescriptor) this.setMethod else setter

// Only works for descriptors of Java fields.
internal fun PropertyDescriptor.resolveFakeOverride(): PropertyDescriptor {
  assert(getter == null) { "resolveFakeOverride should only be called for Java fields, got $this" }
  var current = this
  while (current.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
    current = current.overriddenDescriptors.singleOrNull {
      (it.containingDeclaration as ClassDescriptor).kind != ClassKind.INTERFACE
    } ?: current.overriddenDescriptors.firstOrNull()
      ?: error("Fake override descriptor of Java field $current should has no overridden descriptors")
  }
  return current
}

internal val KtPureElement?.pureStartOffsetOrUndefined get() = this?.psiOrParent?.startOffsetSkippingComments ?: UNDEFINED_OFFSET
internal val KtPureElement?.pureEndOffsetOrUndefined get() = this?.psiOrParent?.endOffset ?: UNDEFINED_OFFSET

internal fun KtElement.getChildTokenStartOffsetOrNull(tokenSet: TokenSet) = node.findChildByType(tokenSet)?.startOffset
