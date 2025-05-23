/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.caches.StubsForCollectionClass
import org.jetbrains.kotlin.backend.jvm.ir.isJvmInterface
import org.jetbrains.kotlin.backend.jvm.overridesWithoutStubs
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextWithAdditionalAxioms
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.createIrTypeCheckerState
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAllSubstitutedSupertypes
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.TypeCheckerState

/**
 * Generates exception-throwing stubs for methods from mutable collection classes not implemented in Kotlin classes which inherit only from
 * Kotlin's read-only collections. This is required on JVM because Kotlin's read-only collections are mapped to mutable JDK collections.
 *
 * For example:
 *
 *     class C<T> : Collection<String>
 *
 * In the bytecode, `C` will have implementations of all mutating methods (`add`, `remove`, `clear`, ...) which throw
 * `java.lang.UnsupportedOperationException` with the message "Operation is not supported for read-only collection".
 */
@PhaseDescription(name = "CollectionStubMethod")
internal class CollectionStubMethodLowering(val context: JvmBackendContext) : ClassLoweringPass {
  private val collectionStubComputer = context.collectionStubComputer

  private data class NameAndArity(
    val name: Name,
    val typeParametersCount: Int,
    val valueParametersCount: Int,
  )

  private val IrSimpleFunction.nameAndArity
    get() = NameAndArity(name, typeParameters.size, valueParameters.size)

  override fun lower(irClass: IrClass) {
    if (irClass.isInterface) {
      return
    }

    val methodStubsToGenerate = generateRelevantStubMethods(irClass)
    if (methodStubsToGenerate.isEmpty()) return

    // We don't need to generate stub for existing methods, but for FAKE_OVERRIDE methods with ABSTRACT modality,
    // it means an abstract function in superclass that is not implemented yet,
    // stub generation is still needed to avoid invocation error.
    val (abstractMethods, nonAbstractMethods) = irClass.functions.partition { it.modality == Modality.ABSTRACT && it.isFakeOverride }
    val nonAbstractMethodsByNameAndArity = nonAbstractMethods.groupBy { it.nameAndArity }
    val abstractMethodsByNameAndArity = abstractMethods.groupBy { it.nameAndArity }

    for (stub in methodStubsToGenerate) {
      val stubNameAndArity = stub.nameAndArity
      val relevantMembers = nonAbstractMethodsByNameAndArity[stubNameAndArity].orEmpty()
      val existingOverrides = relevantMembers.filter { isEffectivelyOverriddenBy(stub, it) }

      if (existingOverrides.isNotEmpty()) {
        existingOverrides.forEach {
          // In the case that we find a defined method that matches the stub signature,
          // we add the overridden symbols to that defined method,
          // so that bridge lowering can still generate correct bridge for that method.
          // However, we still need to keep track of the original overrides
          // so that special built-in signature mapping doesn't confuse it with a method
          // that actually requires signature patching.
          it.overridesWithoutStubs = it.overriddenSymbols.toList()
          it.overriddenSymbols += stub.overriddenSymbols
        }
        // We don't add a throwing stub if it's effectively overridden by an existing function.
        continue
      }

      // Generated stub might still override some abstract member(s), which affects resulting method signature.
      val overriddenAbstractMethods = abstractMethodsByNameAndArity[stubNameAndArity].orEmpty()
        .filter { isEffectivelyOverriddenBy(it, stub) }
      stub.overriddenSymbols += overriddenAbstractMethods.map { it.symbol }

      // Some stub members require special handling.
      // In both 'remove' and 'removeAt' cases there are no other member functions with same name in built-in mutable collection
      // classes, so it's safe to check for the member name itself.
      when (stub.name.asString()) {
        "remove" -> {
          //  - 'remove' member functions:
          //          kotlin.collections.MutableCollection<E>#remove(E): Boolean
          //          kotlin.collections.MutableMap<K, V>#remove(K): V?
          //          kotlin.collections.MutableMap<K, V>#remove(key: K, value: V): Boolean
          //      We've checked that corresponding 'remove(T)' member function is not present in the class.
          //      We should add a member function that overrides, respectively:
          //          java.util.Collection<E>#remove(Object): boolean
          //          java.util.Map<K, V>#remove(K): V
          //      This corresponds to replacing value parameter types with 'Any?'.

          //      Here we manually filter out remove(key: K, value: V) method.
          //      We have to reproduce old behavior to avoid introducing breaking changes until it's approved by LC.
          //      As soon as changes that fix KT-72496 are approved, this must be dropped.
          if (context.config.languageVersionSettings.getFlag(AnalysisFlags.stdlibCompilation)
            && irClass.classId == StandardClassIds.AbstractMap
            && irClass.typeParameters.size == 2
            && stub.valueParameters.size == 2
            && stub.valueParameters[0].type.classifierOrNull == irClass.typeParameters[0].symbol
            && stub.valueParameters[1].type.classifierOrNull == irClass.typeParameters[1].symbol
          ) {
            continue
          }
          irClass.declarations.add(stub.apply {
            valueParameters = valueParameters.map {
              it.copyWithCustomTypeSubstitution(this) { context.irBuiltIns.anyNType }
            }
          })
        }
        "removeAt" -> {
          //  - 'removeAt' member function:
          //          kotlin.collections.MutableList<E>#removeAt(Int): E
          //      We've checked that corresponding 'removeAt(Int)' member function is not present in the class
          //      (if it IS present, special bridges for 'remove(I)' would be generated later in BridgeLowering).
          //      We can't add 'removeAt' here, because it would be different from what old back-end generates
          //      and can break existing Java and/or Kotlin code.
          //      We should add a member function that overrides
          //          java.util.List<E>#remove(int): E
          //      and throws UnsupportedOperationException, just like any other stub.
          //      Also, we should generate a bridge for it if required.
          val removeIntFun = createRemoveAtStub(stub, stub.returnType, IrDeclarationOrigin.IR_BUILTINS_STUB)
          irClass.declarations.add(removeIntFun)
          val removeIntBridgeFun = createRemoveAtStub(stub, context.irBuiltIns.anyNType, IrDeclarationOrigin.BRIDGE)
          if (removeIntBridgeFun.toJvmSignature() != removeIntFun.toJvmSignature()) {
            irClass.declarations.add(removeIntBridgeFun)
          }
        }
        else ->
          irClass.declarations.add(stub)
      }
    }
  }

