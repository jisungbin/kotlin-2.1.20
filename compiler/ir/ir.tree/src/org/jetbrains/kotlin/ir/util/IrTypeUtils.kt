/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.StandardNames.FqNames
import org.jetbrains.kotlin.builtins.UnsignedTypes
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.symbols.FqNameEqualityChecker
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.addAnnotations
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.createIrTypeCheckerState
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.mergeNullability
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.memoryOptimizedMap

val kotlinPackageFqn = FqName.fromSegments(listOf("kotlin"))
private val kotlinReflectionPackageFqn = kotlinPackageFqn.child(Name.identifier("reflect"))
private val kotlinCoroutinesPackageFqn = kotlinPackageFqn.child(Name.identifier("coroutines"))

fun IrType.isFunctionMarker(): Boolean = classifierOrNull?.isClassWithName("Function", kotlinPackageFqn) == true
fun IrType.isFunction(): Boolean = classifierOrNull?.isClassWithNamePrefix("Function", kotlinPackageFqn) == true
fun IrType.isKFunction(): Boolean = classifierOrNull?.isClassWithNamePrefix("KFunction", kotlinReflectionPackageFqn) == true
fun IrType.isSuspendFunction(): Boolean = classifierOrNull?.isClassWithNamePrefix("SuspendFunction", kotlinCoroutinesPackageFqn) == true
fun IrType.isKSuspendFunction(): Boolean = classifierOrNull?.isClassWithNamePrefix("KSuspendFunction", kotlinReflectionPackageFqn) == true

fun IrType.isKProperty(): Boolean = classifierOrNull?.isClassWithNamePrefix("KProperty", kotlinReflectionPackageFqn) == true
fun IrType.isKMutableProperty(): Boolean = classifierOrNull?.isClassWithNamePrefix("KMutableProperty", kotlinReflectionPackageFqn) == true

fun IrClassifierSymbol.isFunctionMarker(): Boolean = this.isClassWithName("Function", kotlinPackageFqn)
fun IrClassifierSymbol.isFunction(): Boolean = this.isClassWithNamePrefix("Function", kotlinPackageFqn)
fun IrClassifierSymbol.isKFunction(): Boolean = this.isClassWithNamePrefix("KFunction", kotlinReflectionPackageFqn)
fun IrClassifierSymbol.isSuspendFunction(): Boolean = this.isClassWithNamePrefix("SuspendFunction", kotlinCoroutinesPackageFqn)
fun IrClassifierSymbol.isKSuspendFunction(): Boolean = this.isClassWithNamePrefix("KSuspendFunction", kotlinReflectionPackageFqn)

private fun IrClassifierSymbol.isClassWithName(name: String, packageFqName: FqName): Boolean {
  val declaration = owner as IrDeclarationWithName
  return name == declaration.name.asString() && (declaration.parent as? IrPackageFragment)?.packageFqName == packageFqName
}

private fun IrClassifierSymbol.isClassWithNamePrefix(prefix: String, packageFqName: FqName): Boolean {
  val declaration = owner as IrDeclarationWithName
  return declaration.name.asString().startsWith(prefix) && (declaration.parent as? IrPackageFragment)?.packageFqName == packageFqName
}

fun IrClassifierSymbol.superTypes(): List<IrType> = when (this) {
  is IrClassSymbol -> owner.superTypes
  is IrTypeParameterSymbol -> owner.superTypes
  is IrScriptSymbol -> emptyList()
}

fun IrClassifierSymbol.isSubtypeOfClass(superClass: IrClassSymbol): Boolean =
  FqNameEqualityChecker.areEqual(this, superClass) || isStrictSubtypeOfClass(superClass)

fun IrClassifierSymbol.isStrictSubtypeOfClass(superClass: IrClassSymbol): Boolean =
  superTypes().any { it.isSubtypeOfClass(superClass) }

fun IrType.superTypes(): List<IrType> = classifierOrNull?.superTypes() ?: emptyList()

