/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter.state

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.AbstractMap
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.interpreter.IrInterpreterEnvironment
import org.jetbrains.kotlin.ir.interpreter.fqName
import org.jetbrains.kotlin.ir.interpreter.getLastOverridden
import org.jetbrains.kotlin.ir.interpreter.getOnlyName
import org.jetbrains.kotlin.ir.interpreter.getPrimitiveClass
import org.jetbrains.kotlin.ir.interpreter.internalName
import org.jetbrains.kotlin.ir.interpreter.property
import org.jetbrains.kotlin.ir.interpreter.stack.Field
import org.jetbrains.kotlin.ir.interpreter.stack.Fields
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isCharSequence
import org.jetbrains.kotlin.ir.types.isComparable
import org.jetbrains.kotlin.ir.types.isIterable
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isNumber
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.isThrowable
import org.jetbrains.kotlin.ir.util.isTypeParameter
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly

internal class Wrapper(val value: Any, override val irClass: IrClass, environment: IrInterpreterEnvironment) : Complex {
  override val fields: Fields = mutableMapOf()

  override var superWrapperClass: Wrapper? = null
  override var outerClass: Field? = null

  private val receiverClass = irClass.defaultType.getClass(true)

  init {
    val javaClass = value::class.java
    when {
      javaClass == HashMap::class.java -> {
        val nodeClass = javaClass.declaredClasses.single { it.name.contains("\$Node") }
        val mutableMap = irClass.superTypes.mapNotNull { it.classOrNull?.owner }
          .single { it.name == StandardNames.FqNames.mutableMap.shortName() }
        environment.javaClassToIrClass += nodeClass to mutableMap.declarations.filterIsInstance<IrClass>().single()
      }
      javaClass == LinkedHashMap::class.java -> {
        val entryClass = javaClass.declaredClasses.single { it.name.contains("\$Entry") }
        val mutableMap = irClass.superTypes.mapNotNull { it.classOrNull?.owner }
          .single { it.name == StandardNames.FqNames.mutableMap.shortName() }
        environment.javaClassToIrClass += entryClass to mutableMap.declarations.filterIsInstance<IrClass>().single()
      }
      javaClass.canonicalName == "java.util.Collections.SingletonMap" -> {
        val irClassMapEntry = irClass.declarations.filterIsInstance<IrClass>().single()
        environment.javaClassToIrClass += AbstractMap.SimpleEntry::class.java to irClassMapEntry
        environment.javaClassToIrClass += AbstractMap.SimpleImmutableEntry::class.java to irClassMapEntry
      }
    }
    if (environment.javaClassToIrClass[value::class.java].let { it == null || irClass.isSubclassOf(it) }) {
      // second condition guarantees that implementation class will not be replaced with its interface
      // for example: map will store ArrayList instead of just List
      // this is needed for parallel calculations
      environment.javaClassToIrClass[value::class.java] = irClass
    }
  }

  override fun getIrFunctionByIrCall(expression: IrCall): IrFunction? = null

  fun getMethod(irFunction: IrFunction): MethodHandle? {
    // if function is actually a getter, then use "get${property.name.capitalize()}" as method name
    val propertyName = irFunction.property?.name?.asString()
    val propertyCall = listOfNotNull(propertyName, "get${propertyName?.capitalizeAsciiOnly()}")
      .firstOrNull { receiverClass.methods.any { method -> method.name == it } }

    val intrinsicName = getJavaOriginalName(irFunction)
    val methodName = intrinsicName ?: propertyCall ?: irFunction.name.toString()
    val methodType = irFunction.getMethodType()
    return MethodHandles.lookup().findVirtual(receiverClass, methodName, methodType)
  }

  // This method is used to get correct java method name
  private fun getJavaOriginalName(irFunction: IrFunction): String? {
    return when (irFunction.getLastOverridden().fqName) {
      "kotlin.collections.Map.<get-entries>" -> "entrySet"
      "kotlin.collections.Map.<get-keys>" -> "keySet"
      "kotlin.CharSequence.get" -> "charAt"
      "kotlin.collections.MutableList.removeAt" -> "remove"
      else -> null
    }
  }

  override fun toString(): String {
    return value.toString()
  }

