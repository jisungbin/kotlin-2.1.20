/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util

sealed class ArrayMap<T : Any> : Iterable<T> {
  abstract val size: Int

  abstract operator fun set(index: Int, value: T)
  abstract operator fun get(index: Int): T?

  abstract fun copy(): ArrayMap<T>
}

internal object EmptyArrayMap : ArrayMap<Nothing>() {
  override val size: Int
    get() = 0

  override fun set(index: Int, value: Nothing) {
    throw IllegalStateException()
  }

  override fun get(index: Int): Nothing? {
    return null
  }

  override fun copy(): ArrayMap<Nothing> = this

  override fun iterator(): Iterator<Nothing> {
    return object : Iterator<Nothing> {
      override fun hasNext(): Boolean = false

      override fun next(): Nothing = throw NoSuchElementException()
    }
  }
}

internal class OneElementArrayMap<T : Any>(val value: T, val index: Int) : ArrayMap<T>() {
  override val size: Int
    get() = 1

  override fun set(index: Int, value: T) {
    throw IllegalStateException()
  }

  override fun get(index: Int): T? {
    return if (index == this.index) value else null
  }

  override fun copy(): ArrayMap<T> = OneElementArrayMap(value, index)

  override fun iterator(): Iterator<T> {
    return object : Iterator<T> {
      private var notVisited = true

      override fun hasNext(): Boolean {
        return notVisited
      }

      override fun next(): T {
        if (notVisited) {
          notVisited = false
          return value
        } else {
          throw NoSuchElementException()
        }
      }
    }
  }
}

internal class ArrayMapImpl<T : Any> private constructor(
  private var data: Array<Any?>,
  initialSize: Int,
) : ArrayMap<T>() {
  companion object {
    private const val DEFAULT_SIZE = 20
    private const val INCREASE_K = 2
  }

  constructor() : this(arrayOfNulls<Any>(DEFAULT_SIZE), 0)

  override var size: Int = initialSize
    private set


  private fun ensureCapacity(index: Int) {
    if (data.size > index) return
    var newSize = data.size
    do {
      newSize *= INCREASE_K
    } while (newSize <= index)
    data = data.copyOf(newSize)
  }

  override operator fun set(index: Int, value: T) {
    ensureCapacity(index)
    if (data[index] == null) {
      size++
    }
    data[index] = value
  }

  override operator fun get(index: Int): T? {
    @Suppress("UNCHECKED_CAST")
    return data.getOrNull(index) as T?
  }

  override fun copy(): ArrayMap<T> = ArrayMapImpl(data.copyOf(), size)

  override fun iterator(): Iterator<T> {
    return object : AbstractIterator<T>() {
      private var index = -1

      override fun computeNext() {
        do {
          index++
        } while (index < data.size && data[index] == null)
        if (index >= data.size) {
          done()
        } else {
          @Suppress("UNCHECKED_CAST")
          setNext(data[index] as T)
        }
      }
    }
  }

  fun remove(index: Int) {
    if (data[index] != null) {
      size--
    }
    data[index] = null
  }

  fun entries(): List<Entry<T>> {
    @Suppress("UNCHECKED_CAST")
    return data.mapIndexedNotNull { index, value -> if (value != null) Entry(index, value as T) else null }
  }

  data class Entry<T>(override val key: Int, override val value: T) : Map.Entry<Int, T>
}