  private fun createRemoveAtStub(
    removeAtStub: IrSimpleFunction,
    stubReturnType: IrType,
    stubOrigin: IrDeclarationOrigin,
  ): IrSimpleFunction {
    return context.irFactory.buildFun {
      name = Name.identifier("remove")
      returnType = stubReturnType
      visibility = removeAtStub.visibility
      origin = stubOrigin
      modality = Modality.OPEN
    }.apply {
      // NB stub method for 'remove(int)' doesn't override any built-in Kotlin declaration
      parent = removeAtStub.parent
      dispatchReceiverParameter = removeAtStub.dispatchReceiverParameter?.copyWithCustomTypeSubstitution(this) { it }
      extensionReceiverParameter = null
      valueParameters = removeAtStub.valueParameters.map { stubParameter ->
        stubParameter.copyWithCustomTypeSubstitution(this) { it }
      }
      body = createThrowingStubBody(context, this)
    }
  }

  private fun IrSimpleFunction.toJvmSignature(): String =
    context.defaultMethodSignatureMapper.mapAsmMethod(this).toString()

  private fun createStubMethod(
    function: IrSimpleFunction,
    irClass: IrClass,
    substitutionMap: Map<IrTypeParameterSymbol, IrType>,
  ): IrSimpleFunction {
    return context.irFactory.buildFun {
      name = function.name
      returnType = liftStubMethodReturnType(function).substitute(substitutionMap)
      visibility = function.visibility
      origin = IrDeclarationOrigin.IR_BUILTINS_STUB
      modality = Modality.OPEN
    }.apply {
      // Replace Function metadata with the data from class
      // Add the abstract function symbol to stub function for bridge lowering
      overriddenSymbols = listOf(function.symbol)
      parent = irClass
      dispatchReceiverParameter = function.dispatchReceiverParameter?.copyWithSubstitution(this, substitutionMap)
      extensionReceiverParameter = function.extensionReceiverParameter?.copyWithSubstitution(this, substitutionMap)
      valueParameters = function.valueParameters.map { it.copyWithSubstitution(this, substitutionMap) }
      body = createThrowingStubBody(context, this)
    }
  }

  private fun liftStubMethodReturnType(function: IrSimpleFunction): IrType {
    val klass = when (function.name.asString()) {
      "iterator" -> context.ir.symbols.iterator
      "listIterator" -> context.ir.symbols.listIterator
      "subList" -> context.ir.symbols.list
      else -> return function.returnType
    }
    return klass.typeWithArguments((function.returnType as IrSimpleType).arguments)
  }

