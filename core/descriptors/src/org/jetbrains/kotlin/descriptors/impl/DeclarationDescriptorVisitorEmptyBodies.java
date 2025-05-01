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

package org.jetbrains.kotlin.descriptors.impl;

import org.jetbrains.kotlin.descriptors.ClassDescriptor;
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorVisitor;
import org.jetbrains.kotlin.descriptors.FunctionDescriptor;
import org.jetbrains.kotlin.descriptors.ModuleDescriptor;
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor;
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyGetterDescriptor;
import org.jetbrains.kotlin.descriptors.PropertySetterDescriptor;
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ScriptDescriptor;
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor;
import org.jetbrains.kotlin.descriptors.VariableDescriptor;

public class DeclarationDescriptorVisitorEmptyBodies<R, D> implements DeclarationDescriptorVisitor<R, D> {
  public R visitDeclarationDescriptor(DeclarationDescriptor descriptor, D data) {
    return null;
  }

  @Override
  public R visitVariableDescriptor(VariableDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitFunctionDescriptor(FunctionDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitTypeParameterDescriptor(TypeParameterDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitPackageFragmentDescriptor(PackageFragmentDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitPackageViewDescriptor(PackageViewDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitClassDescriptor(ClassDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitTypeAliasDescriptor(TypeAliasDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitModuleDeclaration(ModuleDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }

  @Override
  public R visitConstructorDescriptor(ConstructorDescriptor constructorDescriptor, D data) {
    return visitFunctionDescriptor(constructorDescriptor, data);
  }

  @Override
  public R visitScriptDescriptor(ScriptDescriptor scriptDescriptor, D data) {
    return visitClassDescriptor(scriptDescriptor, data);
  }

  @Override
  public R visitPropertyDescriptor(PropertyDescriptor descriptor, D data) {
    return visitVariableDescriptor(descriptor, data);
  }

  @Override
  public R visitValueParameterDescriptor(ValueParameterDescriptor descriptor, D data) {
    return visitVariableDescriptor(descriptor, data);
  }

  @Override
  public R visitPropertyGetterDescriptor(PropertyGetterDescriptor descriptor, D data) {
    return visitFunctionDescriptor(descriptor, data);
  }

  @Override
  public R visitPropertySetterDescriptor(PropertySetterDescriptor descriptor, D data) {
    return visitFunctionDescriptor(descriptor, data);
  }

  @Override
  public R visitReceiverParameterDescriptor(ReceiverParameterDescriptor descriptor, D data) {
    return visitDeclarationDescriptor(descriptor, data);
  }
}