fun IrType.isFunctionTypeOrSubtype(): Boolean = DFS.ifAny(listOf(this), IrType::superTypes, IrType::isFunction)
fun IrType.isSuspendFunctionTypeOrSubtype(): Boolean = DFS.ifAny(listOf(this), IrType::superTypes, IrType::isSuspendFunction)

fun IrType.isTypeParameter() = classifierOrNull is IrTypeParameterSymbol

fun IrType.isInterface() = classOrNull?.owner?.kind == ClassKind.INTERFACE

fun IrType.isExternalObject() = classOrNull?.owner.let { it?.kind == ClassKind.OBJECT && it.isExternal }

fun IrType.isAnnotation() = classOrNull?.owner?.kind == ClassKind.ANNOTATION_CLASS

fun IrType.isFunctionOrKFunction() = isFunction() || isKFunction()

fun IrType.isSuspendFunctionOrKFunction() = isSuspendFunction() || isKSuspendFunction()

fun IrType.isThrowable(): Boolean = isTypeFromKotlinPackage { name -> name.asString() == "Throwable" }

fun IrType.isUnsigned(): Boolean = isTypeFromKotlinPackage { name -> UnsignedTypes.isShortNameOfUnsignedType(name) }

fun IrType.isUnsignedArray(): Boolean = isTypeFromKotlinPackage { name -> UnsignedTypes.isShortNameOfUnsignedArray(name) }

private inline fun IrType.isTypeFromKotlinPackage(namePredicate: (Name) -> Boolean): Boolean {
  if (this is IrSimpleType) {
    val classClassifier = classifier as? IrClassSymbol ?: return false
    if (!namePredicate(classClassifier.owner.name)) return false
    val parent = classClassifier.owner.parent as? IrPackageFragment ?: return false
    return parent.packageFqName == kotlinPackageFqn
  } else return false
}

fun IrType.isPrimitiveArray() = isTypeFromKotlinPackage { it in FqNames.primitiveArrayTypeShortNames }

fun IrType.getPrimitiveArrayElementType() = (this as? IrSimpleType)?.let {
  (it.classifier.owner as? IrClass)?.fqNameWhenAvailable?.toUnsafe()?.let { fqn -> FqNames.arrayClassFqNameToPrimitiveType[fqn] }
}

fun IrType.substitute(params: List<IrTypeParameter>, arguments: List<IrType>): IrType =
  substitute(params.map { it.symbol }.zip(arguments).toMap())

fun IrType.substitute(substitutionMap: Map<IrTypeParameterSymbol, IrType>): IrType {
  if (this !is IrSimpleType || substitutionMap.isEmpty()) return this

  val newAnnotations = annotations.memoryOptimizedMap { it.deepCopyWithSymbols() }

  substitutionMap[classifier]?.let { substitutedType ->
    // Add nullability and annotations from original type
    return substitutedType
      .mergeNullability(this)
      .addAnnotations(newAnnotations)
  }

  val newArguments = arguments.memoryOptimizedMap {
    when (it) {
      is IrTypeProjection -> makeTypeProjection(it.type.substitute(substitutionMap), it.variance)
      is IrStarProjection -> it
    }
  }

  return IrSimpleTypeImpl(
    classifier,
    nullability,
    newArguments,
    newAnnotations
  )
}

fun IrType.isSubtypeOfClass(superClass: IrClassSymbol): Boolean =
  this is IrSimpleType && classifier.isSubtypeOfClass(superClass)

fun IrType.isStrictSubtypeOfClass(superClass: IrClassSymbol): Boolean =
  this is IrSimpleType && classifier.isStrictSubtypeOfClass(superClass)

fun IrType.isSubtypeOf(superType: IrType, typeSystem: IrTypeSystemContext): Boolean =
  AbstractTypeChecker.isSubtypeOf(createIrTypeCheckerState(typeSystem), this, superType)

