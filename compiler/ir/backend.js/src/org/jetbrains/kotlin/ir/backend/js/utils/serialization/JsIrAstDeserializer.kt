/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.utils.serialization

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Deque
import org.jetbrains.kotlin.ir.backend.js.export.TypeScriptFragment
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsIrIcClassModel
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsIrProgramFragment
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsIrProgramFragments
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsIrProgramTestEnvironment
import org.jetbrains.kotlin.ir.backend.js.utils.emptyScope
import org.jetbrains.kotlin.js.backend.ast.JsArrayAccess
import org.jetbrains.kotlin.js.backend.ast.JsArrayLiteral
import org.jetbrains.kotlin.js.backend.ast.JsBinaryOperation
import org.jetbrains.kotlin.js.backend.ast.JsBinaryOperator
import org.jetbrains.kotlin.js.backend.ast.JsBlock
import org.jetbrains.kotlin.js.backend.ast.JsBooleanLiteral
import org.jetbrains.kotlin.js.backend.ast.JsBreak
import org.jetbrains.kotlin.js.backend.ast.JsCase
import org.jetbrains.kotlin.js.backend.ast.JsCatch
import org.jetbrains.kotlin.js.backend.ast.JsClass
import org.jetbrains.kotlin.js.backend.ast.JsComment
import org.jetbrains.kotlin.js.backend.ast.JsCompositeBlock
import org.jetbrains.kotlin.js.backend.ast.JsConditional
import org.jetbrains.kotlin.js.backend.ast.JsContinue
import org.jetbrains.kotlin.js.backend.ast.JsDebugger
import org.jetbrains.kotlin.js.backend.ast.JsDefault
import org.jetbrains.kotlin.js.backend.ast.JsDoWhile
import org.jetbrains.kotlin.js.backend.ast.JsDocComment
import org.jetbrains.kotlin.js.backend.ast.JsDoubleLiteral
import org.jetbrains.kotlin.js.backend.ast.JsDynamicScope
import org.jetbrains.kotlin.js.backend.ast.JsEmpty
import org.jetbrains.kotlin.js.backend.ast.JsExport
import org.jetbrains.kotlin.js.backend.ast.JsExpression
import org.jetbrains.kotlin.js.backend.ast.JsExpressionStatement
import org.jetbrains.kotlin.js.backend.ast.JsFor
import org.jetbrains.kotlin.js.backend.ast.JsForIn
import org.jetbrains.kotlin.js.backend.ast.JsFunction
import org.jetbrains.kotlin.js.backend.ast.JsIf
import org.jetbrains.kotlin.js.backend.ast.JsImport
import org.jetbrains.kotlin.js.backend.ast.JsImportedModule
import org.jetbrains.kotlin.js.backend.ast.JsIntLiteral
import org.jetbrains.kotlin.js.backend.ast.JsInvocation
import org.jetbrains.kotlin.js.backend.ast.JsLabel
import org.jetbrains.kotlin.js.backend.ast.JsLocation
import org.jetbrains.kotlin.js.backend.ast.JsMultiLineComment
import org.jetbrains.kotlin.js.backend.ast.JsName
import org.jetbrains.kotlin.js.backend.ast.JsNameRef
import org.jetbrains.kotlin.js.backend.ast.JsNew
import org.jetbrains.kotlin.js.backend.ast.JsNode
import org.jetbrains.kotlin.js.backend.ast.JsNullLiteral
import org.jetbrains.kotlin.js.backend.ast.JsObjectLiteral
import org.jetbrains.kotlin.js.backend.ast.JsParameter
import org.jetbrains.kotlin.js.backend.ast.JsPostfixOperation
import org.jetbrains.kotlin.js.backend.ast.JsPrefixOperation
import org.jetbrains.kotlin.js.backend.ast.JsPropertyInitializer
import org.jetbrains.kotlin.js.backend.ast.JsRegExp
import org.jetbrains.kotlin.js.backend.ast.JsReturn
import org.jetbrains.kotlin.js.backend.ast.JsScope
import org.jetbrains.kotlin.js.backend.ast.JsSingleLineComment
import org.jetbrains.kotlin.js.backend.ast.JsStatement
import org.jetbrains.kotlin.js.backend.ast.JsStringLiteral
import org.jetbrains.kotlin.js.backend.ast.JsSuperRef
import org.jetbrains.kotlin.js.backend.ast.JsSwitch
import org.jetbrains.kotlin.js.backend.ast.JsThisRef
import org.jetbrains.kotlin.js.backend.ast.JsThrow
import org.jetbrains.kotlin.js.backend.ast.JsTry
import org.jetbrains.kotlin.js.backend.ast.JsUnaryOperator
import org.jetbrains.kotlin.js.backend.ast.JsVars
import org.jetbrains.kotlin.js.backend.ast.JsWhile
import org.jetbrains.kotlin.js.backend.ast.JsYield
import org.jetbrains.kotlin.js.backend.ast.metadata.LocalAlias
import org.jetbrains.kotlin.js.backend.ast.metadata.SideEffectKind
import org.jetbrains.kotlin.js.backend.ast.metadata.SpecialFunction
import org.jetbrains.kotlin.js.backend.ast.metadata.constant
import org.jetbrains.kotlin.js.backend.ast.metadata.exportedPackage
import org.jetbrains.kotlin.js.backend.ast.metadata.exportedTag
import org.jetbrains.kotlin.js.backend.ast.metadata.hasDefaultValue
import org.jetbrains.kotlin.js.backend.ast.metadata.imported
import org.jetbrains.kotlin.js.backend.ast.metadata.isInline
import org.jetbrains.kotlin.js.backend.ast.metadata.isLocal
import org.jetbrains.kotlin.js.backend.ast.metadata.localAlias
import org.jetbrains.kotlin.js.backend.ast.metadata.sideEffects
import org.jetbrains.kotlin.js.backend.ast.metadata.specialFunction
import org.jetbrains.kotlin.js.backend.ast.metadata.synthetic

