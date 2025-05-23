/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.name

/**
 * A *fully-qualified* name of a declaration or package. An [FqName] not only contains the declaration's or package's simple name, but also
 * the full name of the package it's contained in.
 *
 * #### Example
 *
 * ```kotlin
 * package foo.bar
 *
 * class A
 *
 * fun main() {}
 * ```
 *
 * The declarations above have the following fully-qualified names:
 *
 * - `A`: `foo.bar.A`
 * - `main`: `foo.bar.main`
 *
 * @see ClassId
 * @see CallableId
 */
class FqName {
  private val fqName: FqNameUnsafe

  // cache
  @Transient
  private var parent: FqName? = null

  constructor(fqName: String) {
    this.fqName = FqNameUnsafe(fqName, this)
  }

  constructor(fqName: FqNameUnsafe) {
    this.fqName = fqName
  }

  private constructor(fqName: FqNameUnsafe, parent: FqName) {
    this.fqName = fqName
    this.parent = parent
  }

  fun asString(): String {
    return fqName.asString()
  }

  fun toUnsafe(): FqNameUnsafe {
    return fqName
  }

  val isRoot: Boolean
    get() = fqName.isRoot

  fun parent(): FqName {
    parent?.let {
      return it
    }

    check(!isRoot) { "root" }

    return FqName(fqName.parent()).also {
      parent = it
    }
  }

  fun child(name: Name): FqName {
    return FqName(fqName.child(name), this)
  }

  fun shortName(): Name {
    return fqName.shortName()
  }

  fun shortNameOrSpecial(): Name {
    return fqName.shortNameOrSpecial()
  }

  fun pathSegments(): List<Name> {
    return fqName.pathSegments()
  }

  fun startsWith(segment: Name): Boolean {
    return fqName.startsWith(segment)
  }

  fun startsWith(other: FqName): Boolean {
    return fqName.startsWith(other.fqName)
  }

  override fun toString(): String {
    return fqName.toString()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is FqName) return false

    if (fqName != other.fqName) return false

    return true
  }

  override fun hashCode(): Int {
    return fqName.hashCode()
  }

  companion object {
    @JvmStatic
    fun fromSegments(names: List<String?>): FqName {
      return FqName(names.joinToString("."))
    }

    @JvmField
    val ROOT: FqName = FqName("")

    @JvmStatic
    fun topLevel(shortName: Name): FqName {
      return FqName(FqNameUnsafe.topLevel(shortName))
    }
  }
}
