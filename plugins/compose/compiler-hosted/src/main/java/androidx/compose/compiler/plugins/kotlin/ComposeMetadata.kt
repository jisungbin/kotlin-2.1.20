/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package androidx.compose.compiler.plugins.kotlin

import org.jetbrains.kotlin.config.LanguageVersion

/**
 * Compose metadata encoded with declarations that require special handling for backwards compatibility.
 * Current version of compiler expects the following metadata format:
 * ```
 * ┌───────────────┬───────────────┐
 * │      0        │       1       │
 * ├───────────────┼───────────────┤
 * │ major version │ minor version │
 * └───────────────┴───────────────┘
 * ```
 */
// 이전 버전과의 호환성을 위해 특별한 처리가 필요한 선언으로 인코딩된
// 메타데이터를 작성합니다. 현재 버전의 컴파일러는 다음 메타데이터 형식을
// 예상합니다:
@JvmInline
value class ComposeMetadata(val data: ByteArray) {
  constructor(version: LanguageVersion) : this(byteArrayOf(version.major.toByte(), version.minor.toByte()))

  /**
   * Open functions with default params are supported 2.1.20 onwards.
   */
  // onwards: 이후, 앞으로, ~ 시간부터 계속
  fun supportsOpenFunctionsWithDefaultParams(): Boolean =
    data[0] >= 2 && data[1] >= 1
}
