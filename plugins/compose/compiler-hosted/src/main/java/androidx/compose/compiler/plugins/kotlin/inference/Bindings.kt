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

private var valueIndex = 0

/**
 * The value of a binding. If [token] is not null, the binding is bound to [token]. [size] is the
 * number of [Binding] instances that share this value. This is used to optimize unifying open
 * bindings. [index] is not used directly but makes debugging easier.
 *
 * [Binding]의 값입니다. [token]이 null이 아니면 [token]이 [Binding]에 할당된 상태입니다.
 */
class Value(var token: String?, var observers: Set<Bindings>) {
  /** 이 [Value]을 공유하는 [Binding] 인스턴스의 수 */
  var size: Int = 1

  /** 이 [Value]의 인스턴스가 생성된 인덱스 */
  val index: Int = valueIndex++
}

// 이 파일 코드를 읽기 전에 `@ComposableOpenTarget` 어노테이션 맥락을 충분히 이해해야 함

/**
 * A binding that is either closed (with a non-null [token]) or open. Unified bindings are linked
 * together in a circular list by [Bindings]. All linked bindings are all closed simultaneously
 * when anyone of them is unified to a closed binding.
 *
 * 닫혀 있거나(-> `null`이 아닌 토큰이 할당됨) 열려 있는 바인딩입니다. 통합된 바인딩은
 * [Bindings.unify]에 의해 LinkedList로 서로 연결됩니다. 통합된 바인딩 중 일부가 닫히면,
 * LinkedList로 연결된 모든 [Binding]이 동시에 닫힙니다.
 *
 * unify: 통합(통일)하다
 *
 * Params:
 * [token] - [Binding]이 닫힌 경우, 현재 할당되어 있는 Applier 토큰
 *
 * @param token the applier token the binding is bound to if it is closed.
 */
class Binding(token: String? = null, observers: Set<Bindings>) {
  /**
   * The value of the binding. All linked bindings share the same value which also maintains
   * the count of linked bindings.
   *
   * 바인딩의 값입니다. 연결된 모든 바인딩은 동일한 값을 공유하며 연결된 바인딩의
   * 개수도 유지됩니다.
   */
  var value: Value = Value(token = token, observers = observers)

  /**
   * The token that is bound to this binding. If [token] is null then the binding is still open.
   *
   * 이 바인딩에 할당된 토큰입니다. 토큰이 null이면 바인딩이 열려 있는 것입니다.
   */
  val token: String? get() = value.token

  /**
   * The linked list next pointer. The list is circular an always non-empty as a binding will
   * always at least contain itself in its own list. All linked [Binding] are in the same
   * circular list. Open bindings that are unified together are linked.
   *
   * 연결된 [Binding]의 다음 포인터입니다. 바인딩은 항상 자체 목록에 자신을 포함하므로
   * 바인딩 목록은 항상 비어 있지 않은 순환 목록입니다. 연결된 모든 바인딩은 동일한 순환
   * 목록에 있습니다. 열린 바인딩은 LinkedList로 연결되어 있습니다.
   */
  var next: Binding = this

  override fun toString(): String =
    value.token?.let { "Binding(token = $it)" } ?: "Binding(index = ${value.index})"
}

/**
 * [Bindings] can create open or closed bindings and can unify bindings together. A binding is
 * either bound to an applier token or it is open. All open bindings of the same value are linked
 * together in a circular list. When variables from different groups are unified, their lists are
 * merged and they are all given the lower of the two group's value. Bindings of different values
 * with the same token are considered unified but there is no need to link them as neither will
 * ever change.
 *
 * [Bindings]는 열리거나(open) 닫힌(closed) [Binding]을 만들 수 있으며 [Binding]을 함께 통합할 수
 * 있습니다. [Binding]은 Applier 토큰에 바인딩되거나(-> 닫힘) 열려 있습니다. 동일한 [Value]의 모든
 * 열린 바인딩은 LinkedList로 함께 연결됩니다. 서로 다른 두 개의 [Binding]이 통합되면, 더 낮은
 * [Value.size]를 갖는 [Binding]에 더 큰 [Value.size]를 갖는 [Binding]을 연결합니다. 동일한
 * [Value.token]을 가진 서로 다른 [Value]의 바인딩은 통합된 것으로 간주되지만 둘 다 변경되지 않으므로
 * 연결할 필요가 없습니다.
 */
class Bindings {
  private val listeners = mutableListOf<() -> Unit>()

  /**
   * Create a fresh open Applier binding instance
   */
  fun open(): Binding = Binding(observers = setOf(this))

  /**
   * Create a closed Applier binding instance
   */
  fun closed(target: String): Binding = Binding(token = target, observers = emptySet())

  /**
   * Listen for when a unification closed a binding or bound two binding groups together.
   *
   * [Binding] 목록이 변경되었을 때 호출될 콜백을 등록하고, 이 콜백을 지우는 람다를
   * 반환합니다.
   */
  fun onChange(callback: () -> Unit): () -> Unit {
    listeners.add(callback)
    return { listeners.remove(callback) }
  }