  private fun isEffectivelyOverriddenBy(superFun: IrSimpleFunction, overridingFun: IrSimpleFunction): Boolean {
    // Function 'f0' is overridden by function 'f1' if all the following conditions are met,
    // assuming type parameter Ti of 'f1' is "equal" to type parameter Si of 'f0':
    //  - names are same;
    //  - 'f1' has the same number of type parameters,
    //    and upper bounds for type parameters are equivalent;
    //  - 'f1' has the same number of value parameters,
    //    and types for value parameters are equivalent;
    //  - 'f1' return type is a subtype of 'f0' return type.

    if (superFun.name != overridingFun.name) return false
    if (superFun.typeParameters.size != overridingFun.typeParameters.size) return false
    if (superFun.valueParameters.size != overridingFun.valueParameters.size) return false
    if (!superFun.isSuspend && overridingFun.isSuspend) return false

    val typeChecker = createTypeCheckerState(superFun, overridingFun)

    // Note that type parameters equivalence check doesn't really happen on collection stubs
    // (because members of Kotlin built-in collection classes don't have type parameters of their own),
    // but we keep it here for the sake of consistency.
    if (!areTypeParametersEquivalent(overridingFun, superFun, typeChecker)) return false

    if (!areValueParametersEquivalent(overridingFun, superFun, typeChecker)) return false
    if (!isReturnTypeOverrideCompliant(overridingFun, superFun, typeChecker)) return false

    return true
  }

  private fun createTypeCheckerState(overrideFun: IrSimpleFunction, parentFun: IrSimpleFunction): TypeCheckerState =
    createIrTypeCheckerState(
      IrTypeSystemContextWithAdditionalAxioms(
        context.typeSystem,
        overrideFun.typeParameters,
        parentFun.typeParameters
      )
    )

  private fun areTypeParametersEquivalent(
    overrideFun: IrSimpleFunction,
    parentFun: IrSimpleFunction,
    typeChecker: TypeCheckerState,
  ): Boolean =
    overrideFun.typeParameters.zip(parentFun.typeParameters)
      .all { (typeParameter1, typeParameter2) ->
        typeParameter1.superTypes.zip(typeParameter2.superTypes)
          .all { (supertype1, supertype2) ->
            AbstractTypeChecker.equalTypes(typeChecker, supertype1, supertype2)
          }
      }

  private fun areValueParametersEquivalent(
    overrideFun: IrSimpleFunction,
    parentFun: IrSimpleFunction,
    typeChecker: TypeCheckerState,
  ): Boolean =
    overrideFun.valueParameters.zip(parentFun.valueParameters)
      .all { (valueParameter1, valueParameter2) ->
        AbstractTypeChecker.equalTypes(typeChecker, valueParameter1.type, valueParameter2.type)
      }

  internal fun isReturnTypeOverrideCompliant(
    overrideFun: IrSimpleFunction,
    parentFun: IrSimpleFunction,
    typeChecker: TypeCheckerState,
  ): Boolean =
    AbstractTypeChecker.isSubtypeOf(typeChecker, overrideFun.returnType, parentFun.returnType)

  // Copy value parameter with type substitution
  private fun IrValueParameter.copyWithSubstitution(
    target: IrSimpleFunction,
    substitutionMap: Map<IrTypeParameterSymbol, IrType>,
  ): IrValueParameter =
    copyWithCustomTypeSubstitution(target) { it.substitute(substitutionMap) }

  private fun IrValueParameter.copyWithCustomTypeSubstitution(
    target: IrSimpleFunction,
    substituteType: (IrType) -> IrType,
  ): IrValueParameter {
    val parameter = this
    return buildValueParameter(target) {
      origin = IrDeclarationOrigin.IR_BUILTINS_STUB
      name = parameter.name
      type = substituteType(parameter.type)
      varargElementType = parameter.varargElementType?.let { substituteType(it) }
      isCrossInline = parameter.isCrossinline
      isNoinline = parameter.isNoinline
    }
  }

  // Compute a substitution map for type parameters between source class (Mutable Collection classes) to
  // target class (class currently in lowering phase), this map is later used for substituting type parameters in generated functions
  private fun computeSubstitutionMap(readOnlyClass: IrClass, mutableClass: IrClass, targetClass: IrClass)
    : Map<IrTypeParameterSymbol, IrType> {
    // We find the most specific type for the immutable collection class from the inheritance chain of target class
    // Perform type substitution along searching, then use the type arguments obtained from the most specific type
    // for type substitution.
    val readOnlyClassType = getAllSubstitutedSupertypes(targetClass).findMostSpecificTypeForClass(readOnlyClass.symbol)
    val readOnlyClassTypeArguments = (readOnlyClassType as IrSimpleType).arguments.mapNotNull { (it as? IrTypeProjection)?.type }

    if (readOnlyClassTypeArguments.isEmpty() || readOnlyClassTypeArguments.size != mutableClass.typeParameters.size) {
      throw IllegalStateException(
        "Type argument mismatch between immutable class ${readOnlyClass.fqNameWhenAvailable}" +
          " and mutable class ${mutableClass.fqNameWhenAvailable}, when processing" +
          "class ${targetClass.fqNameWhenAvailable}"
      )
    }
    return mutableClass.typeParameters.map { it.symbol }.zip(readOnlyClassTypeArguments).toMap()
  }

