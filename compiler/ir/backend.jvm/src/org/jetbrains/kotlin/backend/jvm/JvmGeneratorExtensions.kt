/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.ir.expressions.IrConstructorCall

interface JvmGeneratorExtensions {
  fun generateRawTypeAnnotationCall(): IrConstructorCall?

  val cachedFields: CachedFieldsForObjectInstances
}
