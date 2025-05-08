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
 * A part of a [Scheme].
 */
sealed class Item {
  /** `ComposableOpenTarget.index == 음수`인 상황  */
  internal abstract val isAnonymous: Boolean
  internal open val isUnspecified: Boolean get() = false

  /** @param context `ComposableOpenTarget.index` 값 별로 사용할 [Binding] 인스턴스 목록.
   * 예를 들어, `context[2]`의 [Binding]은 `ComposableOpenTarget.index == 2`인 Applier를
   * 추론하는데 사용됩니다.
   */
  internal abstract fun toBinding(bindings: Bindings, context: MutableList<Binding>): Binding
  internal abstract fun serializeTo(writer: SchemeStringSerializationWriter)
}

/**
 * A bound part of a [Scheme] that is bound to [value].
 *
 * Closed Binding를 만드는 [Item] 구현체
 */
class Token(val value: String) : Item() {
  override val isAnonymous: Boolean get() = false

  override fun toBinding(bindings: Bindings, context: MutableList<Binding>): Binding =
    bindings.closed(value)

  override fun toString() = value
  override fun serializeTo(writer: SchemeStringSerializationWriter) {
    writer.writeToken(value)
  }

  override fun equals(other: Any?) = other is Token && other.value == value
  override fun hashCode(): Int = value.hashCode() * 31
}

/**
 * An open part of a [Scheme]. All [Open] items with the same non-negative index should be bound
 * together to the same applier. [Open] items with a negative index are considered anonymous and
 * are treated as independent.
 *
 * Open Binding을 만드는 [Item] 구현체. `@ComposableOpenTarget`의 정책을 따릅니다.
 */
class Open(val index: Int, override val isUnspecified: Boolean = false) : Item() {
  override val isAnonymous: Boolean get() = index < 0

  override fun toBinding(bindings: Bindings, context: MutableList<Binding>): Binding {
    // 익명 Applier라 모든 토큰을 항상 허용함
    if (index < 0) return bindings.open()

    while (index >= context.size) {
      context.add(bindings.open())
    }

    return context[index]
  }

  override fun toString() = if (index < 0) "_" else "$index"
  override fun equals(other: Any?) =
    other is Open && (other.index == index || (other.index < 0 && index < 0))

  override fun hashCode(): Int = if (index < 0) -31 else index * 31
  override fun serializeTo(writer: SchemeStringSerializationWriter) {
    writer.writeNumber(index)
  }
}

private enum class ItemKind {
  Open,
  Close,
  ResultPrefix,
  AnyParameters,
  Token,
  Number,
  End,
  Invalid,
}

/**
 * A [Scheme] declares the applier the type expects and which appliers are expected of the
 * lambda parameters of a function or bound callable types of a generic type. The applier can be
 * open but all open appliers of the same non-negative index must be bound to the same applier.
 *
 * All non-composable lambda parameters of a function type are ignored and skipped when producing
 * or interpreting a scheme. Also if the result is not a composable lambda the result is `null`.
 * This is because inference is only inferring what applier is being used by the `$composer`
 * passed as a parameter of a function. Note that a lambda that captures a `$composer` in context
 * (such as the lambda passed to [forEach] in a composable function) has a scheme as if the
 * `$composer` captured was passed in as a parameter just like it was a composable lambda.
 *
 * [Scheme]는 타입이 기대하는 Applier와 함수의 람다 매개변수 또는 제네릭 타입의 바인딩된 타입이
 * 기대하는 Applier를 선언합니다. 예상하는 Applier의 타입 정책은 `@ComposableOpenTarget` 문서를
 * 참고하세요.
 *
 * 컴포저블 람다 타입이 아닌 매개변수는 [Scheme]를 생성하거나 해석할 때 모두 무시하고 건너뜁니다.
 * 또한 [Scheme.result]가 컴포저블 람다가 아닐 경우에는 [Scheme.result]가 항상 `null`입니다.
 * 이는 컴포저블 람다의 매개변수로 전달된 $composer가 어떤 Applier를 사용하고 있는지로 [Scheme]을
 * 추론하기 때문입니다. 컴포저블 함수 안의 `forEach`에 전달된 람다처럼, 컨텍스트에서 $composer를
 * 캡처하는 람다는 캡처된 $composer가 컴포저블 람다처럼 파라미터로 전달된 것과 같은 [Scheme]를
 * 가지고 있다는 점에 유의하세요.
 */
