/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.compiler.plugins.kotlin.lower

class PathPartInfo(val key: String) {
  var parent: PathPartInfo? = null
  var previous: PathPartInfo? = null

  fun print(
    builder: StringBuilder,
    pathSeparator: String = "/",
    siblingSeparator: String = ":",
  ) {
    with(builder) {
      var node = this@PathPartInfo
      if (node == ROOT) {
        append("<ROOT>")
        return
      }
      while (node != ROOT) {
        append(pathSeparator)
        append(node.key)
        val key = node.key
        var count = 0
        while (node.previous != null) {
          if (node.previous?.key == key) {
            count++
          }
          node = node.previous!!
        }
        if (count > 0) {
          append(siblingSeparator)
          append(count)
        }
        node = node.parent ?: ROOT
      }
    }
  }

  override fun toString() = StringBuilder().also { print(it) }.toString()

  companion object {
    val ROOT = PathPartInfo("ROOT")
  }
}

/**
 * This data structure is used to build unique but durable "key paths" for tree structures using a
 * DSL.
 *
 * This is primarily used by the [LiveLiteralTransformer] to create unique and durable keys for
 * all of the constant literals in an IR source tree.
 */
// 이 데이터 구조는 DSL을 사용하여 트리 구조에 대해 고유하지만 내구성이 뛰어난 "키 경로"를 구축하는 데
// 사용됩니다.
//
// 이 데이터 구조는 주로 LiveLiteralTransformer에서 IR 소스 트리의 모든 상수 리터럴에 대해 고유하고
// 내구성 있는 키를 생성하는 데 사용됩니다.
class DurableKeyVisitor(private var keys: MutableSet<String> = mutableSetOf()) {
  private var current: PathPartInfo = PathPartInfo.ROOT
  private var parent: PathPartInfo? = null
  private var sibling: PathPartInfo? = null

  /**
   * Enter into a new scope with path part [part].
   */
  fun <T> enter(part: String, block: () -> T): T {
    val prev = current
    val prevSibling = sibling
    val prevParent = parent
    val next = PathPartInfo(part)
    try {
      when {
        // next 노드의 부모를 prev 노드로 지정하고,
        // next 노드를 [sibling 대상 노드]로 지정함
        prevParent != null && prevSibling == null -> {
          next.parent = prevParent
          sibling = next
          parent = null
        }
        // next 노드의 sibling 노드를 이전의 [sibling 대상 노드]로 지정하고,
        // next 노드를 [sibling 대상 노드]로 지정함
        prevParent != null && prevSibling != null -> {
          next.previous = prevSibling
          sibling = next
          parent = null
        }
        // prevParent == null
        else -> {
          next.parent = prev
          parent = null
        }
      }
      current = next
      return block()
    } finally {
      current = prev
      parent = prevParent
    }
  }

  /**
   * Inside this block, treat all entered path parts as siblings of the current path part.
   */
  fun <T> siblings(block: () -> T): T {
    if (parent != null) {
      // we are already in a siblings block, so we want this to be a no-op
      return block()
    }
    val prevSibling = sibling
    val prevParent = parent
    val prevCurrent = current
    try {
      parent = current
      sibling = null
      return block()
    } finally {
      parent = prevParent
      sibling = prevSibling
      current = prevCurrent
    }
  }

  /**
   * Enter into a new scope with path part [part] and assume entered paths to be children of
   * that path.
   *
   * This is shorthand for `enter(part) { siblings(block) } }`.
   */
  fun <T> siblings(part: String, block: () -> T): T = enter(part) { siblings(block) }

  /**
   * This API is meant to allow for a sub-hierarchy of the tree to be treated as its own scope.
   * This will use the provided [keys] Set as the container for keys that are built while in
   * this scope. Inside of this scope, the previous scope will be completely ignored.
   */
  // 이 API는 트리의 하위 계층을 자체 범위로 취급할 수 있도록 하기 위한 것입니다.
  // 그러면 제공된 [keys] Set이 이 스코프에 있는 동안 빌드된 키의 컨테이너로 사용됩니다.
  // 이 범위 내에서 이전 범위는 완전히 무시됩니다.
  fun <T> root(
    keys: MutableSet<String> = mutableSetOf(),
    block: () -> T,
  ): T {
    val prevKeys = this.keys
    val prevCurrent = current
    val prevParent = parent
    val prevSibling = sibling
    try {
      this.keys = keys
      current = PathPartInfo.ROOT
      parent = null
      sibling = null
      return siblings(block)
    } finally {
      this.keys = prevKeys
      current = prevCurrent
      parent = prevParent
      sibling = prevSibling
    }
  }

  /**
   * Build a path at the current position in the tree.
   *
   * @param prefix A string to prefix the path with
   * @param pathSeparator The string used to separate parts of the path
   * @param siblingSeparator When duplicate siblings are found an incrementing index is used to
   * make the path unique. This string will be used to separate the path part from the
   * incrementing index. 중복된 sibling이 발견되면 경로를 고유하게 만들기 위해 증분 인덱스가
   * 사용됩니다. 이 문자열은 증분 인덱스에서 경로 부분을 분리하는 데 사용됩니다.
   *
   * @return A pair with `first` being the built key, and `second` being whether or not the key
   * was absent in the dictionary of already built keys. If `second` is false, this key is a
   * duplicate. (-> 생성된 키 목록에 추가를 성공했다면 `second=true`임)
   */
  // absent: 없는, 부재한
  fun buildPath(
    prefix: String,
    pathSeparator: String = "/",
    siblingSeparator: String = ":",
  ): Pair<String, Boolean> {
    return buildString {
      append(prefix)
      current.print(
        builder = this,
        pathSeparator = pathSeparator,
        siblingSeparator = siblingSeparator,
      )
    }.let {
      it to keys.add(it)
    }
  }
}
