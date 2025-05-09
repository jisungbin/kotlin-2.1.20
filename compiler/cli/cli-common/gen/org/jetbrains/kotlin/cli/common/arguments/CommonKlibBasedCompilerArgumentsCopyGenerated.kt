@file:Suppress("unused", "DuplicatedCode")

// DO NOT EDIT MANUALLY!
// Generated by generators/tests/org/jetbrains/kotlin/generators/arguments/GenerateCompilerArgumentsCopy.kt
// To regenerate run 'generateCompilerArgumentsCopy' task

package org.jetbrains.kotlin.cli.common.arguments

@OptIn(org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI::class)
fun copyCommonKlibBasedCompilerArguments(from: CommonKlibBasedCompilerArguments, to: CommonKlibBasedCompilerArguments): CommonKlibBasedCompilerArguments {
  copyCommonCompilerArguments(from, to)

  to.duplicatedUniqueNameStrategy = from.duplicatedUniqueNameStrategy
  to.enableSignatureClashChecks = from.enableSignatureClashChecks
  to.irInlinerBeforeKlibSerialization = from.irInlinerBeforeKlibSerialization
  to.noDoubleInlining = from.noDoubleInlining
  to.normalizeAbsolutePath = from.normalizeAbsolutePath
  to.partialLinkageLogLevel = from.partialLinkageLogLevel
  to.partialLinkageMode = from.partialLinkageMode
  to.relativePathBases = from.relativePathBases?.copyOf()

  return to
}
