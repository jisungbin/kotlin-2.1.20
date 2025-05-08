/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.compiler.plugins.kotlin.inference

/**
 * Call bindings are the binding variables for either a function being inferred or a target of a
 * call. If a call and the function's call bindings can be unified the call is valid and the
 * variables bound by the unification contribute to the environment for the any subsequent bindings.
 *
 * [CallBindings]은 추론되는 함수 또는 호출(call) 대상에 대한 [Binding] 변수입니다. 호출(call)과 함수의
 * 호출(call) 바인딩이 통합될 수 있는 경우 호출은 유효하며 통합에 의해 바인딩된 변수는 후속 바인딩의
 * 환경에 기여합니다.
 *
 * @param target the binding instance for the call target.
 * @param parameters the call bindings the lambda parameters (if any).
 */
class CallBindings(
  val target: Binding,
  val parameters: List<CallBindings> = emptyList(),
  val result: CallBindings?,
  val anyParameters: Boolean,
) {
  override fun toString(): String {
    val paramsString = if (parameters.isEmpty()) "" else ", ${parameters.joinToString(", ") { it.toString() }}"
    val anyParametersStr = if (anyParameters) "*" else ""
    val resultString = result?.let { "-> $it" }.orEmpty()
    return "[$target$anyParametersStr$paramsString$resultString]"
  }
}
