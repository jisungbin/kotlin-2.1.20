/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.ComposeCallableIds
import androidx.compose.compiler.plugins.kotlin.ComposeClassIds
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrImplementationDetail
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrVarargElement
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyWithOffsets
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.Name

/**
 * This transformer transforms constant literal expressions into expressions which read a
 * MutableState instance so that changes to the source code of the constant literal can be
 * communicated to the runtime without a recompile. This transformation is intended to improve
 * developer experience and should never be enabled in a release build as it will significantly
 * slow down performance-conscious code.
 *
 * The nontrivial piece of this transform is to create a stable "durable" unique key for every
 * single constant in the module. It does this by creating a path-based key which roughly maps to
 * the semantic structure of the code, and uses an incrementing index on sibling constants as a
 * last resort. The constant expressions in the IR will be transformed into property getter calls
 * to a synthetic "Live Literals" class that is generated per file. The class is an object
 * singleton where each property is lazily backed by a MutableState instance which is accessed
 * using the runtime's `liveLiteral(String,T)` top level API.
 *
 * Roughly speaking, the transform will turn the following:
 *
 *     // file: Foo.kt
 *     fun Foo() {
 *       print("Hello World")
 *     }
 *
 * into the following equivalent representation:
 *
 *    // file: Foo.kt
 *    fun Foo() {
 *      print(LiveLiterals$FooKt.`getString$arg-0$call-print$fun-Foo`())
 *    }
 *    object LiveLiterals$FooKt {
 *      var `String$arg-0$call-print$fun-Foo`: String = "Hello World"
 *      var `State$String$arg-0$call-print$fun-Foo`: MutableState<String>? = null
 *      fun `getString$arg-0$call-print$fun-Foo`(): String {
 *        val field = this.`String$arg-0$call-print$fun-Foo`
 *        val state = if (field == null) {
 *          val tmp = liveLiteral(
 *              "String$arg-0$call-print$fun-Foo",
 *              this.`String$arg-0$call-print$fun-Foo`
 *          )
 *          this.`String$arg-0$call-print$fun-Foo` = tmp
 *          tmp
 *        } else field
 *        return field.value
 *      }
 *    }
 *
 * @see DurableKeyVisitor
 */