  /**
   * Unify a and b; returns true if the unification succeeded. If both a and b are unbound they
   * will be bound together and will simultaneously be bound if either is later bound. If only
   * one is bound the other will be bound to the bound token. If a and b are bound already,
   * unify() returns true if they are bound to the same token or false if they are not. Binding
   * two open bindings that are already bound together is a noop and succeeds.
   *
   * [a]와 [b]를 통합하고, 통합에 성공하면 true을 반환합니다. [a]와 [b]가 모두 바인딩되지 않은 경우
   * 함께 묶이며, 나중에 둘 중 하나가 바인딩되면 동시에 바인딩됩니다. [a]와 [b] 중 하나만 바인딩되면
   * 다른 하나는 바인딩된 토큰에 바인딩됩니다. [a]와 [b]가 이미 바인딩된 경우, 서로 같은 토큰으로
   * 바인딩됐다면 true를, 그렇지 않다면 false를 반환합니다. 이미 unify된 두 개의 열린 바인딩을 함께
   * unify하는 것은 no-op이며 성공합니다.
   *
   * @param a an Applier binding instance
   * @param b an Applier binding instance
   * @return true if [a] and [b] can be unified together
   */
  fun unify(a: Binding, b: Binding): Boolean {
    val aToken = a.value.token
    val bToken = b.value.token

    return when {
      aToken != null && bToken == null -> bindToken(binding = b, token = aToken)
      aToken == null && bToken != null -> bindToken(binding = a, token = bToken)
      aToken != null && bToken != null -> aToken == bToken
      else -> bindTwoBindings(a = a, b = b)
    }
  }

  /** [binding]의 [Value]에 [token]을 할당하고, [bindingValueChanged]를 호출합니다. */
  private fun bindToken(binding: Binding, token: String): Boolean {
    val value = binding.value
    value.token = token
    bindingValueChanged(value)
    value.observers = emptySet()
    return true
  }

  private fun bindTwoBindings(a: Binding, b: Binding): Boolean {
    // Update the smallest binding list. If the bindings already have the same value then
    // they are already bound together. Using the smallest list ensures that binding all
    // bindings together will be no worse than O(N log N) operations where N is the number of
    // bindings.
    //
    // 가장 작은 바인딩 목록을 업데이트합니다. 바인딩에 이미 동일한 값이 있는 경우 이미 함께
    // 바인딩되어 있습니다. 가장 작은 목록을 사용하면 모든 바인딩을 함께 바인딩하는 것이 O(N log N)
    // 연산보다 나쁘지 않으며, 여기서 N은 바인딩의 수입니다.

    val aValue = a.value
    val bValue = b.value
    if (aValue == bValue) return true

    val aValueSize = aValue.size
    val bValueSize = bValue.size
    val newObservers = aValue.observers + bValue.observers

    if (aValueSize > bValueSize) {
      aValue.size += bValueSize
      aValue.observers = newObservers
      unifyValues(binding = b, value = aValue)
    } else {
      bValue.size += aValueSize
      bValue.observers = newObservers
      unifyValues(binding = a, value = bValue)
    }

    // Merge the circular lists by swapping a and b's next pointers.
    //
    //   https://en.wikipedia.org/wiki/Linked_list#Circularly_linked_vs._linearly_linked.
    //
    // This only works if a and b are in distinct lists. If they are in the same list this
    // breaks the list apart instead of merging. To ensure the lists are distinct the values
    // of merged lists are made identical and all new nodes are given unique values. This
    // ensures that the bindings in the same list have ths same value and the `aValue ==
    // bValue` check above prevent list splits.
    //
    // A와 B의 다음 포인터를 바꾸어 원형 목록을 병합합니다.
    //
    // 이 방법은 a와 b가 서로 다른 목록에 있는 경우에만 작동합니다. 같은 목록에 있으면
    // 병합하는 대신 목록을 분리(break)합니다. 목록을 구분하기 위해 병합된 목록의 값은 동일하게
    // 만들어지고 모든 새 노드에는 고유한 값이 부여됩니다. 이렇게 하면 동일한 목록의
    // 바인딩이 동일한 값을 가지게 되고 위의 `aValue == bValue` 검사는 목록 분할을 방지합니다.

    val nextA = a.next
    val nextB = b.next
    a.next = nextB
    b.next = nextA
    bindingValueChanged(a.value)

    return true
  }

  private fun unifyValues(binding: Binding, value: Value) {
    binding.value = value
    var current = binding.next
    while (current != binding) {
      current.value = value
      current = current.next
    }
  }

  private fun bindingValueChanged(value: Value) {
    for (observer in value.observers) {
      observer.changed()
    }
  }

  private fun changed() {
    if (listeners.isNotEmpty()) {
      // Enumerate a copy of the list to allow listeners to delete themselves from the list.
      // 리스트의 사본을 순회하여 리스너가 리스트에서 자신을 삭제할 수 있도록 합니다.
      for (listener in listeners.toMutableList()) {
        listener()
      }
    }
  }
}
