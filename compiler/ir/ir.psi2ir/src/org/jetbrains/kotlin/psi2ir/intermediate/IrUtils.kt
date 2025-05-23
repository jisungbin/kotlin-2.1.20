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

package org.jetbrains.kotlin.psi2ir.intermediate

import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.SyntheticPropertyDescriptor
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl

internal fun IrVariable.loadAt(startOffset: Int, endOffset: Int): IrExpression =
  IrGetValueImpl(startOffset, endOffset, type, symbol)

internal fun CallReceiver.adjustForCallee(callee: CallableMemberDescriptor): CallReceiver =
  object : CallReceiver {
    override fun call(builder: CallExpressionBuilder): IrExpression =
      this@adjustForCallee.call { dispatchReceiverValue, extensionReceiverValue, contextReceiverValues ->
        val numReceiversPresent = listOfNotNull(dispatchReceiverValue, extensionReceiverValue).size
        val numReceiversExpected = listOfNotNull(callee.dispatchReceiverParameter, callee.extensionReceiverParameter).size
        if (numReceiversPresent != numReceiversExpected)
          throw AssertionError("Mismatching receivers for $callee: $numReceiversPresent, expected: $numReceiversExpected")

        val newDispatchReceiverValue =
          when {
            callee.dispatchReceiverParameter == null -> null
            dispatchReceiverValue != null -> dispatchReceiverValue
            else -> extensionReceiverValue
          }
        val newExtensionReceiverValue =
          when {
            callee.extensionReceiverParameter == null -> null
            dispatchReceiverValue != null && callee.dispatchReceiverParameter == null -> dispatchReceiverValue
            else -> extensionReceiverValue
          }
        builder.withReceivers(newDispatchReceiverValue, newExtensionReceiverValue, contextReceiverValues)
      }
  }


internal fun computeSubstitutedSyntheticAccessor(
  propertyDescriptor: PropertyDescriptor,
  accessorFunctionDescriptor: FunctionDescriptor,
  substitutedExtensionAccessorDescriptor: PropertyAccessorDescriptor,
): FunctionDescriptor {
  if (propertyDescriptor.original == propertyDescriptor) return accessorFunctionDescriptor

  // Compute substituted accessor descriptor in case of Synthetic Java property `Java: getFoo() -> Kotlin: foo`
  if (propertyDescriptor !is SyntheticPropertyDescriptor) return accessorFunctionDescriptor

  if (propertyDescriptor.extensionReceiverParameter == null || propertyDescriptor.dispatchReceiverParameter != null) {
    return accessorFunctionDescriptor
  }

  if (accessorFunctionDescriptor !is SimpleFunctionDescriptor) return accessorFunctionDescriptor

  return copyTypesFromExtensionAccessor(accessorFunctionDescriptor, substitutedExtensionAccessorDescriptor)
}

// When computing substituted descriptor for a synthetic accessor of a synthesized property,
// the property descriptor's accessor field almost has the right type,
// except with extension receiver instead of dispatch receiver. Need to patch it up.
private fun copyTypesFromExtensionAccessor(
  accessorFunctionDescriptor: SimpleFunctionDescriptor,
  extensionAccessorDescriptor: PropertyAccessorDescriptor,
): FunctionDescriptor =
  SimpleFunctionDescriptorImpl.create(
    accessorFunctionDescriptor.containingDeclaration,
    accessorFunctionDescriptor.annotations,
    accessorFunctionDescriptor.name,
    accessorFunctionDescriptor.kind,
    accessorFunctionDescriptor.source
  ).apply {
    initialize(
      null,
      extensionAccessorDescriptor.extensionReceiverParameter?.copy(this),
      emptyList(),
      emptyList(),
      extensionAccessorDescriptor.valueParameters.map { it.copy(this, it.name, it.index) },
      extensionAccessorDescriptor.returnType,
      accessorFunctionDescriptor.modality,
      accessorFunctionDescriptor.visibility
    )
  }
