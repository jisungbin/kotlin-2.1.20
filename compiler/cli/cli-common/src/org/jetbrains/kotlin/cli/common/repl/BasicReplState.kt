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

package org.jetbrains.kotlin.cli.common.repl

import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

data class LineId(override val no: Int, override val generation: Int, private val codeHash: Int) : ILineId, Serializable {

  override fun compareTo(other: ILineId): Int = (other as? LineId)?.let { lineId ->
    no.compareTo(lineId.no).takeIf { no -> no != 0 }
      ?: codeHash.compareTo(lineId.codeHash)
  } ?: -1 // TODO: check if it doesn't break something

  companion object {
    private const val serialVersionUID: Long = 8328354000L
  }
}

open class BasicReplStageHistory<T>(override val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()) : IReplStageHistory<T>, ArrayList<ReplHistoryRecord<T>>() {

  val currentLineNumber = AtomicInteger(REPL_CODE_LINE_FIRST_NO)
  val currentGeneration = AtomicInteger(REPL_CODE_LINE_FIRST_GEN)

  override fun push(id: ILineId, item: T) {
    lock.write {
      add(ReplHistoryRecord(id, item))
    }
  }

  override fun pop(): ReplHistoryRecord<T>? = lock.write { if (isEmpty()) null else removeAt(lastIndex) }

  override fun reset(): Iterable<ILineId> {
    lock.write {
      val removed = map { it.id }
      clear()
      currentGeneration.incrementAndGet()
      currentLineNumber.set(REPL_CODE_LINE_FIRST_NO)
      return removed
    }
  }

  override fun resetTo(id: ILineId): Iterable<ILineId> {
    lock.write {
      return tryResetTo(id) ?: throw NoSuchElementException("Cannot reset to non-existent line ${id.no}")
    }
  }

  protected fun tryResetTo(id: ILineId): List<ILineId>? {
    val idx = indexOfFirst { it.id == id }
    if (idx < 0) return null
    return if (idx < lastIndex) {
      val removed = asSequence().drop(idx + 1).map { it.id }.toList()
      removeRange(idx + 1, size)
      currentGeneration.incrementAndGet()
      removed.lastOrNull()?.no?.let {
        currentLineNumber.set(it)
      }
      removed
    } else {
      currentGeneration.incrementAndGet()
      currentLineNumber.set(id.no + 1)
      emptyList()
    }
  }
}

open class BasicReplStageState<HistoryItemT>(override final val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()) : IReplStageState<HistoryItemT> {

  override val currentGeneration: Int get() = history.currentGeneration.get()

  override fun getNextLineNo(): Int = history.currentLineNumber.getAndIncrement()

  override val history: BasicReplStageHistory<HistoryItemT> = BasicReplStageHistory(lock)
}