  // Compute stubs that should be generated, compare based on signature
  private fun generateRelevantStubMethods(irClass: IrClass): List<IrSimpleFunction> {
    val classStubFuns = collectionStubComputer.stubsForCollectionClasses(irClass)
      .flatMap { it.createStubFuns(irClass) }
    if (classStubFuns.isEmpty()) return classStubFuns

    val alreadyPresent = computeStubsForSuperClasses(irClass)
      .flatMap { it.createStubFuns(irClass) }
      .mapTo(HashSet()) { it.toJvmSignature() }

    return classStubFuns.filter { alreadyPresent.add(it.toJvmSignature()) }
  }

  private fun StubsForCollectionClass.createStubFuns(irClass: IrClass): List<IrSimpleFunction> {
    val substitutionMap = computeSubstitutionMap(readOnlyClass.owner, mutableClass.owner, irClass)
    return candidatesForStubs.map { function ->
      createStubMethod(function, irClass, substitutionMap)
    }
  }

  private fun computeStubsForSuperClasses(irClass: IrClass): List<StubsForCollectionClass> {
    val immediateSuperClass = irClass.superClass ?: return emptyList()
    return immediateSuperClass.superClassChain
      .flatMap { superClass -> computeStubsForSuperClass(superClass) }
      .toList()
  }

  private class FilteredStubsForCollectionClass(
    override val readOnlyClass: IrClassSymbol,
    override val mutableClass: IrClassSymbol,
    override val candidatesForStubs: Collection<IrSimpleFunction>,
  ) : StubsForCollectionClass

  private fun computeStubsForSuperClass(superClass: IrClass): List<StubsForCollectionClass> {
    val superClassStubs = collectionStubComputer.stubsForCollectionClasses(superClass)

    if (superClassStubs.isEmpty() || superClass.modality != Modality.ABSTRACT) return superClassStubs

    // An abstract superclass might contain an abstract function declaration that effectively overrides a stub to be generated,
    // and thus have no non-abstract stub.
    // This calculation happens for each abstract class multiple times. TODO memoize.

    val abstractFunsByNameAndArity = superClass.functions
      .filter { !it.isFakeOverride && it.modality == Modality.ABSTRACT }
      .groupBy { it.nameAndArity }

    if (abstractFunsByNameAndArity.isEmpty()) return superClassStubs

    return superClassStubs.map {
      // NB here we should build a stub in substitution context of the given superclass.
      // Resulting stub can be different from a stub created in substitution context of the "current" class
      // in case of (partially) specialized generic superclass.
      val substitutionMap = computeSubstitutionMap(it.readOnlyClass.owner, it.mutableClass.owner, superClass)
      val filteredCandidates = it.candidatesForStubs.filter { candidateFun ->
        val stubMethod = createStubMethod(candidateFun, superClass, substitutionMap)
        val stubNameAndArity = stubMethod.nameAndArity
        abstractFunsByNameAndArity[stubNameAndArity].orEmpty().none { abstractFun ->
          isEffectivelyOverriddenBy(stubMethod, abstractFun)
        }
      }
      FilteredStubsForCollectionClass(it.readOnlyClass, it.mutableClass, filteredCandidates)
    }
  }

  private fun Collection<IrType>.findMostSpecificTypeForClass(classifier: IrClassSymbol): IrType {
    val types = this.filter { it.classifierOrNull == classifier }
    if (types.isEmpty()) error("No supertype of $classifier in $this")
    if (types.size == 1) return types.first()
    // Find the first type in the list such that it's a subtype of every other type in that list
    return types.first { type ->
      types.all { other -> type.isSubtypeOfClass(other.classOrNull!!) }
    }
  }

  private val IrClass.superClass: IrClass?
    get() = superTypes.mapNotNull { it.getClass() }.singleOrNull { !it.isJvmInterface }

  private val IrClass.superClassChain: Sequence<IrClass>
    get() = generateSequence(this) { it.superClass }
}


fun createThrowingStubBody(context: JvmBackendContext, function: IrSimpleFunction) =
  context.createIrBuilder(function.symbol).irBlockBody {
    // Function body consist only of throwing UnsupportedOperationException statement
    +irCall(context.ir.symbols.throwUnsupportedOperationException)
      .apply {
        putValueArgument(0, irString("Operation is not supported for read-only collection"))
      }
  }