class Scheme(
  val target: Item,
  val parameters: List<Scheme> = emptyList(),
  val result: Scheme? = null,
  val anyParameters: Boolean = false,
) {
  init {
    check(!anyParameters || parameters.isEmpty()) {
      "`anyParameters` == true must have empty parameters"
    }
  }

  /**
   * Produce a string serialization of the scheme. This is not necessarily readable, use
   * [toString] for debugging instead.
   *
   * 스키마의 문자열 직렬화를 생성합니다. 반드시 읽을 수 있는 것은 아니므로 디버깅에는
   * toString을 대신 사용하세요.
   */
  fun serialize(): String = buildString { serializeTo(SchemeStringSerializationWriter(this)) }

  override fun toString(): String = "[$target$parametersString$resultString]"

  private val parametersString: String
    get() = if (parameters.isEmpty()) "" else ", ${parameters.joinToString(", ") { it.toString() }}"

  private val resultString: String
    get() = result?.let { ": $it" }.orEmpty()

  /**
   * Compare to [Scheme] instances for equality. Two [Scheme]s are considered equal if they are
   * [alpha equivalent](https://en.wikipedia.org/wiki/Lambda_calculus#%CE%B1-conversion). This
   * is accomplished by normalizing both schemes and then comparing them simply for equality.
   * See [alphaRename] for details.
   *
   * 스키마 인스턴스를 비교하여 동등성을 확인합니다. 두 스키마의 alpha가 같으면 동일한 것으로
   * 간주합니다. 이는 두 스키마를 정규화한 다음 단순히 동일성을 비교하여 수행됩니다. 자세한
   * 내용은 [alphaRename]을 참조하세요.
   */
  override fun equals(other: Any?): Boolean {
    val o = other as? Scheme ?: return false
    return this.alphaRename().simpleEquals(o.alphaRename())
  }

  fun canOverride(other: Scheme): Boolean = alphaRename().simpleCanOverride(other.alphaRename())

  override fun hashCode(): Int = alphaRename().simpleHashCode()

  private fun simpleCanOverride(other: Scheme): Boolean =
    run {
      if (other.target is Open)
        target is Open && other.target.index == target.index
      else
        target.isUnspecified || target == other.target
    } && run {
      parameters.zip(other.parameters).all { (a, b) -> a.simpleCanOverride(b) }
    } && run {
      result == other.result ||
        (other.result != null && result != null && result.canOverride(other.result))
    }

  private fun simpleEquals(other: Scheme) =
    target == other.target &&
      parameters.zip(other.parameters).all { (a, b) -> a == b }

  private fun simpleHashCode(): Int =
    target.hashCode() * 31 + parameters.hashOfElements() + result.hashCode()

  private fun List<Scheme>.hashOfElements(): Int =
    if (isEmpty()) 0
    else map { it.simpleHashCode() }.reduceRight { h, acc -> h + acc * 31 }

  private fun serializeTo(writer: SchemeStringSerializationWriter) {
    writer.writeOpen()

    target.serializeTo(writer)

    if (anyParameters) {
      writer.writeAnyParameters()
    } else {
      parameters.forEach { it.serializeTo(writer) }
    }

    if (result != null) {
      writer.writeResultPrefix()
      result.serializeTo(writer)
    }

    writer.writeClose()
  }

  /**
   * Both hashCode and equals are in terms of alpha rename equivalents. That means that the scheme
   * [0, [0]] and [2, [2]] should be treated as equal even though they have different indexes
   * because they are alpha rename equivalent. This method will rename all variables
   * consistently so that if they are alpha equivalent then they will have the same open
   * indexes in the same location. If the scheme is already alpha rename consistent then this is
   * returned.
   *
   * `hashCode`와 `equals`은 모두 alpha rename에 해당합니다. 즉, [0, [0]]와 [2, [2]] 스키마는 alpha
   * rename이 동일하므로 인덱스가 다르더라도 같은 것으로 취급해야 합니다. 이 방법은 모든 변수의
   * 이름을 일관되게 바꾸어 alpha가 동일한 경우 같은 위치에서 같은 open index(`ComposableOpenTarget.index` 값)를
   * 갖도록 합니다. 스키마의 alpha rename이 이미 일관된 경우 this를 그대로 반환합니다.
   */
  // 복잡한 수학적 연산 알고리즘 지식이 필요해서 이해 포기
  private fun alphaRename(): Scheme {
    // Special case where the scheme would always be renamed to itself.
    if ((target !is Open || target.index in -1..0) && parameters.isEmpty()) return this

    // Calculate what the renames should be
    val alphaRenameMap = mutableMapOf<Int, Int>()
    var next = 0
    fun scan(scheme: Scheme) {
      val target = scheme.target
      val parameters = scheme.parameters
      val result = scheme.result
      if (target is Open) {
        val index = target.index
        if (index in alphaRenameMap) {
          if (index >= 0 && alphaRenameMap[index] == -1)
            alphaRenameMap[index] = next++
        } else alphaRenameMap[index] = -1
      }
      parameters.forEach { scan(it) }
      result?.let { scan(it) }
    }
    scan(this)

    // if no renames found, this is an entirely bound scheme, just return it
    if (alphaRenameMap.isEmpty()) return this

    fun rename(scheme: Scheme): Scheme {
      val target = scheme.target
      val parameters = scheme.parameters
      val result = scheme.result
      val newTarget = if (target is Open && target.index != alphaRenameMap[target.index])
        Open(alphaRenameMap[target.index]!!)
      else target
      val newParameters = parameters.map { rename(it) }
      val newResult = result?.let { rename(it) }
      return if (
        target !== newTarget || newParameters.zip(parameters).any { (a, b) ->
          a !== b
        } || newResult != result
      ) Scheme(newTarget, newParameters, newResult)
      else scheme
    }
    return rename(this)
  }
}

