/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallOp
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenReturnFalse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotIs
import org.jetbrains.kotlin.ir.builders.irReturnTrue
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isBoxedArray
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isUnsignedArray
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.filterIsInstanceAnd

val ANNOTATION_IMPLEMENTATION by IrDeclarationOriginImpl.Synthetic

/**
 * Creates synthetic annotations implementations and uses them in annotations constructor calls.
 *
 * For example:
 *
 *     annotation class A(val value: String)
 *     fun f(): A = A("")
 *
 * becomes
 *
 *     annotation class A(val value: String)
 *     fun f(): A = annotationImpl$A$0("")
 *
 *     class annotationImpl$A$0(override val value: String) : A {
 *         override fun equals(other: Any?): Boolean = ...
 *         override fun hashCode(): Int = ...
 *         override fun toString(): String = ...
 *         fun annotationType(): Class<*> = A::class.java // (JVM-only)
 *     }
 */
open class AnnotationImplementationLowering(
  val transformer: (IrFile) -> AnnotationImplementationTransformer,
) : FileLoweringPass {
  override fun lower(irFile: IrFile) {
    val tf = transformer(irFile)
    irFile.transformChildrenVoid(tf)
    tf.implementations.values.forEach {
      val parentClass = it.parent as IrDeclarationContainer
      parentClass.declarations += it
    }
  }
}

