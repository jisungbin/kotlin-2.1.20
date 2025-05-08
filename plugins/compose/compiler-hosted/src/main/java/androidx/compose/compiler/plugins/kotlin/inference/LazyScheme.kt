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

// 이 파일 코드를 읽기 전에 `@ComposableOpenTarget` 어노테이션 맥락을 충분히 이해해야 함

/**
 * A [LazyScheme] is a scheme that is lazily and progressively inferred by [ApplierInferencer] and is
 * used to store the current inference state for a function type. [Scheme] is used to control the
 * shape and initial values of the lazy scheme. Bound appliers are not changed but open types are
 * inferred or bound together, if possible, by [ApplierInferencer].
 *
 * [LazyScheme]는 [ApplierInferencer]에서 lazy하고 점진적으로 추론하는 스키마로, 함수 타입에 대한
 * 현재 추론 상태를 저장하는 데 사용됩니다. [Scheme]은 [LazyScheme]의 모양과 초기 값을 제어하는 데
 * 사용됩니다. 바인딩된 Applier는 변경되지 않지만, 가능한 경우 [ApplierInferencer]에 의해 Open Applier가
 * 추론되거나 특정 Applier가 함께 바인딩됩니다.
 */
// STUDY ComposableOpenTarget.index를 동적으로 계산해 주는 역할로 보인다?
class LazyScheme(
  scheme: Scheme,
  context: MutableList<Binding> = mutableListOf(),
  val bindings: Bindings = Bindings(),
) {
  val target: Binding = scheme.target.toBinding(bindings = bindings, context = context)
  val anyParameters = scheme.anyParameters
  val parameters = scheme.parameters.map { LazyScheme(scheme = it, context = context, bindings = bindings) }
  val result = scheme.result?.let { LazyScheme(scheme = it, context = context, bindings = bindings) }

  val closed: Boolean
    get() = target.token != null && (result == null || result.closed) && parameters.all { it.closed }

  /**
   * Create a call binding for use when validating a call to the function this lazy scheme is for.
   *
   * 이 [LazyScheme]의 대상 함수에 대한 호출의 유효성을 검사할 때 사용할 호출 바인딩을 만듭니다.
   */
  fun toCallBindings(): CallBindings =
    CallBindings(
      target = target,
      parameters = parameters.map { it.toCallBindings() },
      result = result?.toCallBindings(),
      anyParameters = anyParameters,
    )

  /**
   * Create a [Scheme] from the current state of this.
   */
  fun toScheme(): Scheme {
    // Open Applier의 index 값 (자동 추론됨)
    var uniqueNumber = 0

    // Value를 ComposableOpenTarget.index로 매핑할 때 사용할 index 맵
    val context = mutableMapOf<Value, Int>()

    fun mapValues(scheme: LazyScheme) {
      val target: Binding = scheme.target
      if (target.token == null) {
        val value: Value = target.value
        val index: Int? = context[value]
        if (index == -1) {
          context[value] = uniqueNumber++
        } else if (index == null) {
          context[value] = -1
        }
      }
      scheme.parameters.forEach { mapValues(it) }
      scheme.result?.let { mapValues(it) }
    }

    fun itemOf(binding: Binding): Item =
      binding.token?.let(::Token) ?: context[binding.value]?.let(::Open) ?: Open(-1)

    fun schemeOf(lazyScheme: LazyScheme): Scheme =
      Scheme(
        target = itemOf(binding = lazyScheme.target),
        parameters = lazyScheme.parameters.map { schemeOf(it) },
        result = lazyScheme.result?.let { schemeOf(it) },
        anyParameters = lazyScheme.anyParameters,
      )

    mapValues(this)
    return schemeOf(this)
  }

  /**
   * Call [callback] whenever the lazy changes.
   *
   * @return A function that can be called to stop observing changes.
   */
  fun onChange(callback: () -> Unit): () -> Unit {
    var previousScheme = toScheme()
    return bindings.onChange {
      val newScheme = toScheme()
      if (newScheme != previousScheme) {
        callback()
        previousScheme = newScheme
      }
    }
  }

  private val targetString: String
    get() = target.token ?: target.value.index.toString()

  private val parametersString: String
    get() = if (parameters.isNotEmpty()) ", ${parameters.joinToString(", ")}" else ""

  private val resultString: String
    get() = result?.let { ":$it" }.orEmpty()

  override fun toString(): String =
    "[$targetString$parametersString$resultString]"

  companion object {
    fun open(): LazyScheme =
      Open(-1).let { target ->
        LazyScheme(scheme = Scheme(target = target, result = Scheme(target)))
      }
  }
}
