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

package org.jetbrains.kotlin.descriptors.annotations;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.resolve.constants.AnnotationValue;
import org.jetbrains.kotlin.resolve.constants.ArrayValue;
import org.jetbrains.kotlin.resolve.constants.BooleanValue;
import org.jetbrains.kotlin.resolve.constants.ByteValue;
import org.jetbrains.kotlin.resolve.constants.CharValue;
import org.jetbrains.kotlin.resolve.constants.DoubleValue;
import org.jetbrains.kotlin.resolve.constants.EnumValue;
import org.jetbrains.kotlin.resolve.constants.ErrorValue;
import org.jetbrains.kotlin.resolve.constants.FloatValue;
import org.jetbrains.kotlin.resolve.constants.IntValue;
import org.jetbrains.kotlin.resolve.constants.KClassValue;
import org.jetbrains.kotlin.resolve.constants.LongValue;
import org.jetbrains.kotlin.resolve.constants.NullValue;
import org.jetbrains.kotlin.resolve.constants.ShortValue;
import org.jetbrains.kotlin.resolve.constants.StringValue;
import org.jetbrains.kotlin.resolve.constants.UByteValue;
import org.jetbrains.kotlin.resolve.constants.UIntValue;
import org.jetbrains.kotlin.resolve.constants.ULongValue;
import org.jetbrains.kotlin.resolve.constants.UShortValue;

public interface AnnotationArgumentVisitor<R, D> {
  R visitLongValue(@NotNull LongValue value, D data);

  R visitIntValue(IntValue value, D data);

  R visitErrorValue(ErrorValue value, D data);

  R visitShortValue(ShortValue value, D data);

  R visitByteValue(ByteValue value, D data);

  R visitDoubleValue(DoubleValue value, D data);

  R visitFloatValue(FloatValue value, D data);

  R visitBooleanValue(BooleanValue value, D data);

  R visitCharValue(CharValue value, D data);

  R visitStringValue(StringValue value, D data);

  R visitNullValue(NullValue value, D data);

  R visitEnumValue(EnumValue value, D data);

  R visitArrayValue(ArrayValue value, D data);

  R visitAnnotationValue(AnnotationValue value, D data);

  R visitKClassValue(KClassValue value, D data);

  R visitUByteValue(UByteValue value, D data);

  R visitUShortValue(UShortValue value, D data);

  R visitUIntValue(UIntValue value, D data);

  R visitULongValue(ULongValue value, D data);
}