fun deserializeJsIrProgramFragment(input: ByteArray): JsIrProgramFragments {
  return JsIrAstDeserializer(input).readFragments()
}

private class JsIrAstDeserializer(private val source: ByteArray) {

  private val buffer = ByteBuffer.wrap(source)

  private val scope = emptyScope
  private val fileStack: Deque<String> = ArrayDeque()

  private val stringTable = readArray { readString() }
  private val nameTable = readArray { readName() }

  private fun readByte(): Byte {
    return buffer.get()
  }

  private fun readBoolean(): Boolean {
    return readByte() != 0.toByte()
  }

  private fun readInt(): Int {
    return buffer.int
  }

  private fun readDouble(): Double {
    return buffer.double
  }

  private fun readString(): String {
    val length = readInt()
    val offset = buffer.position()
    val result = String(source, offset, length, SerializationCharset)
    buffer.position(offset + length)
    return result
  }

  private inline fun <reified T> readArray(readElement: () -> T): Array<T> {
    return Array<T>(readInt()) { readElement() }
  }

  private inline fun readRepeated(readElement: () -> Unit) {
    var length = readInt()
    while (length-- > 0) {
      readElement()
    }
  }

  private inline fun <T> readList(readElement: () -> T): List<T> {
    val length = readInt()
    val result = ArrayList<T>(length)
    for (i in 0 until length) {
      result.add(readElement())
    }
    return result
  }

  private inline fun <T> ifTrue(then: () -> T): T? {
    return if (readBoolean()) then() else null
  }

  fun readFragments(): JsIrProgramFragments {
    return JsIrProgramFragments(readFragment(), ifTrue { readFragment() })
  }

