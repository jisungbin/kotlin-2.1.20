/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.compiler.plugins.kotlin

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

/* ktlint-disable max-line-length */
class DurableFunctionKeyCodegenTests(useFir: Boolean) : AbstractCodegenSignatureTest(useFir) {

  override fun CompilerConfiguration.updateConfiguration() {
    put(ComposeConfiguration.GENERATE_FUNCTION_KEY_META_ANNOTATION_KEY, true)
  }

  @Test
  fun testSimpleComposable(): Unit = validateBytecode(
    """
            @Composable fun Example() {}
        """
  ) { bytecode ->
    bytecode.assertFunctionKeyMetaAnnotationCount(1)
  }

  @Test
  fun testMultipleComposables(): Unit = validateBytecode(
    """
            @Composable fun Example1() {}
            @Composable fun Example2() {}
        """
  ) { bytecode ->
    bytecode.assertFunctionKeyMetaAnnotationCount(2)
  }

  @Test
  fun testComposableLambdas(): Unit = validateBytecode(
    """
            @Composable fun Row(content: @Composable () -> Unit) { content() }
            @Composable fun Example2() {
                Row {}
            }
        """
  ) { bytecode ->
    bytecode.assertFunctionKeyMetaAnnotationCount(3)
  }

  private fun String.assertFunctionKeyMetaAnnotationCount(expected: Int) {
    assertEquals(
      expected,
      lines().sumOf {
        when {
          it.contains("@Landroidx/compose/runtime/internal/FunctionKeyMeta%Container;") -> {
            it.occurrences("@Landroidx/compose/runtime/internal/FunctionKeyMeta;")
          }
          it.contains("@Landroidx/compose/runtime/internal/FunctionKeyMeta;") -> 1
          else -> 0
        }
      }
    )
  }

  private fun String.occurrences(substring: String): Int = split(substring)
    .dropLastWhile { it.isEmpty() }
    .count() - 1
}