private class SchemeParseError : Exception("Internal scheme parse error")

private fun throwSchemeParseError(): Nothing = throw SchemeParseError()

/**
 * Given a string produce a [Scheme] if the string is a valid serialization of a [Scheme]
 * or null otherwise.
 *
 * 문자열이 주어졌을 때 문자열이 유효한 Scheme 직렬화인 경우 Scheme을 생성하고
 * 그렇지 않으면 null을 생성합니다.
 */
fun deserializeScheme(value: String): Scheme? {
  val reader = SchemeStringSerializationReader(value)

  fun item(): Item =
    when (reader.kind) {
      ItemKind.Token -> Token(reader.token())
      ItemKind.Number -> Open(reader.number())
      else -> throwSchemeParseError()
    }

  fun <T> list(content: () -> T): List<T> {
    if (reader.kind != ItemKind.Open) return emptyList()
    val result = mutableListOf<T>()
    while (reader.kind == ItemKind.Open) {
      result.add(content())
    }
    return result
  }

  fun <T> delimited(
    prefix: ItemKind,
    postfix: ItemKind,
    content: () -> T,
  ): T {
    reader.expect(prefix)
    return content().also { reader.expect(postfix) }
  }

  fun <T> optional(
    prefix: ItemKind,
    postfix: ItemKind = ItemKind.Invalid,
    content: () -> T,
  ): T? =
    if (reader.kind == prefix) {
      delimited(prefix, postfix, content)
    } else null

  fun isItem(kind: ItemKind): Boolean =
    if (reader.kind == kind) {
      reader.expect(kind)
      true
    } else false

  fun scheme(): Scheme =
    delimited(ItemKind.Open, ItemKind.Close) {
      val target = item()
      val anyParameters = isItem(ItemKind.AnyParameters)
      val parameters = if (anyParameters) emptyList() else list { scheme() }
      val result = optional(ItemKind.ResultPrefix) { scheme() }
      Scheme(target, parameters, result, anyParameters)
    }

  return try {
    scheme().also { reader.end() }
  } catch (_: SchemeParseError) {
    null
  }
}