  fun readFragment(): JsIrProgramFragment {
    return JsIrProgramFragment(readString(), readString()).apply {
      readRepeated {
        importedModules += JsImportedModule(
          externalName = stringTable[readInt()],
          internalName = nameTable[readInt()],
          plainReference = ifTrue { readExpression() }
        )
      }

      readRepeated { imports[stringTable[readInt()]] = readStatement() }

      readRepeated { declarations.statements += readStatement() }
      readRepeated { initializers.statements += readStatement() }
      readRepeated { eagerInitializers.statements += readStatement() }
      readRepeated { exports.statements += readStatement() }
      readRepeated { polyfills.statements += readStatement() }

      readRepeated { nameBindings[stringTable[readInt()]] = nameTable[readInt()] }
      readRepeated { optionalCrossModuleImports.add(stringTable[readInt()]) }
      readRepeated { classes[nameTable[readInt()]] = readIrIcClassModel() }

      ifTrue { mainFunctionTag = readString() }
      ifTrue { testEnvironment = readTestEnvironment() }
      ifTrue { dts = TypeScriptFragment(readString()) }

      readRepeated { definitions += stringTable[readInt()] }
    }
  }

  private fun readIrIcClassModel(): JsIrIcClassModel {
    return JsIrIcClassModel(readList { nameTable[readInt()] }).apply {
      readRepeated { preDeclarationBlock.statements += readStatement() }
      readRepeated { postDeclarationBlock.statements += readStatement() }
    }
  }

  private fun readTestEnvironment(): JsIrProgramTestEnvironment {
    return JsIrProgramTestEnvironment(stringTable[readInt()], stringTable[readInt()])
  }

  private fun readStatement(): JsStatement {
    return withComments {
      withLocation {
        with(StatementIds) {
          when (val id = readByte().toInt()) {
            RETURN -> {
              JsReturn(ifTrue { readExpression() })
            }
            THROW -> {
              JsThrow(readExpression())
            }
            BREAK -> {
              JsBreak(ifTrue { JsNameRef(nameTable[readInt()]) })
            }
            CONTINUE -> {
              JsContinue(ifTrue { JsNameRef(nameTable[readInt()]) })
            }
            DEBUGGER -> {
              JsDebugger()
            }
            EXPRESSION -> {
              JsExpressionStatement(readExpression()).apply {
                ifTrue { exportedTag = stringTable[readInt()] }
              }
            }
            VARS -> {
              readVars()
            }
            BLOCK -> {
              JsBlock().apply {
                readRepeated { statements += readStatement() }
              }
            }
            COMPOSITE_BLOCK -> {
              readCompositeBlock()
            }
            LABEL -> {
              JsLabel(nameTable[readInt()], readStatement())
            }
            IF -> {
              JsIf(readExpression(), readStatement(), ifTrue { readStatement() })
            }
            SWITCH -> {
              JsSwitch(
                readExpression(),
                readList {
                  withLocation {
                    ifTrue {
                      JsCase().apply { caseExpression = readExpression() }
                    } ?: JsDefault()
                  }.apply {
                    readRepeated {
                      statements += readStatement()
                    }
                  }
                }
              )
            }
            WHILE -> {
              JsWhile(readExpression(), readStatement())
            }
            DO_WHILE -> {
              JsDoWhile(readExpression(), readStatement())
            }
            FOR -> {
              val condition = ifTrue { readExpression() }
              val incrementExpression = ifTrue { readExpression() }
              val body = ifTrue { readStatement() }

              ifTrue {
                JsFor(
                  readVars(),
                  condition,
                  incrementExpression,
                  body
                )
              } ?: JsFor(
                ifTrue { readExpression() },
                condition,
                incrementExpression,
                body
              )
            }
            FOR_IN -> {
              JsForIn(
                ifTrue { nameTable[readInt()] },
                ifTrue { readExpression() },
                readExpression(),
                readStatement()
              )
            }
            TRY -> {
              JsTry(
                readBlock(),
                readList {
                  JsCatch(nameTable[readInt()]).apply {
                    body = readBlock()
                  }
                },
                ifTrue { readBlock() }
              )
            }
            EXPORT -> {
              JsExport(
                when (val type = readByte().toInt()) {
                  ExportType.ALL -> JsExport.Subject.All
                  ExportType.ITEMS -> JsExport.Subject.Elements(readList {
                    JsExport.Element(
                      nameTable[readInt()].makeRef(),
                      ifTrue { nameTable[readInt()] }
                    )
                  })
                  else -> error("Unknown JsExport type $type")
                },
                ifTrue { readString() }
              )
            }
            IMPORT -> {
              JsImport(
                readString(),
                when (val type = readByte().toInt()) {
                  ImportType.EFFECT -> JsImport.Target.Effect
                  ImportType.ALL -> JsImport.Target.All(nameTable[readInt()].makeRef())
                  ImportType.DEFAULT -> JsImport.Target.Default(nameTable[readInt()].makeRef())
                  ImportType.ITEMS -> JsImport.Target.Elements(readList {
                    JsImport.Element(
                      nameTable[readInt()],
                      ifTrue { nameTable[readInt()].makeRef() }
                    )

                  }.toMutableList())
                  else -> error("Unknown JsImport type $type")
                }
              )
            }
            EMPTY -> {
              JsEmpty
            }
            SINGLE_LINE_COMMENT -> {
              JsSingleLineComment(readString())
            }
            MULTI_LINE_COMMENT -> {
              JsMultiLineComment(readString())
            }
            else -> error("Unknown statement id: $id")
          }
        }
      }
    }.apply {
      synthetic = readBoolean()
    }
  }