fun IrType.isNullable(): Boolean =
  when (this) {
    is IrSimpleType -> when (val classifier = classifier) {
      is IrClassSymbol -> nullability == SimpleTypeNullability.MARKED_NULLABLE
      is IrTypeParameterSymbol -> when (nullability) {
        SimpleTypeNullability.MARKED_NULLABLE -> true
        // here is a bug, there should be .all check (not .any),
        // but fixing it is a breaking change, see KT-31545 for details
        SimpleTypeNullability.NOT_SPECIFIED -> classifier.owner.superTypes.any(IrType::isNullable)
        SimpleTypeNullability.DEFINITELY_NOT_NULL -> false
      }
      is IrScriptSymbol -> nullability == SimpleTypeNullability.MARKED_NULLABLE
    }
    is IrDynamicType -> true
    is IrErrorType -> this.isMarkedNullable
  }

val IrType.isBoxedArray: Boolean
  get() = classOrNull?.owner?.fqNameWhenAvailable == FqNames.array.toSafe()

fun IrType.getArrayElementType(irBuiltIns: IrBuiltIns): IrType =
  if (isBoxedArray) {
    when (val argument = (this as IrSimpleType).arguments.singleOrNull()) {
      is IrTypeProjection ->
        argument.type
      is IrStarProjection ->
        irBuiltIns.anyNType
      null ->
        error("Unexpected array argument type: null")
    }
  } else {
    val classifier = this.classOrNull!!
    irBuiltIns.primitiveArrayElementTypes[classifier]
      ?: irBuiltIns.unsignedArraysElementTypes[classifier]
      ?: throw AssertionError("Primitive array expected: $classifier")
  }

fun IrType.toArrayOrPrimitiveArrayType(irBuiltIns: IrBuiltIns): IrType =
  if (isPrimitiveType()) {
    irBuiltIns.primitiveArrayForType[this]?.defaultType
      ?: throw AssertionError("$this not in primitiveArrayForType")
  } else {
    irBuiltIns.arrayClass.typeWith(this)
  }

private fun getImmediateSupertypes(irType: IrSimpleType): List<IrSimpleType> {
  val irClass = irType.getClass()
    ?: throw AssertionError("Not a class type: ${irType.render()}")
  val originalSupertypes = irClass.superTypes
  val arguments =
    irType.arguments.map {
      it.typeOrNull
        ?: throw AssertionError("*-projection in supertype arguments: ${irType.render()}")
    }
  return originalSupertypes
    .filter { it.classOrNull != null }
    .memoryOptimizedMap { superType ->
      superType.substitute(irClass.typeParameters, arguments) as IrSimpleType
    }
}

private fun collectAllSupertypes(irType: IrSimpleType, result: MutableSet<IrSimpleType>) {
  val immediateSupertypes = getImmediateSupertypes(irType)
  result.addAll(immediateSupertypes)
  for (supertype in immediateSupertypes) {
    collectAllSupertypes(supertype, result)
  }
}

// Given the following classes:
//      open class A<X>
//      open class B<Y> : A<List<Y>>
//      class C<Z> : B<List<Z>>
// for the class C, this function constructs:
//      { B<List<Z>>, A<List<List<Z>>, Any }
// where Z is a type parameter of class C.
fun getAllSubstitutedSupertypes(irClass: IrClass): Set<IrSimpleType> {
  val result = HashSet<IrSimpleType>()
  collectAllSupertypes(irClass.defaultType, result)
  return result
}

private fun collectAllSuperclasses(irClass: IrClass, set: MutableSet<IrClass>) {
  for (superType in irClass.superTypes) {
    val classifier = superType.classifierOrNull as? IrClassSymbol ?: continue
    val superClass = classifier.owner
    if (set.add(superClass)) {
      collectAllSuperclasses(superClass, set)
    }
  }
}

fun IrClass.getAllSuperclasses(): Set<IrClass> {
  val result = HashSet<IrClass>()
  collectAllSuperclasses(this, result)
  return result
}

val IrType.isReifiedTypeParameter: Boolean
  get() = (classifierOrNull as? IrTypeParameterSymbol)?.owner?.isReified == true