abstract class AnnotationImplementationTransformer(val context: CommonBackendContext, val symbolTable: ReferenceSymbolTable, val irFile: IrFile?) :
  IrElementTransformerVoidWithContext() {
  internal val implementations: MutableMap<IrClass, IrClass> = mutableMapOf()


  override fun visitClassNew(declaration: IrClass): IrStatement {
    declaration.addConstructorBodyForCompatibility()
    return super.visitClassNew(declaration)
  }

  protected fun IrClass.addConstructorBodyForCompatibility() {
    if (!isAnnotationClass) return
    val primaryConstructor = constructors.singleOrNull() ?: return

    if (primaryConstructor.body != null) return
    // Compatibility hack. Now, frontend generates constructor body for annotations and makes them open
    // but, if one gets annotation from pre-1.6.20 klib, it would have no constructor body and would be final,
    // so we need to fix it
    modality = Modality.OPEN
    primaryConstructor.body = context.createIrBuilder(symbol)
      .irBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET) {
        +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
        +IrInstanceInitializerCallImpl(
          startOffset,
          endOffset,
          this@addConstructorBodyForCompatibility.symbol,
          context.irBuiltIns.unitType
        )
      }
  }

  abstract fun chooseConstructor(implClass: IrClass, expression: IrConstructorCall): IrConstructor

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val constructedClass = expression.type.classOrNull?.owner ?: return super.visitConstructorCall(expression)
    if (!constructedClass.isAnnotationClass) return super.visitConstructorCall(expression)
    require(expression.symbol.owner.isPrimary) { "Non-primary constructors of annotations are not supported" }

    val implClass = implementations.getOrPut(constructedClass) { createAnnotationImplementation(constructedClass) }
    val ctor = chooseConstructor(implClass, expression)
    val newCall = IrConstructorCallImpl.fromSymbolOwner(
      expression.startOffset,
      expression.endOffset,
      implClass.defaultType,
      ctor.symbol,
    )
    moveValueArgumentsUsingNames(expression, newCall)
    newCall.transformChildrenVoid() // for annotations in annotations
    return newCall
  }

  open fun IrClass.platformSetup() {}

  private fun moveValueArgumentsUsingNames(source: IrConstructorCall, destination: IrConstructorCall) {
    val argumentsByName = source.getArgumentsWithIr().associateBy(
      { (param, _) -> param.name },
      { (_, value) -> value }
    )

    destination.symbol.owner.parameters.forEach { parameter ->
      val valueArg = argumentsByName[parameter.name]

      if (parameter.defaultValue == null && valueArg == null) {
        // if parameter is vararg, put an empty array as argument
        // The vararg is already lowered to array, so `isVararg` is false.
        if (parameter.type.isBoxedArray || parameter.type.isPrimitiveArray() || parameter.type.isUnsignedArray()) {
          val arrayType = parameter.type

          val arrayConstructorCall =
            if (arrayType.isBoxedArray) {
              val arrayFunction = context.ir.symbols.arrayOfNulls
              IrCallImpl.fromSymbolOwner(source.startOffset, source.endOffset, arrayType, arrayFunction)
            } else {
              val arrayConstructor = arrayType.classOrNull!!.constructors.single {
                it.owner.hasShape(regularParameters = 1, parameterTypes = listOf(context.irBuiltIns.intType))
              }
              IrConstructorCallImpl.fromSymbolOwner(source.startOffset, source.endOffset, arrayType, arrayConstructor)
            }
          arrayConstructorCall.arguments[0] = IrConstImpl.int(source.startOffset, source.endOffset, context.irBuiltIns.intType, 0)
          destination.arguments[parameter.indexInParameters] = arrayConstructorCall
          return
        } else {
          error(
            "Usage of default value argument for this annotation is not yet possible.\n" +
              "Please specify value for '${source.type.classOrNull?.owner?.name}.${parameter.name}' explicitly"
          )
        }
      }
      destination.arguments[parameter.indexInParameters] = valueArg
    }
  }

  private fun createAnnotationImplementation(annotationClass: IrClass): IrClass {
    val localDeclarationParent = currentClass?.scope?.getLocalDeclarationParent() as? IrClass
    val parentFqName = annotationClass.fqNameWhenAvailable!!.asString().replace('.', '_')
    val wrapperName = Name.identifier("annotationImpl\$$parentFqName$0")
    val subclass = context.irFactory.buildClass {
      startOffset = SYNTHETIC_OFFSET
      endOffset = SYNTHETIC_OFFSET
      name = wrapperName
      origin = ANNOTATION_IMPLEMENTATION
      // It can be seen from inline functions and multiple classes within one file
      // JavaDescriptorVisibilities.PACKAGE_VISIBILITY also can be used here, like in SAM, but that's not a big difference
      // since declaration is synthetic anyway
      visibility = DescriptorVisibilities.INTERNAL
    }.apply {
      parent = localDeclarationParent ?: irFile
        ?: error("irFile in transformer should be specified when creating synthetic implementation")
      createThisReceiverParameter()
      superTypes = listOf(annotationClass.defaultType)
      platformSetup()
      // Type parameters can be copied from annotationClass, but in fact they are never used by any of the backends.
    }

    val ctor = subclass.addConstructor {
      startOffset = SYNTHETIC_OFFSET
      endOffset = SYNTHETIC_OFFSET
      visibility = DescriptorVisibilities.PUBLIC
    }
    implementAnnotationPropertiesAndConstructor(subclass, annotationClass, ctor)
    implementGeneratedFunctions(annotationClass, subclass)
    implementPlatformSpecificParts(annotationClass, subclass)
    return subclass
  }

  abstract fun implementAnnotationPropertiesAndConstructor(
    implClass: IrClass,
    annotationClass: IrClass,
    generatedConstructor: IrConstructor,
  )

  fun IrClass.getAnnotationProperties(): List<IrProperty> {
    // For some weird reason, annotations defined in other IrFiles, do not have IrProperties in declarations.
    // (although annotations imported from Java do have)
    val props = declarations.filterIsInstance<IrProperty>()
    if (props.isNotEmpty()) return props
    return declarations
      .filterIsInstanceAnd<IrSimpleFunction> { it.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR }
      .mapNotNull { it.correspondingPropertySymbol?.owner }
  }

  abstract fun getArrayContentEqualsSymbol(type: IrType): IrFunctionSymbol

  open fun IrExpression.transformArrayEqualsArgument(type: IrType, irBuilder: IrBlockBodyBuilder): IrExpression = this

  fun generatedEquals(irBuilder: IrBlockBodyBuilder, type: IrType, arg1: IrExpression, arg2: IrExpression): IrExpression =
    if (type.isArray() || type.isPrimitiveArray() || type.isUnsignedArray()) {
      val requiredSymbol = getArrayContentEqualsSymbol(type)
      val lhs = arg1.transformArrayEqualsArgument(type, irBuilder)
      val rhs = arg2.transformArrayEqualsArgument(type, irBuilder)
      irBuilder.irCall(
        requiredSymbol
      ).apply {
        arguments[0] = lhs
        arguments[1] = rhs
      }
    } else
      irBuilder.irEquals(arg1, arg2)

  open val forbidDirectFieldAccessInMethods = false

  open fun generateFunctionBodies(
    annotationClass: IrClass,
    implClass: IrClass,
    eqFun: IrSimpleFunction,
    hcFun: IrSimpleFunction,
    toStringFun: IrSimpleFunction,
    generator: AnnotationImplementationMemberGenerator,
  ) {
    val properties = annotationClass.getAnnotationProperties()
    generator.generateEqualsUsingGetters(eqFun, annotationClass.defaultType, properties)
    generator.generateHashCodeMethod(hcFun, properties)
    generator.generateToStringMethod(toStringFun, properties)
  }

  fun implementGeneratedFunctions(annotationClass: IrClass, implClass: IrClass) {
    val creator = MethodsFromAnyGeneratorForLowerings(context, implClass, ANNOTATION_IMPLEMENTATION)
    val eqFun = creator.createEqualsMethodDeclaration()
    val hcFun = creator.createHashCodeMethodDeclaration()
    val toStringFun = creator.createToStringMethodDeclaration()
    if (annotationClass != implClass) {
      implClass.addFakeOverrides(context.typeSystem)
    }

    val generator = AnnotationImplementationMemberGenerator(
      context, symbolTable, implClass,
      nameForToString = "@" + annotationClass.fqNameWhenAvailable!!.asString(),
      forbidDirectFieldAccess = forbidDirectFieldAccessInMethods
    ) { type, a, b ->
      generatedEquals(this, type, a, b)
    }

    generateFunctionBodies(annotationClass, implClass, eqFun, hcFun, toStringFun, generator)
  }

  open fun implementPlatformSpecificParts(annotationClass: IrClass, implClass: IrClass) {}
}

