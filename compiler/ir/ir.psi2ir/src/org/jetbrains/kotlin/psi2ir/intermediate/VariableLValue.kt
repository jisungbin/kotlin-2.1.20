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
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType

internal class VariableLValue(
  private val context: IrGeneratorContext,
  val startOffset: Int,
  val endOffset: Int,
  val symbol: IrValueSymbol,
  override val type: IrType,
  val origin: IrStatementOrigin? = null,
) :
  LValue,
  AssignmentReceiver {

  constructor(
    context: IrGeneratorContext,
    irVariable: IrVariable,
    origin: IrStatementOrigin? = null,
    startOffset: Int = irVariable.startOffset,
    endOffset: Int = irVariable.endOffset,
  ) :
    this(context, startOffset, endOffset, irVariable.symbol, irVariable.type, origin)

  override fun load(): IrExpression =
    IrGetValueImpl(startOffset, endOffset, type, symbol, origin)

  override fun store(irExpression: IrExpression): IrExpression =
    IrSetValueImpl(
      startOffset, endOffset,
      context.irBuiltIns.unitType,
      symbol,
      irExpression, origin
    )

  override fun assign(withLValue: (LValue) -> IrExpression): IrExpression =
    withLValue(this)
}