  private val sideEffectKindValues get() = SideEffectKind.entries
  private val jsBinaryOperatorValues get() = JsBinaryOperator.entries
  private val jsUnaryOperatorValues get() = JsUnaryOperator.entries
  private val jsFunctionModifiersValues get() = JsFunction.Modifier.entries

  private fun readExpression(): JsExpression {
    return withComments {
      withLocation {
        with(ExpressionIds) {
          when (val id = readByte().toInt()) {
            THIS_REF -> {
              JsThisRef()
            }
            SUPER_REF -> {
              JsSuperRef()
            }
            NULL -> {
              JsNullLiteral()
            }
            TRUE_LITERAL -> {
              JsBooleanLiteral(true)
            }
            FALSE_LITERAL -> {
              JsBooleanLiteral(false)
            }
            STRING_LITERAL -> {
              JsStringLiteral(stringTable[readInt()])
            }
            REG_EXP -> {
              JsRegExp().apply {
                pattern = stringTable[readInt()]
                ifTrue { flags = stringTable[readInt()] }
              }
            }
            INT_LITERAL -> {
              JsIntLiteral(readInt())
            }
            DOUBLE_LITERAL -> {
              JsDoubleLiteral(readDouble())
            }
            ARRAY_LITERAL -> {
              JsArrayLiteral(readList { readExpression() })
            }
            OBJECT_LITERAL -> {
              JsObjectLiteral(
                readList { JsPropertyInitializer(readExpression(), readExpression()) },
                readBoolean()
              )
            }
            FUNCTION -> {
              readFunction()
            }
            CLASS -> {
              JsClass(
                ifTrue { nameTable[readInt()] },
                ifTrue { readExpression() },
                ifTrue { readFunction() },
              ).apply {
                readRepeated { members += readFunction() }
              }
            }
            DOC_COMMENT -> {
              val tags = hashMapOf<String, Any>()
              readRepeated {
                tags[stringTable[readInt()]] = ifTrue { readExpression() } ?: stringTable[readInt()]
              }
              JsDocComment(tags)
            }
            BINARY_OPERATION -> {
              JsBinaryOperation(
                jsBinaryOperatorValues[readByte().toInt()],
                readExpression(),
                readExpression()
              )
            }
            PREFIX_OPERATION -> {
              JsPrefixOperation(jsUnaryOperatorValues[readByte().toInt()], readExpression())
            }
            POSTFIX_OPERATION -> {
              JsPostfixOperation(jsUnaryOperatorValues[readByte().toInt()], readExpression())
            }
            CONDITIONAL -> {
              JsConditional(
                readExpression(),
                readExpression(),
                readExpression()
              )
            }
            ARRAY_ACCESS -> {
              JsArrayAccess(readExpression(), readExpression())
            }
            NAME_REFERENCE -> {
              JsNameRef(nameTable[readInt()]).apply {
                ifTrue { qualifier = readExpression() }
                ifTrue { isInline = readBoolean() }
              }
            }
            SIMPLE_NAME_REFERENCE -> {
              JsNameRef(nameTable[readInt()])
            }
            PROPERTY_REFERENCE -> {
              JsNameRef(stringTable[readInt()]).apply {
                ifTrue { qualifier = readExpression() }
                ifTrue { isInline = readBoolean() }
              }
            }
            INVOCATION -> {
              JsInvocation(readExpression(), readList { readExpression() }).apply {
                ifTrue { isInline = readBoolean() }
              }
            }
            NEW -> {
              JsNew(readExpression(), readList { readExpression() })
            }
            YIELD -> {
              JsYield(ifTrue { readExpression() })
            }
            else -> error("Unknown expression id: $id")
          }
        }
      }
    }.apply {
      synthetic = readBoolean()
      sideEffects = sideEffectKindValues[readByte().toInt()]
      ifTrue { localAlias = readJsImportedModule() }
    }
  }