internal class SchemeStringSerializationWriter(private val builder: StringBuilder) {
  fun writeToken(token: String) {
    if (isNormal(token)) {
      builder.append(token)
    } else {
      builder.append('"')
      builder.append(token.replace("\\", "\\\\").replace("\"", "\\\""))
      builder.append('"')
    }
  }

  fun writeNumber(number: Int) {
    if (number < 0) {
      builder.append('_')
    } else {
      builder.append(number)
    }
  }

  fun writeOpen() {
    builder.append('[')
  }

  fun writeClose() {
    builder.append(']')
  }

  fun writeResultPrefix() {
    builder.append(':')
  }

  fun writeAnyParameters() {
    builder.append('*')
  }

  override fun toString(): String = builder.toString()

  private fun isNormal(value: String): Boolean = value.all { it == '.' || it.isLetter() }
}

private class SchemeStringSerializationReader(private val value: String) {
  private var current = 0
  private val ch: Char get() = if (current < value.length) value[current] else EOS

  val kind: ItemKind
    get() = when (val ch = ch) {
      '_' -> ItemKind.Number
      '[' -> ItemKind.Open
      ']' -> ItemKind.Close
      ':' -> ItemKind.ResultPrefix
      '*' -> ItemKind.AnyParameters
      '"' -> ItemKind.Token
      else -> {
        when {
          ch.isLetter() -> ItemKind.Token
          ch.isDigit() -> ItemKind.Number
          ch == EOS -> ItemKind.End
          else -> ItemKind.Invalid
        }
      }
    }

  fun end() {
    if (kind != ItemKind.End)
      throwSchemeParseError()
  }

  fun number(): Int {
    if (ch == '_') {
      current++
      return -1
    }

    val start = current

    while (ch.isDigit()) current++

    return try {
      Integer.parseUnsignedInt(value.substring(start, current), 10)
    } catch (_: NumberFormatException) {
      throwSchemeParseError()
    }
  }

  fun token(): String {
    var start = current
    val end: Int
    var prefix = ""

    if (ch == '"') {
      current++
      start = current
      while (ch != '"' && ch != EOS) {
        if (ch == '\\') {
          prefix += value.subSequence(start, current).toString()
          current++
          start = current
          if (ch == '\"' || ch == '\\') {
            current++
          } else {
            throwSchemeParseError()
          }
        } else {
          current++
        }
      }
      end = current
      current++
    } else {
      while (run { val ch = ch; ch == '.' || ch.isLetter() }) current++
      end = current
    }

    return prefix + value.subSequence(start, end).toString()
  }

  fun expect(kind: ItemKind) {
    if (kind != ItemKind.Invalid) {
      if (this.kind != kind) {
        throwSchemeParseError()
      }
      when (this.kind) {
        ItemKind.Open -> expect('[')
        ItemKind.Close -> expect(']')
        ItemKind.ResultPrefix -> expect(':')
        ItemKind.AnyParameters -> expect('*')
        ItemKind.Token -> token()
        ItemKind.Number -> number()
        ItemKind.End -> end()
        else -> throwSchemeParseError()
      }
    }
  }

  private fun expect(ch: Char) {
    if (current < value.length && value[current] == ch) {
      current++
    } else {
      throwSchemeParseError()
    }
  }

  private companion object {
    private const val EOS: Char = '\u0000'
  }
}

internal fun Scheme.mergeWith(schemes: List<Scheme>): Scheme {
  if (schemes.isEmpty()) return this

  val lazyScheme = LazyScheme(this)
  val bindings = lazyScheme.bindings

  fun unifySchemes(a: LazyScheme, b: LazyScheme) {
    bindings.unify(a.target, b.target)
    for ((ap, bp) in a.parameters.zip(b.parameters)) {
      unifySchemes(ap, bp)
    }
  }

  schemes.forEach {
    val overrideScheme = LazyScheme(it, bindings = lazyScheme.bindings)
    unifySchemes(lazyScheme, overrideScheme)
  }

  return lazyScheme.toScheme()
}
