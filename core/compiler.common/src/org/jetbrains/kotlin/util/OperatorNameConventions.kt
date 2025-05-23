/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.util

import org.jetbrains.kotlin.name.Name

object OperatorNameConventions {
  @JvmField val GET_VALUE = Name.identifier("getValue")
  @JvmField val SET_VALUE = Name.identifier("setValue")
  @JvmField val PROVIDE_DELEGATE = Name.identifier("provideDelegate")

  @JvmField val EQUALS = Name.identifier("equals")
  @JvmField val HASH_CODE = Name.identifier("hashCode")
  @JvmField val COMPARE_TO = Name.identifier("compareTo")
  @JvmField val CONTAINS = Name.identifier("contains")
  @JvmField val INVOKE = Name.identifier("invoke")
  @JvmField val ITERATOR = Name.identifier("iterator")
  @JvmField val GET = Name.identifier("get")
  @JvmField val SET = Name.identifier("set")
  @JvmField val NEXT = Name.identifier("next")
  @JvmField val HAS_NEXT = Name.identifier("hasNext")

  @JvmField val TO_STRING = Name.identifier("toString")

  @JvmField val COMPONENT_REGEX = Regex("component\\d+")

  @JvmField val AND = Name.identifier("and")
  @JvmField val OR = Name.identifier("or")
  @JvmField val XOR = Name.identifier("xor")
  @JvmField val INV = Name.identifier("inv")

  @JvmField val SHL = Name.identifier("shl")
  @JvmField val SHR = Name.identifier("shr")
  @JvmField val USHR = Name.identifier("ushr")

  @JvmField val INC = Name.identifier("inc")
  @JvmField val DEC = Name.identifier("dec")
  @JvmField val PLUS = Name.identifier("plus")
  @JvmField val MINUS = Name.identifier("minus")
  @JvmField val NOT = Name.identifier("not")

  @JvmField val UNARY_MINUS = Name.identifier("unaryMinus")
  @JvmField val UNARY_PLUS = Name.identifier("unaryPlus")

  @JvmField val TIMES = Name.identifier("times")
  @JvmField val DIV = Name.identifier("div")
  @JvmField val REM = Name.identifier("rem")
  @JvmField val RANGE_TO = Name.identifier("rangeTo")
  @JvmField val RANGE_UNTIL = Name.identifier("rangeUntil")

  @JvmField val TIMES_ASSIGN = Name.identifier("timesAssign")
  @JvmField val DIV_ASSIGN = Name.identifier("divAssign")
  @JvmField val REM_ASSIGN = Name.identifier("remAssign")
  @JvmField val PLUS_ASSIGN = Name.identifier("plusAssign")
  @JvmField val MINUS_ASSIGN = Name.identifier("minusAssign")

  @JvmField val TO_DOUBLE = Name.identifier("toDouble")
  @JvmField val TO_FLOAT = Name.identifier("toFloat")
  @JvmField val TO_LONG = Name.identifier("toLong")
  @JvmField val TO_INT = Name.identifier("toInt")
  @JvmField val TO_CHAR = Name.identifier("toChar")
  @JvmField val TO_SHORT = Name.identifier("toShort")
  @JvmField val TO_BYTE = Name.identifier("toByte")

  // If you add new unary, binary or assignment operators, add it to OperatorConventions as well

  @JvmField
  val UNARY_OPERATION_NAMES = setOf(INC, DEC, UNARY_PLUS, UNARY_MINUS, NOT, INV)

  @JvmField
  val SIMPLE_UNARY_OPERATION_NAMES = setOf(UNARY_PLUS, UNARY_MINUS, NOT, INV)

  @JvmField
  val BINARY_OPERATION_NAMES = setOf(TIMES, PLUS, MINUS, DIV, REM, RANGE_TO, RANGE_UNTIL)

  @JvmField
  val SIMPLE_BINARY_OPERATION_NAMES = setOf(TIMES, PLUS, MINUS, DIV, REM)

  @JvmField
  val BITWISE_OPERATION_NAMES = setOf(AND, OR, XOR, INV, SHL, SHR, USHR)

  @JvmField
  val SIMPLE_BITWISE_OPERATION_NAMES = setOf(AND, OR, XOR, SHL, SHR, USHR)

  @JvmField
  val ALL_BINARY_OPERATION_NAMES = BINARY_OPERATION_NAMES + BITWISE_OPERATION_NAMES + setOf(EQUALS, CONTAINS, COMPARE_TO)

  @JvmField
  val ASSIGNMENT_OPERATIONS = setOf(TIMES_ASSIGN, DIV_ASSIGN, REM_ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN)

  @JvmField
  val DELEGATED_PROPERTY_OPERATORS = setOf(GET_VALUE, SET_VALUE, PROVIDE_DELEGATE)

  @JvmField
  val STATEMENT_LIKE_OPERATORS = setOf(SET) + ASSIGNMENT_OPERATIONS

  @JvmField
  val NUMBER_CONVERSIONS = setOf(TO_DOUBLE, TO_FLOAT, TO_LONG, TO_INT, TO_SHORT, TO_BYTE, TO_CHAR)

  val TOKENS_BY_OPERATOR_NAME = mapOf(
    INC to "++",
    DEC to "--",
    UNARY_PLUS to "+",
    UNARY_MINUS to "-",
    NOT to "!",
    TIMES to "*",
    PLUS to "+",
    MINUS to "-",
    DIV to "/",
    REM to "%",
    RANGE_TO to "..",
    RANGE_UNTIL to "..<",
  )

  fun isComponentN(name: Name): Boolean {
    val identifier = name.identifierOrNullIfSpecial ?: return false
    return COMPONENT_REGEX.matches(identifier)
  }
}
