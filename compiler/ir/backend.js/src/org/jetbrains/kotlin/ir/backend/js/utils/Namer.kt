/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.utils

import org.jetbrains.kotlin.js.backend.ast.JsNameRef

object Namer {
  val CALL_FUNCTION = "call"
  val BIND_FUNCTION = "bind"

  val SLICE_FUNCTION = "slice"

  val OUTER_NAME = "\$outer"
  val UNREACHABLE_NAME = "\$unreachable"
  val THROWABLE_CONSTRUCTOR = "\$throwableCtor"

  val DELEGATE = "\$delegate"

  val IMPLICIT_RECEIVER_NAME = "this"
  val SYNTHETIC_RECEIVER_NAME = "\$this"
  val ES6_BOX_PARAMETER_NAME = "\$box"

  val ARGUMENTS = JsNameRef("arguments")

  val PROTOTYPE_NAME = "prototype"
  val CONSTRUCTOR_NAME = "constructor"

  val JS_ERROR = JsNameRef("Error")

  val METADATA = "\$metadata\$"

  val KCALLABLE_GET_NAME = "<get-name>"
  val KCALLABLE_NAME = "callableName"
  val KPROPERTY_GET = "get"
  val KPROPERTY_SET = "set"
  val KCALLABLE_CACHE_SUFFIX = "\$cache"
  const val KCALLABLE_ARITY = "\$arity"

  const val SHARED_BOX_V = "_v"
}
