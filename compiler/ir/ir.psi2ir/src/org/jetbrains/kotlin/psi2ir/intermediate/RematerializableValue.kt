/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.psi2ir.intermediate

import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer

internal fun Scope.createTemporaryVariableInBlock(
  context: IrGeneratorContext,
  irExpression: IrExpression,
  block: IrStatementContainer,
  nameHint: String? = null,
  startOffset: Int = irExpression.startOffset,
  endOffset: Int = irExpression.endOffset,
): IntermediateValue {
  return VariableLValue(
    context,
    declareTemporaryVariableInBlock(irExpression, block, nameHint, startOffset, endOffset)
  )
}

internal fun Scope.declareTemporaryVariableInBlock(
  irExpression: IrExpression,
  block: IrStatementContainer,
  nameHint: String? = null,
  startOffset: Int = irExpression.startOffset,
  endOffset: Int = irExpression.endOffset,
): IrVariable {
  val temporaryVariable = createTemporaryVariable(irExpression, nameHint, startOffset = startOffset, endOffset = endOffset)
  block.statements.add(temporaryVariable)
  return temporaryVariable
}
