/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ir.BuiltinSymbolsBase
import org.jetbrains.kotlin.backend.common.lower.ANNOTATION_IMPLEMENTATION
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationLowering
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationMemberGenerator
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationTransformer
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.createJvmIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.isInPublicInlineScope
import org.jetbrains.kotlin.backend.jvm.ir.javaClassReference
import org.jetbrains.kotlin.backend.jvm.isPublicAbi
import org.jetbrains.kotlin.backend.jvm.unboxInlineClass
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.createTmpVariable
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallOp
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.builders.primitiveOp2
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isKClass
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithoutPatchingParents
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.findDeclaration
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isUnsignedArray
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

@PhaseDescription(name = "AnnotationImplementation")
internal class JvmAnnotationImplementationLowering(context: JvmBackendContext) : AnnotationImplementationLowering(
  { JvmAnnotationImplementationTransformer(context, it) }
)

class JvmAnnotationImplementationTransformer(private val jvmContext: JvmBackendContext, file: IrFile) :
  AnnotationImplementationTransformer(jvmContext, jvmContext.symbolTable, file) {
  private val publicAnnotationImplementationClasses = mutableSetOf<IrClassSymbol>()

  // FIXME: Copied from JvmSingleAbstractMethodLowering
  private val inInlineFunctionScope: Boolean
    get() = allScopes.any { (it.irElement as? IrDeclaration)?.isInPublicInlineScope == true }

  private val implementor = AnnotationPropertyImplementor(
    jvmContext.irFactory,
    jvmContext.irBuiltIns,
    jvmContext.ir.symbols,
    jvmContext.ir.symbols.javaLangClass,
    jvmContext.ir.symbols.kClassJavaPropertyGetter.symbol,
    ANNOTATION_IMPLEMENTATION
  )

  @Suppress("UNUSED_PARAMETER")
  override fun chooseConstructor(implClass: IrClass, expression: IrConstructorCall) =
    implClass.constructors.single()

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val constructedClass = expression.type.classOrNull
    if (constructedClass?.owner?.isAnnotationClass == true && inInlineFunctionScope) {
      publicAnnotationImplementationClasses += constructedClass
    }
    return super.visitConstructorCall(expression)
  }

  // There's no specialized Array.equals for unsigned arrays (as this is a Java function), so we force compiler not to box
  // result of property getter call
  override fun IrExpression.transformArrayEqualsArgument(type: IrType, irBuilder: IrBlockBodyBuilder): IrExpression =
    if (!type.isUnsignedArray()) this
    else irBuilder.irCall(jvmContext.ir.symbols.unsafeCoerceIntrinsic).apply {
      typeArguments[0] = type
      typeArguments[1] = type.unboxInlineClass()
      putValueArgument(0, this@transformArrayEqualsArgument)
    }

  override fun getArrayContentEqualsSymbol(type: IrType): IrFunctionSymbol {
    val targetType = when {
      type.isPrimitiveArray() -> type
      type.isUnsignedArray() -> type.unboxInlineClass()
      else -> jvmContext.ir.symbols.arrayOfAnyNType
    }
    val requiredSymbol = jvmContext.ir.symbols.arraysClass.owner.findDeclaration<IrFunction> {
      it.name.asString() == "equals" && it.valueParameters.size == 2 && it.valueParameters.first().type == targetType
    }
    requireNotNull(requiredSymbol) { "Can't find Arrays.equals method for type ${targetType.render()}" }
    return requiredSymbol.symbol
  }

  override fun implementPlatformSpecificParts(annotationClass: IrClass, implClass: IrClass) {
    // Mark the implClass as part of the public ABI if it was instantiated from a public
    // inline function, since annotation implementation classes are regenerated during inlining.
    if (annotationClass.symbol in publicAnnotationImplementationClasses) {
      implClass.isPublicAbi = true
    }

    implClass.addFunction(
      name = "annotationType",
      returnType = jvmContext.ir.symbols.javaLangClass.starProjectedType,
      origin = ANNOTATION_IMPLEMENTATION,
      isStatic = false
    ).apply {
      body = jvmContext.createJvmIrBuilder(symbol).run {
        irBlockBody {
          +irReturn(javaClassReference(annotationClass.defaultType))
        }
      }
    }
  }

  override fun implementAnnotationPropertiesAndConstructor(
    implClass: IrClass,
    annotationClass: IrClass,
    generatedConstructor: IrConstructor,
  ) {
    implementor.implementAnnotationPropertiesAndConstructor(
      annotationClass,
      annotationClass.getAnnotationProperties(),
      implClass,
      generatedConstructor,
      this
    )
  }

  override fun generateFunctionBodies(
    annotationClass: IrClass,
    implClass: IrClass,
    eqFun: IrSimpleFunction,
    hcFun: IrSimpleFunction,
    toStringFun: IrSimpleFunction,
    generator: AnnotationImplementationMemberGenerator,
  ) {
    val properties = annotationClass.getAnnotationProperties()
    val implProperties = implClass.getAnnotationProperties()
    generator.generateEqualsUsingGetters(eqFun, annotationClass.defaultType, properties)
    generator.generateHashCodeMethod(hcFun, implProperties)
    generator.generateToStringMethod(toStringFun, implProperties)
  }

  class AnnotationPropertyImplementor(
    val irFactory: IrFactory,
    val irBuiltIns: IrBuiltIns,
    val symbols: BuiltinSymbolsBase,
    val javaLangClassSymbol: IrClassSymbol,
    val kClassJavaPropertyGetterSymbol: IrSimpleFunctionSymbol,
    val originForProp: IrDeclarationOrigin,
  ) {

    /**
     * Copies array by one element, roughly as following:
     *     val size = kClassArray.size
     *     val result = arrayOfNulls<java.lang.Class>(size)
     *     var i = 0
     *     while(i < size) {
     *         result[i] = kClassArray[i].java
     *         i++
     *     }
     * Partially taken from ArrayConstructorLowering.kt
     */
    private fun IrBuilderWithScope.kClassArrayToJClassArray(kClassArray: IrExpression): IrExpression {
      val javaLangClassType = javaLangClassSymbol.starProjectedType
      val jlcArray = symbols.array.typeWith(javaLangClassType)
      val arrayClass = symbols.array.owner
      val arrayOfNulls = symbols.arrayOfNulls
      val arraySizeSymbol = arrayClass.findDeclaration<IrProperty> { it.name.asString() == "size" }!!.getter!!

      val block = irBlock {
        val sourceArray = createTmpVariable(kClassArray, "src", isMutable = false)
        val index = createTmpVariable(irInt(0), "i", isMutable = true)
        val size = createTmpVariable(
          irCall(arraySizeSymbol).apply { dispatchReceiver = irGet(sourceArray) },
          "size", isMutable = false
        )
        val result = createTmpVariable(irCall(arrayOfNulls, jlcArray).apply {
          listOf(javaLangClassType)
          putValueArgument(0, irGet(size))
        })
        val comparison = primitiveOp2(
          startOffset, endOffset,
          context.irBuiltIns.lessFunByOperandType[context.irBuiltIns.intType.classifierOrFail]!!,
          context.irBuiltIns.booleanType,
          IrStatementOrigin.LT,
          irGet(index), irGet(size)
        )
        val setArraySymbol = arrayClass.functions.single { it.name == OperatorNameConventions.SET }
        val getArraySymbol = arrayClass.functions.single { it.name == OperatorNameConventions.GET }
        val inc = context.irBuiltIns.intType.getClass()!!.functions.single { it.name == OperatorNameConventions.INC }
        +irWhile().also { loop ->
          loop.condition = comparison
          loop.body = irBlock {
            val tempIndex = createTmpVariable(irGet(index))

            val getArray = irCall(getArraySymbol).apply {
              dispatchReceiver = irGet(sourceArray)
              putValueArgument(0, irGet(tempIndex))
            }
            +irCall(setArraySymbol).apply {
              dispatchReceiver = irGet(result)
              putValueArgument(0, irGet(tempIndex))
              putValueArgument(1, kClassToJClass(getArray))
            }

            +irSet(index.symbol, irCallOp(inc.symbol, index.type, irGet(index)))
          }
        }
        +irGet(result)
      }
      return block
    }

    private fun IrType.kClassToJClassIfNeeded(): IrType = when {
      this.isKClass() -> javaLangClassSymbol.starProjectedType
      this.isKClassArray() -> symbols.array.typeWith(
        javaLangClassSymbol.starProjectedType
      )

      else -> this
    }

    private fun IrType.isKClassArray() =
      this is IrSimpleType && isArray() && arguments.single().typeOrNull?.isKClass() == true

    private fun IrBuilderWithScope.kClassToJClass(irExpression: IrExpression): IrExpression =
      irGet(
        javaLangClassSymbol.starProjectedType,
        null,
        kClassJavaPropertyGetterSymbol
      ).apply {
        extensionReceiver = irExpression
      }

    fun implementAnnotationPropertiesAndConstructor(
      annotationClass: IrClass,
      annotationProperties: List<IrProperty>,
      implClass: IrClass,
      generatedConstructor: IrConstructor,
      defaultValueTransformer: IrElementTransformerVoid?,
    ) {
      val ctorBodyBuilder = irBuiltIns.createIrBuilder(generatedConstructor.symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET)
      val ctorBody = irFactory.createBlockBody(
        SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, listOf(
          IrDelegatingConstructorCallImpl(
            SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, irBuiltIns.unitType, irBuiltIns.anyClass.constructors.single(),
            typeArgumentsCount = 0,
          )
        )
      )

      generatedConstructor.body = ctorBody

      // For annotations defined in Java, IrProperties do not contain initializers in backing fields, as annotation properties are represented as Java methods
      // (that are later converted to synthetic properties w/o fields).
      // However, K2 stores default values in annotation's constructor parameters.
      val fallbackPrimaryCtorParamsMap = annotationClass.primaryConstructor?.valueParameters?.associateBy { it.name }.orEmpty()

      annotationProperties.forEach { property ->
        val propType = property.getter!!.returnType
        val storedFieldType = propType.kClassToJClassIfNeeded()
        val propName = property.name
        val field = irFactory.buildField {
          startOffset = SYNTHETIC_OFFSET
          endOffset = SYNTHETIC_OFFSET
          name = propName
          type = storedFieldType
          origin = originForProp
          isFinal = true
          visibility = DescriptorVisibilities.PRIVATE
        }.also { it.parent = implClass }

        val parameter = generatedConstructor.addValueParameter(propName.asString(), propType)

        val defaultExpression = property.backingField?.initializer?.expression
        val newDefaultValue: IrExpressionBody? =
          // INITIALIZE_PROPERTY_FROM_PARAMETER
          when {
            defaultExpression is IrGetValue && defaultExpression.symbol.owner is IrValueParameter ->
              (defaultExpression.symbol.owner as IrValueParameter).defaultValue
            defaultExpression != null -> property.backingField!!.initializer
            propName in fallbackPrimaryCtorParamsMap ->
              fallbackPrimaryCtorParamsMap[propName]?.defaultValue?.takeIf { it.expression !is IrErrorExpression }
            else -> null
          }
        parameter.defaultValue = newDefaultValue?.deepCopyWithoutPatchingParents()
          ?.also { if (defaultValueTransformer != null) it.transformChildrenVoid(defaultValueTransformer) }

        ctorBody.statements += with(ctorBodyBuilder) {
          val param = irGet(parameter)
          val fieldValue = when {
            propType.isKClass() -> kClassToJClass(param)
            propType.isKClassArray() -> kClassArrayToJClassArray(param)
            else -> param
          }
          irSetField(irGet(implClass.thisReceiver!!), field, fieldValue)
        }

        val prop = implClass.addProperty {
          startOffset = SYNTHETIC_OFFSET
          endOffset = SYNTHETIC_OFFSET
          name = propName
          isVar = false
          origin = originForProp
        }.apply {
          field.correspondingPropertySymbol = this.symbol
          backingField = field
          parent = implClass
          overriddenSymbols = listOf(property.symbol)
        }

        prop.addGetter {
          startOffset = SYNTHETIC_OFFSET
          endOffset = SYNTHETIC_OFFSET
          name = propName  // Annotation value getter should be named 'x', not 'getX'
          returnType = propType.kClassToJClassIfNeeded() // On JVM, annotation store j.l.Class even if declared with KClass
          origin = originForProp
          visibility = DescriptorVisibilities.PUBLIC
          modality = Modality.FINAL
        }.apply {
          correspondingPropertySymbol = prop.symbol
          dispatchReceiverParameter = implClass.thisReceiver!!.copyTo(this)
          body = irBuiltIns.createIrBuilder(symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).irBlockBody {
            +irReturn(irGetField(irGet(dispatchReceiverParameter!!), field))
          }
          overriddenSymbols = listOf(property.getter!!.symbol)
        }
      }
    }
  }
}