// 이 변환기는 상수 리터럴 표현식을 MutableState 인스턴스를 읽는 표현식으로 변환하여 상수 리터럴의
// 소스 코드 변경 사항을 다시 컴파일하지 않고 런타임에 전달할 수 있도록 합니다. 이 변환은 개발자
// 경험을 개선하기 위한 것으로, 성능에 민감한 코드의 속도를 크게 저하시킬 수 있으므로 릴리스 빌드에서는
// 절대 활성화해서는 안 됩니다.
//
// 이 변환의 중요한 부분은 모듈의 모든 상수에 대해 안정적인 “내구성 있는” 고유 키를 생성하는 것입니다.
// 이를 위해 코드의 의미 구조에 대략적으로 매핑되는 경로 기반 키를 생성하고, 최후의 수단으로 형제
// 상수에 대해 증분 인덱스를 사용합니다. IR의 상수 표현식은 파일별로 생성되는 합성 “라이브 리터럴”
// 클래스에 대한 프로퍼티 게터 호출로 변환됩니다. 이 클래스는 객체 싱글톤으로, 각 프로퍼티는 런타임의
// liveLiteral(String,T) 최상위 API를 사용하여 액세스되는 MutableState 인스턴스에 의해 lazy하게 지원됩니다.
//
// 대략적으로 말하면, 변환은 다음과 같이 변합니다:
//
// 를 다음과 같은 동등한 표현으로 변환합니다:
open class LiveLiteralTransformer(
  private val liveLiteralsEnabled: Boolean,
  private val usePerFileEnabledFlag: Boolean, // liveLiteralsV2Enabled
  private val keyVisitor: DurableKeyVisitor,
  context: IrPluginContext,
  metrics: ModuleMetrics,
  stabilityInferencer: StabilityInferencer,
  featureFlags: FeatureFlags,
) : AbstractComposeLowering(context, metrics, stabilityInferencer, featureFlags), ModuleLoweringPass {

  override fun lower(irModule: IrModuleFragment) {
    irModule.transformChildrenVoid(this)
  }

  private val liveLiteral = getTopLevelFunction(ComposeCallableIds.liveLiteral)
  private val isLiveLiteralsEnabled = getTopLevelPropertyGetter(ComposeCallableIds.isLiveLiteralsEnabled)
  private val liveLiteralInfoAnnotation = getTopLevelClass(ComposeClassIds.LiveLiteralInfo)
  private val liveLiteralFileInfoAnnotation = getTopLevelClass(ComposeClassIds.LiveLiteralFileInfo)
  private val stateInterface = getTopLevelClass(ComposeClassIds.State)
  private val NoLiveLiteralsAnnotation = getTopLevelClass(ComposeClassIds.NoLiveLiterals)

  private fun IrAnnotationContainer.hasNoLiveLiteralsAnnotation(): Boolean =
    annotations.any {
      it.symbol.owner == NoLiveLiteralsAnnotation.owner.primaryConstructor
    }

  private fun <T> enter(key: String, block: () -> T) = keyVisitor.enter(key, block)
  private fun <T> siblings(key: String, block: () -> T) = keyVisitor.siblings(key, block)
  private fun <T> siblings(block: () -> T) = keyVisitor.siblings(block)

  private var liveLiteralsClass: IrClass? = null
  private var liveLiteralsEnabledSymbol: IrSimpleFunctionSymbol? = null
  private var currentFile: IrFile? = null

  private fun irGetLiveLiteralsClass(startOffset: Int, endOffset: Int): IrExpression =
    IrGetObjectValueImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = liveLiteralsClass!!.defaultType,
      symbol = liveLiteralsClass!!.symbol,
    )

  private fun Name.asJvmFriendlyString(): String {
    return if (!isSpecial) identifier
    else asString().replace('<', '$').replace('>', '$').replace(' ', '-')
  }

  private fun irLiveLiteralInfoAnnotation(
    key: String,
    offset: Int,
  ): IrConstructorCall =
    IrConstructorCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = liveLiteralInfoAnnotation.defaultType,
      symbol = liveLiteralInfoAnnotation.constructors.single(),
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0
    ).apply {
      putValueArgument(0, irStringConst(key))
      putValueArgument(1, irIntConst(offset))
    }

  private fun irLiveLiteralFileInfoAnnotation(file: String): IrConstructorCall =
    IrConstructorCallImpl(
      startOffset = UNDEFINED_OFFSET,
      endOffset = UNDEFINED_OFFSET,
      type = liveLiteralFileInfoAnnotation.defaultType,
      symbol = liveLiteralFileInfoAnnotation.constructors.single(),
      typeArgumentsCount = 0,
      constructorTypeArgumentsCount = 0
    ).apply {
      putValueArgument(0, irStringConst(file))
    }

  @OptIn(IrImplementationDetail::class)
  private fun irLiveLiteralStateValueGetter(
    key: String,
    literalValue: IrExpression,
    literalType: IrType,
    startOffset: Int,
  ): IrSimpleFunction {
    val clazz = liveLiteralsClass!!
    val stateType = stateInterface.owner.typeWith(literalType).makeNullable()
    val stateValueGetter = stateInterface.getPropertyGetter("value")!!

    // STUDY getter가 있으면 backingField가 없어도 되지 않나?
    val defaultProp = clazz.addProperty {
      name = Name.identifier(key)
      visibility = DescriptorVisibilities.PRIVATE
    }.also { property ->
      property.backingField = context.irFactory.buildField {
        name = Name.identifier(key)
        isStatic = true
        type = literalType
        visibility = DescriptorVisibilities.PRIVATE
      }.also { field ->
        field.correspondingPropertySymbol = property.symbol
        field.parent = clazz
        field.initializer = context.irFactory.createExpressionBody(
          startOffset = SYNTHETIC_OFFSET,
          endOffset = SYNTHETIC_OFFSET,
          expression = literalValue,
        )
      }
      property.addGetter {
        returnType = literalType
        visibility = DescriptorVisibilities.PRIVATE
        origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
      }.also { getter ->
        val thisParam = clazz.thisReceiver!!.copyTo(irFunction = getter)

        getter.correspondingPropertySymbol = property.symbol
        getter.dispatchReceiverParameter = thisParam
        getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
          +irReturn(irGetField(receiver = irGet(thisParam), field = property.backingField!!))
        }
      }
    }

    val stateProp = clazz.addProperty {
      name = Name.identifier("State\$$key")
      visibility = DescriptorVisibilities.PRIVATE
      isVar = true
    }.also { property ->
      property.backingField = context.irFactory.buildField {
        name = Name.identifier("State\$$key")
        type = stateType
        visibility = DescriptorVisibilities.PRIVATE
        isStatic = true
      }.also { field ->
        field.correspondingPropertySymbol = property.symbol
        field.parent = clazz
      }
      property.addGetter {
        returnType = stateType
        visibility = DescriptorVisibilities.PRIVATE
        origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
      }.also { getter ->
        val thisParam = clazz.thisReceiver!!.copyTo(irFunction = getter)

        getter.correspondingPropertySymbol = property.symbol
        getter.dispatchReceiverParameter = thisParam
        getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
          +irReturn(irGetField(receiver = irGet(thisParam), field = property.backingField!!))
        }
      }
      property.addSetter {
        returnType = context.irBuiltIns.unitType
        visibility = DescriptorVisibilities.PRIVATE
        origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
      }.also { setter ->
        val thisParam = clazz.thisReceiver!!.copyTo(setter)
        val valueParam = setter.addValueParameter("value", stateType)

        setter.correspondingPropertySymbol = property.symbol
        setter.dispatchReceiverParameter = thisParam
        setter.body = DeclarationIrBuilder(context, setter.symbol).irBlockBody {
          +irSetField(receiver = irGet(thisParam), field = property.backingField!!, value = irGet(valueParam))
        }
      }
    }

    return clazz.addFunction(
      name = key,
      returnType = literalType,
    ).also { fn ->
      val thisParam = fn.dispatchReceiverParameter!!

      fn.annotations += irLiveLiteralInfoAnnotation(key, startOffset)

      // if (!isLiveLiteralsEnabled) return defaultValueField
      // val a = stateField
      // val b = if (a == null) {
      //     val c = liveLiteral("key", defaultValueField)
      //     stateField = c
      //     c
      // } else a
      // return b.value
      fn.body = DeclarationIrBuilder(context, fn.symbol).irBlockBody {
        val isLiveLiteralsDisabledCondition = when {
          usePerFileEnabledFlag -> irNot(
            irGet(
              type = builtIns.booleanType,
              receiver = irGet(thisParam),
              getterSymbol = liveLiteralsEnabledSymbol!!,
            ),
          )
          else -> irNot(irCall(isLiveLiteralsEnabled))
        }

        +irIf(
          condition = isLiveLiteralsDisabledCondition,
          body = irReturn(
            irGet(
              type = literalType,
              receiver = irGet(thisParam),
              getterSymbol = defaultProp.getter!!.symbol,
            ),
          ),
        )

        val statePropNullableGettingVar = irTemporary(
          irGet(
            type = stateType,
            receiver = irGet(thisParam),
            getterSymbol = stateProp.getter!!.symbol,
          ),
        )
        val statePropNonNullVar = irIfNull(
          type = stateType,
          subject = irGet(statePropNullableGettingVar),
          thenPart = irBlock(resultType = stateType) {
            val liveLiteralCall = irCall(liveLiteral).apply {
              putValueArgument(0, irString(key))
              putValueArgument(
                1,
                irGet(
                  type = literalType,
                  receiver = irGet(thisParam),
                  getterSymbol = defaultProp.getter!!.symbol,
                )
              )
              typeArguments[0] = literalType
            }
            val liveLiteralCallVar = irTemporary(liveLiteralCall)

            +irSet(
              type = stateType,
              receiver = irGet(thisParam),
              setterSymbol = stateProp.setter!!.symbol,
              value = irGet(liveLiteralCallVar),
            )
            +irGet(variable = liveLiteralCallVar)
          },
          elsePart = irGet(statePropNullableGettingVar)
        )
        val liveLiteralStateCall = IrCallImpl(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET,
          type = literalType,
          symbol = stateValueGetter,
          typeArgumentsCount = stateValueGetter.owner.typeParameters.size,
          origin = IrStatementOrigin.FOR_LOOP_ITERATOR
        ).apply {
          dispatchReceiver = statePropNonNullVar
        }

        +irReturn(liveLiteralStateCall)
      }
    }
  }

  open fun makeKeySet(): MutableSet<String> = mutableSetOf()

  override fun visitConst(expression: IrConst): IrExpression {
    when (expression.kind) {
      IrConstKind.Null -> return expression
      else -> {} /* Continue visiting expression */
    }
    val (key, success) = keyVisitor.buildPath(
      prefix = expression.kind.asString,
      pathSeparator = "\$",
      siblingSeparator = "-",
    )

    // Even if `liveLiteralsEnabled` is false, we are still going to throw an exception
    // here because the presence of a duplicate key represents a bug in this transform since
    // it should be impossible. By checking this always, we are making it so that bugs in
    // this transform will get caught _early_ and that there will be implicitly high coverage
    // of the key generation algorithm despite this transform only being used by tooling.
    // Developers have the ability to "silence" this exception by marking the surrounding
    // class/file/function with the `@NoLiveLiterals` annotation.
    //
    // `liveLiteralsEnabled`가 거짓이더라도 중복 키가 존재하면 이 변환에서 버그가 발생하므로
    // 여기서 예외를 던질 것입니다. 이를 항상 확인함으로써 이 transformer의 버그가 조기에 발견되고
    // 이 transformer의 툴링에서만 사용됨에도 불구하고 키 생성 알고리즘의 적용 범위가 암시적으로
    // 높아지도록 만들고 있습니다. 개발자는 주변 클래스/파일/함수에 `@NoLiveLiterals` 어노테이션을
    // 표시하여 이 예외를 무시할 수 있습니다.
    if (!success) {
      val file = currentFile ?: return expression
      val src = file.fileEntry.getSourceRangeInfo(expression.startOffset, expression.endOffset)

      error(
        "Duplicate live literal key found: $key\n" +
          "Caused by element at: ${src.filePath}:${src.startLineNumber}:${src.startColumnNumber}\n" +
          "If you encounter this error, please file a bug at " +
          "https://issuetracker.google.com/issues?q=componentid:610764\n" +
          "Try adding the `@NoLiveLiterals` annotation around the surrounding code to " +
          "avoid this exception.",
      )
    }

    // If live literals are disabled, don't do anything
    if (!liveLiteralsEnabled) return expression

    // create the getter function on the live literals class
    val liveLiteralStatleValueGetter = irLiveLiteralStateValueGetter(
      key = key,
      // Move the start/endOffsets to the call of the getter since we don't
      // want to step into <clinit> in the debugger.
      literalValue = expression.copyWithOffsets(UNDEFINED_OFFSET, UNDEFINED_OFFSET),
      literalType = expression.type,
      startOffset = expression.startOffset,
    )

    // return a call to the getter in place of the constant
    return IrCallImpl(
      startOffset = expression.startOffset,
      endOffset = expression.endOffset,
      type = expression.type,
      symbol = liveLiteralStatleValueGetter.symbol,
      typeArgumentsCount = liveLiteralStatleValueGetter.symbol.owner.typeParameters.size,
    ).apply {
      dispatchReceiver = irGetLiveLiteralsClass(expression.startOffset, expression.endOffset)
    }
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    if (declaration.hasNoLiveLiteralsAnnotation()) return declaration

    // constants in annotations need to be compile-time values, so we can never transform them
    if (declaration.isAnnotationClass) return declaration
    return siblings("class-${declaration.name.asJvmFriendlyString()}") {
      super.visitClass(declaration)
    }
  }

  override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer): IrStatement {
    if (declaration.hasNoLiveLiteralsAnnotation()) return declaration

    return enter("init") { super.visitAnonymousInitializer(declaration) }
  }

  @OptIn(IrImplementationDetail::class)
  override fun visitFile(declaration: IrFile): IrFile {
    includeFileNameInExceptionTrace(declaration) {
      if (declaration.hasNoLiveLiteralsAnnotation()) return declaration

      val filePath = declaration.fileEntry.name
      val fileName = filePath.split('/').last()
      val keys = makeKeySet()

      return keyVisitor.root(keys) {
        val prevEnabledSymbol = liveLiteralsEnabledSymbol
        var nextEnabledSymbol: IrSimpleFunctionSymbol? = null
        val prevClass = liveLiteralsClass
        val nextClass = context.irFactory.buildClass {
          kind = ClassKind.OBJECT
          visibility = DescriptorVisibilities.INTERNAL

          val shortName = PackagePartClassUtils.getFilePartShortName(fileName)
          // the name of the LiveLiterals class is per-file, so we use the same name that
          // the kotlin file class lowering produces, prefixed with `LiveLiterals$`.
          name = Name.identifier("LiveLiterals${"$"}$shortName")
        }.also { clazz ->
          clazz.createThisReceiverParameter()

          // store the full file path to the file that this class is associated with in an
          // annotation on the class. This will be used by tooling to associate the keys
          // inside of this class with actual PSI in the editor.
          //
          // 클래스에 대한 어노테이션에 이 클래스가 연결된 파일의 전체 파일 경로를 저장합니다.
          // **이 파일은 에디터에서 이 클래스 내부의 키를 실제 PSI와 연결하는 툴링에 사용됩니다.** <-- WOW
          clazz.annotations += irLiveLiteralFileInfoAnnotation(declaration.fileEntry.name)
          clazz.addConstructor { isPrimary = true }.also { ctor ->
            ctor.body = DeclarationIrBuilder(context, clazz.symbol).irBlockBody {
              +irDelegatingConstructorCall(
                callee = context
                  .irBuiltIns
                  .anyClass
                  .owner
                  .primaryConstructor!!,
              )
            }
          }

          if (usePerFileEnabledFlag) {
            // STUDY setter는 왜 없을까?
            val enabledProp = clazz.addProperty {
              name = Name.identifier("enabled")
              visibility = DescriptorVisibilities.PRIVATE
            }.also { property ->
              property.backingField = context.irFactory.buildField {
                name = Name.identifier("enabled")
                isStatic = true
                type = builtIns.booleanType
                visibility = DescriptorVisibilities.PRIVATE
              }.also { field ->
                field.correspondingPropertySymbol = property.symbol
                field.parent = clazz
                field.initializer = context.irFactory.createExpressionBody(
                  startOffset = SYNTHETIC_OFFSET,
                  endOffset = SYNTHETIC_OFFSET,
                  expression = irBooleanConst(false),
                )
              }
              property.addGetter {
                returnType = builtIns.booleanType
                visibility = DescriptorVisibilities.PRIVATE
                origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
              }.also { getter ->
                val thisParam = clazz.thisReceiver!!.copyTo(getter)

                getter.dispatchReceiverParameter = thisParam
                getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
                  +irReturn(irGetField(receiver = irGet(thisParam), field = property.backingField!!))
                }
              }
            }
            nextEnabledSymbol = enabledProp.getter?.symbol
          }
        }

        try {
          liveLiteralsClass = nextClass
          currentFile = declaration
          liveLiteralsEnabledSymbol = nextEnabledSymbol

          val file = super.visitFile(declaration)
          // if there were no constants found in the entire file, then we don't need to
          // create this class at all
          if (liveLiteralsEnabled && keys.isNotEmpty()) {
            file.addChild(nextClass)
          }
          file
        } finally {
          liveLiteralsClass = prevClass
          liveLiteralsEnabledSymbol = prevEnabledSymbol
        }
      }
    }
  }

  override fun visitTry(aTry: IrTry): IrExpression {
    aTry.tryResult = enter("try") {
      aTry.tryResult.transform(this, null)
    }
    siblings {
      aTry.catches.forEach {
        it.result = enter("catch") { it.result.transform(this, null) }
      }
    }
    aTry.finallyExpression = enter("finally") {
      aTry.finallyExpression?.transform(this, null)
    }
    return aTry
  }

  // STUDY IrDelegatingConstructorCall는 어떤 표현식일까?
  override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
    val owner = expression.symbol.owner

    // annotations are represented as constructor calls in IR, but the parameters need to be
    // compile-time values only, so we can't transform them at all.
    if (owner.parentAsClass.isAnnotationClass) return expression

    val name = owner.name.asJvmFriendlyString()

    return enter("call-$name") {
      expression.dispatchReceiver = enter("\$this") {
        expression.dispatchReceiver?.transform(this, null)
      }
      expression.extensionReceiver = enter("\$\$this") {
        expression.extensionReceiver?.transform(this, null)
      }

      for (i in 0 until expression.valueArgumentsCount) {
        val arg = expression.getValueArgument(i)
        if (arg != null) {
          enter("arg-$i") {
            expression.putValueArgument(i, arg.transform(this, null))
          }
        }
      }
      expression
    }
  }

  override fun visitEnumConstructorCall(expression: IrEnumConstructorCall): IrExpression {
    val owner = expression.symbol.owner
    val name = owner.name.asJvmFriendlyString()

    return enter("call-$name") {
      expression.dispatchReceiver = enter("\$this") {
        expression.dispatchReceiver?.transform(this, null)
      }
      expression.extensionReceiver = enter("\$\$this") {
        expression.extensionReceiver?.transform(this, null)
      }

      for (i in 0 until expression.valueArgumentsCount) {
        val arg = expression.getValueArgument(i)
        if (arg != null) {
          enter("arg-$i") {
            expression.putValueArgument(i, arg.transform(this, null))
          }
        }
      }
      expression
    }
  }

  override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val owner = expression.symbol.owner

    // annotations are represented as constructor calls in IR, but the parameters need to be
    // compile-time values only, so we can't transform them at all.
    if (owner.parentAsClass.isAnnotationClass) return expression

    val name = owner.name.asJvmFriendlyString()

    return enter("call-$name") {
      expression.dispatchReceiver = enter("\$this") {
        expression.dispatchReceiver?.transform(this, null)
      }
      expression.extensionReceiver = enter("\$\$this") {
        expression.extensionReceiver?.transform(this, null)
      }

      for (i in 0 until expression.valueArgumentsCount) {
        val arg = expression.getValueArgument(i)
        if (arg != null) {
          enter("arg-$i") {
            expression.putValueArgument(i, arg.transform(this, null))
          }
        }
      }
      expression
    }
  }

  override fun visitCall(expression: IrCall): IrExpression {
    val owner = expression.symbol.owner
    val name = owner.name.asJvmFriendlyString()

    return enter("call-$name") {
      expression.dispatchReceiver = enter("\$this") {
        expression.dispatchReceiver?.transform(this, null)
      }
      expression.extensionReceiver = enter("\$\$this") {
        expression.extensionReceiver?.transform(this, null)
      }

      for (i in 0 until expression.valueArgumentsCount) {
        val arg = expression.getValueArgument(i)
        if (arg != null) {
          enter("arg-$i") {
            expression.putValueArgument(i, arg.transform(this, null))
          }
        }
      }
      expression
    }
  }

  override fun visitEnumEntry(declaration: IrEnumEntry): IrStatement =
    enter("entry-${declaration.name.asJvmFriendlyString()}") {
      super.visitEnumEntry(declaration)
    }

  override fun visitVararg(expression: IrVararg): IrExpression =
    enter("vararg") {
      expression.elements.forEachIndexed { i, arg ->
        expression.elements[i] = enter("$i") {
          arg.transform(this, null) as IrVarargElement
        }
      }
      expression
    }

  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    if (declaration.hasNoLiveLiteralsAnnotation()) return declaration

    val name = declaration.name.asJvmFriendlyString()
    val path = if (name == "<anonymous>") "lambda" else "fun-$name"
    return enter(path) { super.visitSimpleFunction(declaration) }
  }

  override fun visitLoop(loop: IrLoop): IrExpression =
    when (loop.origin) {
      // in these cases, the compiler relies on a certain structure for the condition
      // expression, so we only touch the body
      IrStatementOrigin.WHILE_LOOP,
      IrStatementOrigin.FOR_LOOP_INNER_WHILE,
        -> enter("loop") {
        loop.body = enter("body") { loop.body?.transform(this, null) }
        loop
      }
      else -> enter("loop") {
        loop.condition = enter("cond") { loop.condition.transform(this, null) }
        loop.body = enter("body") { loop.body?.transform(this, null) }
        loop
      }
    }

  override fun visitStringConcatenation(expression: IrStringConcatenation): IrExpression =
    enter("str") {
      siblings {
        expression.arguments.forEachIndexed { index, expr ->
          expression.arguments[index] = enter("$index") {
            expr.transform(this, null)
          }
        }
        expression
      }
    }

  override fun visitWhen(expression: IrWhen): IrExpression =
    when (expression.origin) {
      // ANDAND needs to have an 'if true then false' body on its second branch, so only
      // transform the first branch
      IrStatementOrigin.ANDAND -> {
        expression.branches[0] = expression.branches[0].transform(this, null)
        expression
      }

      // OROR condition should have an 'if a then true' body on its first branch, so only
      // transform the second branch
      IrStatementOrigin.OROR -> {
        expression.branches[1] = expression.branches[1].transform(this, null)
        expression
      }

      IrStatementOrigin.IF -> siblings("if") {
        super.visitWhen(expression)
      }

      else -> siblings("when") {
        super.visitWhen(expression)
      }
    }

  override fun visitValueParameter(declaration: IrValueParameter): IrStatement =
    enter("param-${declaration.name.asJvmFriendlyString()}") {
      super.visitValueParameter(declaration)
    }

  override fun visitElseBranch(branch: IrElseBranch): IrElseBranch =
    IrElseBranchImpl(
      startOffset = branch.startOffset,
      endOffset = branch.endOffset,
      // the condition of an else branch is a constant boolean but we don't want
      // to convert it into a live literal, so we don't transform it
      condition = branch.condition,
      result = enter("else") {
        branch.result.transform(this, null)
      }
    )

  override fun visitBranch(branch: IrBranch): IrBranch =
    IrBranchImpl(
      startOffset = branch.startOffset,
      endOffset = branch.endOffset,
      condition = enter("cond") {
        branch.condition.transform(this, null)
      },
      // only translate the result, as the branch is a constant boolean but we don't want
      // to convert it into a live literal
      result = enter("branch") {
        branch.result.transform(this, null)
      }
    )

  override fun visitComposite(expression: IrComposite): IrExpression =
    siblings {
      super.visitComposite(expression)
    }

  override fun visitBlock(expression: IrBlock): IrExpression =
    when (expression.origin) {
      // The compiler relies on a certain structure for the "iterator" instantiation in For
      // loops, so we avoid transforming the first statement in this case
      IrStatementOrigin.FOR_LOOP,
      IrStatementOrigin.FOR_LOOP_INNER_WHILE,
        -> {
        expression.statements[1] = expression.statements[1].transform(this, null) as IrStatement
        expression
      }
      // IrStatementOrigin.SAFE_CALL
      // IrStatementOrigin.WHEN
      // IrStatementOrigin.IF
      // IrStatementOrigin.ELVIS
      // IrStatementOrigin.ARGUMENTS_REORDERING_FOR_CALL
      else -> siblings {
        super.visitBlock(expression)
      }
    }

  override fun visitSetValue(expression: IrSetValue): IrExpression {
    val owner = expression.symbol.owner
    val name = owner.name
    return when (owner.origin) {
      // for these synthetic variable declarations we want to avoid transforming them since
      // the compiler will rely on their compile time value in some cases.
      IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE -> expression
      IrDeclarationOrigin.IR_TEMPORARY_VARIABLE -> expression
      IrDeclarationOrigin.FOR_LOOP_VARIABLE -> expression
      else -> enter("set-$name") { super.visitSetValue(expression) }
    }
  }

  override fun visitSetField(expression: IrSetField): IrExpression {
    val name = expression.symbol.owner.name
    return enter("set-$name") { super.visitSetField(expression) }
  }

  override fun visitBlockBody(body: IrBlockBody): IrBody =
    siblings {
      super.visitBlockBody(body)
    }

  override fun visitVariable(declaration: IrVariable): IrStatement =
    enter("val-${declaration.name.asJvmFriendlyString()}") {
      super.visitVariable(declaration)
    }

  override fun visitProperty(declaration: IrProperty): IrStatement {
    if (declaration.hasNoLiveLiteralsAnnotation()) return declaration

    val name = declaration.name.asJvmFriendlyString()
    val getter = declaration.getter
    val setter = declaration.setter
    val backingField = declaration.backingField

    return enter("val-$name") {
      // turn them into live literals. We should consider transforming some simple cases like
      // `val foo = 123`, but in general turning this initializer into a getter is not a
      // safe operation. We should figure out a way to do this for "static" expressions
      // though such as `val foo = 16.dp`.
      //
      // backingField도 라이브 리터럴로 변환해야 합니다. `val foo = 123`과 같은 간단한 경우를
      // 변환하는 것을 고려해야 하지만, 일반적으로 이 초기화 함수를 게터로 변환하는 것은 안전한
      // 작업이 아닙니다. `val foo = 16.dp`와 같은 "정적" 표현식에 대해서도 이를 구현하는 방법을
      // 찾아야 합니다.
      declaration.backingField = backingField

      declaration.getter = enter("get") {
        getter?.transform(this, null) as? IrSimpleFunction
      }
      declaration.setter = enter("set") {
        setter?.transform(this, null) as? IrSimpleFunction
      }
      declaration
    }
  }

  inline fun IrProperty.addSetter(builder: IrFunctionBuilder.() -> Unit = {}): IrSimpleFunction =
    IrFunctionBuilder().run {
      name = Name.special("<set-${this@addSetter.name}>")
      builder()
      context.irFactory.buildFunction(this).also { setter ->
        this@addSetter.setter = setter
        setter.parent = this@addSetter.parent
      }
    }

  fun IrFactory.buildFunction(builder: IrFunctionBuilder): IrSimpleFunction =
    with(builder) {
      createSimpleFunction(
        startOffset = startOffset,
        endOffset = endOffset,
        origin = origin,
        name = name,
        visibility = visibility,
        isInline = isInline,
        isExpect = isExpect,
        returnType = returnType,
        modality = modality,
        symbol = IrSimpleFunctionSymbolImpl(),
        isTailrec = isTailrec,
        isSuspend = isSuspend,
        isOperator = isOperator,
        isInfix = isInfix,
        isExternal = isExternal,
        containerSource = containerSource,
        isFakeOverride = isFakeOverride,
      )
    }
}