class AnnotationImplementationMemberGenerator(
  loweringContext: LoweringContext,
  symbolTable: ReferenceSymbolTable,
  irClass: IrClass,
  val nameForToString: String,
  forbidDirectFieldAccess: Boolean,
  val selectEquals: IrBlockBodyBuilder.(IrType, IrExpression, IrExpression) -> IrExpression,
) : LoweringDataClassMemberGenerator(loweringContext, symbolTable, irClass, ANNOTATION_IMPLEMENTATION, forbidDirectFieldAccess) {

  override fun IrClass.classNameForToString(): String = nameForToString

  // From https://docs.oracle.com/javase/8/docs/api/java/lang/annotation/Annotation.html#equals-java.lang.Object-
  // ---
  // The hash code of an annotation is the sum of the hash codes of its members (including those with default values), as defined below:
  // The hash code of an annotation member is (127 times the hash code of the member-name as computed by String.hashCode()) XOR the hash code of the member-value
  override fun IrBuilderWithScope.shiftResultOfHashCode(irResultVar: IrVariable): IrExpression = irGet(irResultVar) // no default (* 31)

  override fun getHashCodeOf(builder: IrBuilderWithScope, property: IrProperty, irValue: IrExpression): IrExpression = with(builder) {
    val propertyValueHashCode = getHashCodeOf(property.type, irValue)
    val propertyNameHashCode = getHashCodeOf(loweringContext.irBuiltIns.stringType, irString(property.name.toString()))
    val multiplied = irCallOp(context.irBuiltIns.intTimesSymbol, context.irBuiltIns.intType, propertyNameHashCode, irInt(127))
    return irCallOp(context.irBuiltIns.intXorSymbol, context.irBuiltIns.intType, multiplied, propertyValueHashCode)
  }

  private fun IrBuilderWithScope.getHashCodeOf(type: IrType, irValue: IrExpression): IrExpression {
    return getHashCodeOf(getHashCodeFunctionInfo(type), irValue)
  }

  // Manual implementation of equals is required for following reasons:
  // 1. `other` should be casted to interface instead of implementation
  // 2. Properties should be retrieved using getters without accessing backing fields
  //    (DataClassMembersGenerator typically tries to access fields)
  // 3. Custom equals function should be used on properties
  fun generateEqualsUsingGetters(equalsFun: IrSimpleFunction, typeForEquals: IrType, properties: List<IrProperty>) = equalsFun.apply {
    body = loweringContext.createIrBuilder(symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).irBlockBody {
      val irType = typeForEquals
      fun irOther() = irGet(parameters[1])
      fun irThis() = irGet(parameters[0])
      fun IrProperty.get(receiver: IrExpression) = irCall(getter!!).apply {
        dispatchReceiver = receiver
      }

      +irIfThenReturnFalse(irNotIs(irOther(), irType))
      val otherWithCast = irTemporary(irAs(irOther(), irType), "other_with_cast")
      for (property in properties) {
        val arg1 = property.get(irThis())
        val arg2 = property.get(irGet(irType, otherWithCast.symbol))
        +irIfThenReturnFalse(irNot(selectEquals(property.type, arg1, arg2)))
      }
      +irReturnTrue()
    }
  }
}