  private fun readFunction(): JsFunction {
    return JsFunction(scope, readBlock(), "").apply {
      readRepeated { parameters += readParameter() }
      readRepeated { modifiers += jsFunctionModifiersValues[readInt()] }
      ifTrue { name = nameTable[readInt()] }
      isLocal = readBoolean()
      isEs6Arrow = readBoolean()
    }
  }

  private fun readJsImportedModule(): JsImportedModule {
    return JsImportedModule(
      stringTable[readInt()],
      nameTable[readInt()],
      ifTrue { readExpression() }
    )
  }

  private fun readParameter(): JsParameter {
    return JsParameter(nameTable[readInt()]).apply {
      hasDefaultValue = readBoolean()
    }
  }

  private fun readCompositeBlock(): JsCompositeBlock {
    return JsCompositeBlock().apply {
      readRepeated { statements += readStatement() }
    }
  }

  private fun readBlock(): JsBlock {
    return ifTrue { readCompositeBlock() } ?: JsBlock().apply {
      readRepeated { statements += readStatement() }
    }
  }

  private fun readVars(): JsVars {
    return JsVars(readBoolean()).apply {
      readRepeated {
        vars += withLocation {
          JsVars.JsVar(nameTable[readInt()], ifTrue { readExpression() })
        }
      }
      ifTrue { exportedPackage = stringTable[readInt()] }
    }
  }

  private val specialFunctionValues get() = SpecialFunction.entries

  private fun readName(): JsName {
    val identifier = stringTable[readInt()]
    val name = ifTrue {
      JsScope.declareTemporaryName(identifier)
    } ?: JsDynamicScope.declareName(identifier)
    ifTrue { name.localAlias = readLocalAlias() }
    ifTrue { name.imported = true }
    ifTrue { name.constant = true }
    ifTrue { name.specialFunction = specialFunctionValues[readInt()] }
    return name
  }

  private fun readLocalAlias(): LocalAlias {
    return LocalAlias(
      nameTable[readInt()],
      ifTrue { stringTable[readInt()] }
    )
  }

  private fun readComment(): JsComment {
    val text = readString()
    return ifTrue { JsMultiLineComment(text) } ?: JsSingleLineComment(text)
  }

  private inline fun <T : JsNode> withLocation(action: () -> T): T {
    return ifTrue {
      val deserializedFile = ifTrue { stringTable[readInt()] }
      val file = deserializedFile ?: fileStack.peek()

      val startLine = readInt()
      val startChar = readInt()
      val deserializedLocation = file?.let { JsLocation(it, startLine, startChar) }

      val shouldUpdateFile = deserializedFile != null && deserializedFile != fileStack.peek()

      if (shouldUpdateFile) {
        fileStack.push(deserializedFile)
      }
      val node = action()
      if (deserializedLocation != null) {
        node.source = deserializedLocation
      }
      if (shouldUpdateFile) {
        fileStack.pop()
      }

      node
    } ?: action()
  }

  private inline fun <T : JsNode> withComments(action: () -> T): T {
    return action().apply {
      ifTrue { this.commentsBeforeNode = readArray { readComment() }.toList() }
      ifTrue { this.commentsAfterNode = readArray { readComment() }.toList() }
    }
  }
}