  companion object {
    private val companionObjectValue = mapOf<String, Any>("kotlin.text.Regex\$Companion" to Regex.Companion)

    // TODO remove later; used for tests only
    private val intrinsicClasses = setOf(
      "kotlin.text.StringBuilder", "kotlin.Pair", "kotlin.collections.ArrayList",
      "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
      "kotlin.collections.HashSet", "kotlin.collections.LinkedHashSet",
      "kotlin.text.RegexOption", "kotlin.text.Regex", "kotlin.text.Regex.Companion", "kotlin.text.MatchGroup",
    )

    private val intrinsicJavaClasses = setOf(
      "java.lang.StringBuilder", "java.util.ArrayList",
      "java.util.LinkedHashMap", "java.util.LinkedHashSet",
      "java.lang.Exception", "java.util.NoSuchElementException", "java.lang.NullPointerException",
      "java.lang.IllegalArgumentException", "java.lang.ArithmeticException", "java.lang.UnsupportedOperationException",
    )

    private val intrinsicFunctionToHandler = mapOf(
      "Array.kotlin.collections.asList()" to "kotlin.collections.ArraysKt",
      "kotlin.collections.mutableListOf(Array)" to "kotlin.collections.CollectionsKt",
      "kotlin.collections.arrayListOf(Array)" to "kotlin.collections.CollectionsKt",
      "Char.kotlin.text.isWhitespace()" to "kotlin.text.CharsKt",
      "Array.kotlin.collections.toMutableList()" to "kotlin.collections.ArraysKt",
      "Array.kotlin.collections.copyToArrayOfAny(Boolean)" to "kotlin.collections.CollectionsKt",
    )

    private val ranges = setOf("kotlin.ranges.CharRange", "kotlin.ranges.IntRange", "kotlin.ranges.LongRange")

    private fun IrFunction.getSignature(fqName: String = this.fqName): String {
      val (receiverParameters, otherParameters) = parameters
        .partition { it.kind == IrParameterKind.DispatchReceiver || it.kind == IrParameterKind.ExtensionReceiver }
      val receiver = receiverParameters.firstOrNull()?.type?.getOnlyName()?.let { "$it." } ?: ""
      return otherParameters.joinToString(prefix = "$receiver$fqName(", postfix = ")") { it.type.getOnlyName() }
    }

    private fun IrFunction.getJvmClassName(): String? {
      return intrinsicFunctionToHandler[this.getSignature()]
    }

    fun mustBeHandledWithWrapper(declaration: IrDeclarationWithName): Boolean {
      val fqName = declaration.fqName
      return when {
        declaration is IrFunction -> declaration.getSignature(fqName) in intrinsicFunctionToHandler
        fqName in ranges && (declaration as IrClass).primaryConstructor?.body == null -> true
        else -> fqName in intrinsicClasses || fqName in intrinsicJavaClasses
      }
    }

    fun getReflectionMethod(irFunction: IrFunction): MethodHandle {
      val receiverClass = irFunction.dispatchReceiverParameter!!.type.getClass(asObject = true)
      val methodType = irFunction.getMethodType()
      val methodName = when (irFunction) {
        is IrSimpleFunction -> {
          val property = irFunction.property
          when {
            property?.getter == irFunction -> "get${property.name.asString().capitalizeAsciiOnly()}"
            property?.setter == irFunction -> "set${property.name.asString().capitalizeAsciiOnly()}"
            else -> irFunction.name.asString()
          }
        }
        is IrConstructor -> irFunction.name.asString()
      }
      return MethodHandles.lookup().findVirtual(receiverClass, methodName, methodType)
    }

    fun getCompanionObject(irClass: IrClass, environment: IrInterpreterEnvironment): Wrapper {
      val objectName = irClass.internalName()
      val objectValue = companionObjectValue[objectName] ?: throw InternalError("Companion object $objectName cannot be interpreted")
      return Wrapper(objectValue, irClass, environment)
    }

    fun getConstructorMethod(irConstructor: IrFunction): MethodHandle? {
      val intrinsicValue = irConstructor.parentAsClass.internalName()
      if (intrinsicValue == "kotlin.Char" || intrinsicValue == "kotlin.Long") return null // used in JS, must be handled as intrinsics

      val methodType = irConstructor.getMethodType()
      return MethodHandles.lookup().findConstructor(irConstructor.returnType.getClass(true), methodType)
    }

    fun getStaticMethod(irFunction: IrFunction): MethodHandle? {
      val intrinsicName = irFunction.getJvmClassName()
      if (intrinsicName?.isEmpty() != false) return null
      val jvmClass = Class.forName(intrinsicName)

      val methodType = irFunction.getMethodType()
      return MethodHandles.lookup().findStatic(jvmClass, irFunction.name.asString(), methodType)
    }

    fun getStaticGetter(field: IrField): MethodHandle {
      val jvmClass = field.parentAsClass.defaultType.getClass(true)
      val returnType = field.type.let { it.getClass(it.isNullable()) }
      return MethodHandles.lookup().findStaticGetter(jvmClass, field.name.asString(), returnType)
    }

    fun getEnumEntry(irEnumClass: IrClass): MethodHandle {
      val intrinsicName = irEnumClass.internalName()
      val jvmEnumClass = Class.forName(intrinsicName)

      val methodType = MethodType.methodType(jvmEnumClass, String::class.java)
      return MethodHandles.lookup().findStatic(jvmEnumClass, StandardNames.ENUM_VALUE_OF.identifier, methodType)
    }

    private fun IrFunction.getMethodType(): MethodType {
      val parameterClasses = this.nonDispatchParameters.map { it.type.getClass(this.isValueParameterPrimitiveAsObject(it.indexInParameters)) }
      return when (this) {
        is IrSimpleFunction -> {
          val returnClass = this.returnType.getClass(this.isReturnTypePrimitiveAsObject())
          MethodType.methodType(returnClass, parameterClasses)
        }
        is IrConstructor -> {
          MethodType.methodType(Void::class.javaPrimitiveType, parameterClasses)
        }
      }
    }

    private fun Int?.getCorrespondingFunction(): Class<*> {
      return when {
        this == null || this >= BuiltInFunctionArity.BIG_ARITY -> Class.forName("kotlin.jvm.functions.FunctionN")
        else -> Class.forName("kotlin.jvm.functions.Function$this")
      }
    }

    private fun IrType.getClass(asObject: Boolean): Class<out Any> {
      val owner = this.classOrNull?.owner
      val fqName = owner?.fqName
      val notNullType = this.makeNotNull()
      //TODO check if primitive array is possible here
      return when {
        notNullType.isPrimitiveType() || notNullType.isString() -> getPrimitiveClass(notNullType, asObject)!!
        notNullType.isArray() -> {
          val argumentFqName = (this as IrSimpleType).arguments.single().typeOrNull?.classOrNull?.owner?.fqName
          when {
            argumentFqName != null && argumentFqName != "kotlin.Any" -> argumentFqName.let { Class.forName("[L$it;") }
            else -> Array<Any?>::class.java
          }
        }
        notNullType.isNothing() -> Nothing::class.java
        notNullType.isAny() -> Any::class.java
        notNullType.isUnit() -> if (asObject) Void::class.javaObjectType else Void::class.javaPrimitiveType!!
        notNullType.isNumber() -> Number::class.java
        notNullType.isCharSequence() -> CharSequence::class.java
        notNullType.isComparable() -> Comparable::class.java
        notNullType.isThrowable() -> Throwable::class.java
        notNullType.isIterable() -> Iterable::class.java

        notNullType.isKFunction() || notNullType.isKSuspendFunction() -> Class.forName("kotlin.reflect.KFunction")
        notNullType.isFunction() -> {
          val arity = fqName?.removePrefix("kotlin.Function")?.toIntOrNull()
          return arity.getCorrespondingFunction()
        }
        notNullType.isSuspendFunction() -> error("Interpretation of $fqName is not supported")

        fqName == "kotlin.Enum" -> Enum::class.java
        fqName == "kotlin.collections.Collection" || fqName == "kotlin.collections.MutableCollection" -> Collection::class.java
        fqName == "kotlin.collections.List" || fqName == "kotlin.collections.MutableList" -> List::class.java
        fqName == "kotlin.collections.Set" || fqName == "kotlin.collections.MutableSet" -> Set::class.java
        fqName == "kotlin.collections.Map" || fqName == "kotlin.collections.MutableMap" -> Map::class.java
        fqName == "kotlin.collections.ListIterator" || fqName == "kotlin.collections.MutableListIterator" -> ListIterator::class.java
        fqName == "kotlin.collections.Iterator" || fqName == "kotlin.collections.MutableIterator" -> Iterator::class.java
        fqName == "kotlin.collections.Map.Entry" || fqName == "kotlin.collections.MutableMap.MutableEntry" -> Map.Entry::class.java
        fqName == "kotlin.collections.ArrayList" -> ArrayList::class.java
        fqName == "kotlin.collections.HashMap" -> HashMap::class.java
        fqName == "kotlin.collections.HashSet" -> HashSet::class.java
        fqName == "kotlin.collections.LinkedHashMap" -> LinkedHashMap::class.java
        fqName == "kotlin.collections.LinkedHashSet" -> LinkedHashSet::class.java
        fqName == "kotlin.text.StringBuilder" -> StringBuilder::class.java
        fqName == "kotlin.text.Appendable" -> Appendable::class.java
        fqName == null -> Any::class.java // null if this.isTypeParameter()
        else -> Class.forName(owner.internalName())
      }
    }

    private fun IrFunction.getOriginalOverriddenSymbols(): MutableList<IrFunctionSymbol> {
      val overriddenSymbols = mutableListOf<IrFunctionSymbol>()
      if (this is IrSimpleFunction) {
        val pool = this.overriddenSymbols.toMutableList()
        val iterator = pool.listIterator()
        for (symbol in iterator) {
          if (symbol.owner.overriddenSymbols.isEmpty()) {
            overriddenSymbols += symbol
            iterator.remove()
          } else {
            symbol.owner.overriddenSymbols.forEach { iterator.add(it) }
          }
        }
      }

      if (overriddenSymbols.isEmpty()) overriddenSymbols.add(this.symbol)
      return overriddenSymbols
    }

    private fun IrFunction.isReturnTypePrimitiveAsObject(): Boolean {
      for (symbol in getOriginalOverriddenSymbols()) {
        if (!symbol.owner.returnType.isTypeParameter() && !symbol.owner.returnType.isNullable()) {
          return false
        }
      }
      return true
    }

    private fun IrFunction.isValueParameterPrimitiveAsObject(index: Int): Boolean {
      for (symbol in getOriginalOverriddenSymbols()) {
        if (!symbol.owner.parameters[index].type.isTypeParameter() && !symbol.owner.parameters[index].type.isNullable()) {
          return false
        }
      }
      return true
    }
  }
}
