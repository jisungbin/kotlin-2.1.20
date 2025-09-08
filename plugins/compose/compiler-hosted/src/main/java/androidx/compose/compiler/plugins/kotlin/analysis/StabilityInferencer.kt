/*
 * Copyright 2020 The Android Open Source Project
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

// 원래 파일 이름: Stability.kt
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package androidx.compose.compiler.plugins.kotlin.analysis

import androidx.compose.compiler.plugins.kotlin.ComposeFqNames
import androidx.compose.compiler.plugins.kotlin.lower.annotationClass
import androidx.compose.compiler.plugins.kotlin.lower.isSyntheticComposableFunction
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeAbbreviation
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.findAnnotation
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getInlineClassUnderlyingType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isEnumClass
import org.jetbrains.kotlin.ir.util.isEnumEntry
import org.jetbrains.kotlin.ir.util.isFinalClass
import org.jetbrains.kotlin.ir.util.isFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isTypeParameter
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.module
import org.jetbrains.kotlin.ir.util.superClass

sealed class Stability {
  // class Foo(val bar: Int)
  class Certain(val stable: Boolean) : Stability() {
    override fun toString(): String = if (stable) "Stable" else "Unstable"
  }

  // class Foo(val bar: ExternalType) -> [ExternalType.$stable]로 안정성 추론 위임
  class Runtime(val declaration: IrClass) : Stability() {
    override fun toString(): String = "Runtime(${declaration.name.asString()})"
  }

  // interface Foo { fun result(): Int }
  class Unknown(val declaration: IrClass) : Stability() {
    override fun toString(): String = "Uncertain(${declaration.name.asString()})"
  }

  // class <T> Foo(val value: T)
  class Parameter(val typeParameter: IrTypeParameter) : Stability() {
    override fun toString(): String = "Parameter(${typeParameter.name.asString()})"
  }

  // class Foo(val foo: A, val bar: B)
  class Combined(val elements: List<Stability>) : Stability() {
    override fun toString(): String = elements.joinToString(",")
  }

  operator fun plus(other: Stability): Stability = when {
    other is Certain -> if (other.stable) this else other
    this is Certain -> if (stable) other else this // 하나라도 불안정하면 전체 안정성을 불안정하다고 간주
    else -> Combined(elements = listOf(this, other))
  }

  operator fun plus(other: List<Stability>): Stability {
    var stability = this
    for (el in other) {
      stability += el
    }
    return stability
  }

  companion object {
    val Stable: Stability = Certain(stable = true)
    val Unstable: Stability = Certain(stable = false)
  }
}

// MEMO Certain(false)일 때만 true이고, 나머진 다 false임.
//  즉, [불안정함]과 [안정하지 않음]은 서로 다른 의미임.
fun Stability.knownUnstable(): Boolean = when (this) {
  is Stability.Certain -> !stable
  is Stability.Runtime -> false
  is Stability.Unknown -> false
  is Stability.Parameter -> false
  is Stability.Combined -> elements.any { it.knownUnstable() }
}

// MEMO Certain(true)일 때만 true이고, 나머진 다 false임.
//  즉, [안정함]과 [불안정하지 않음]은 서로 다른 의미임.
fun Stability.knownStable(): Boolean = when (this) {
  is Stability.Certain -> stable
  is Stability.Runtime -> false
  is Stability.Unknown -> false
  is Stability.Parameter -> false

  // 비어있는 Combined는 Stable로 간주 (knownStable -> true)
  is Stability.Combined -> elements.all { it.knownStable() }
}

fun Stability.isUncertain(): Boolean = when (this) {
  is Stability.Certain -> false
  is Stability.Runtime -> true
  is Stability.Unknown -> true
  is Stability.Parameter -> true

  // 비어있는 Combined는 Stable로 간주 (isUncertain -> false)
  is Stability.Combined -> elements.any { it.isUncertain() }
}

fun Stability.normalize(): Stability {
  when (this) {
    // if not combined, there is no normalization needed.
    is Stability.Certain,
    is Stability.Parameter,
    is Stability.Runtime,
    is Stability.Unknown,
      -> return this

    is Stability.Combined -> {
      // if combined, we perform the more expensive normalization process.
    }
  }

  val parameters = mutableSetOf<IrTypeParameterSymbol>()
  val parts = mutableListOf<Stability>()
  val stack = mutableListOf<Stability>(this)

  while (stack.isNotEmpty()) {
    when (val stability: Stability = stack.removeAt(stack.size - 1)) {
      is Stability.Combined -> {
        stack.addAll(stability.elements)
      }

      is Stability.Certain -> {
        if (!stability.stable) return Stability.Unstable
      }

      is Stability.Parameter -> {
        if (stability.typeParameter.symbol !in parameters) {
          parameters.add(stability.typeParameter.symbol)
          parts.add(stability)
        }
      }

      is Stability.Runtime -> parts.add(stability)
      is Stability.Unknown -> {
        /* do nothing */
      }
    }
  }

  return Stability.Combined(elements = parts)
}

fun Stability.forEach(action: (Stability) -> Unit) {
  if (this is Stability.Combined) {
    elements.forEach { it.forEach(action) }
  } else {
    action(this)
  }
}

fun IrAnnotationContainer.hasStableMarker(): Boolean =
  annotations.any { it.isStableMarker() }

private fun IrConstructorCall.isStableMarker(): Boolean =
  annotationClass?.owner?.hasAnnotation(ComposeFqNames.StableMarker) == true

// descendant: 자손, 후손, 후예
private fun IrClass.hasStableMarkerDescendant(): Boolean {
  if (hasStableMarker()) return true
  return superTypes.any { superType ->
    !superType.isAny() &&
      superType.classOrNull?.owner?.hasStableMarkerDescendant() == true
  }
}

// 원래 이름: stabilityParamBitmask
private fun IrAnnotationContainer.stabilityInferredArgumentBitmask(): Int? =
  (annotations.findAnnotation(ComposeFqNames.StabilityInferred)
    ?.getValueArgument(0) as? IrConst)
    ?.value as? Int

private data class SymbolForAnalysis(
  val symbol: IrClassifierSymbol,
  val typeArguments: List<IrTypeArgument?>,
)

class StabilityInferencer(
  private val currentModule: ModuleDescriptor,
  externalStableTypeMatchers: Set<FqNameMatcher>,
) {
  private val externalTypeMatcherCollection = FqNameMatcherCollection(externalStableTypeMatchers)

  fun stabilityOfType(type: IrType): Stability =
    stabilityOfTypeImpl(type = type, substitutions = emptyMap(), currentlyAnalyzing = emptySet())

  fun stabilityOfExpression(expr: IrExpression): Stability {
    // look at type first. if type is stable, whole expression is stable.
    val baseStability = stabilityOfType(type = expr.type)
    if (baseStability.knownStable()) return baseStability

    return when (expr) {
      is IrConst -> Stability.Stable

      is IrCall -> stabilityOfCall(expr = expr, baseStability = baseStability)

      is IrGetValue -> {
        val owner = expr.symbol.owner
        if (owner is IrVariable && !owner.isVar) {
          // MEMO 변수 접근식의 안정성은, 변수 타입이 아니라 변수 초기화 식을 보고 추론됨.
          owner.initializer?.let { stabilityOfExpression(expr = it) } ?: baseStability
        } else {
          baseStability
        }
      }

      //   fun main() {
      //     var a by Delegates.notNull<Int>()
      //     println(a)
      //   }
      //
      // 위 코드는 아래처럼 컴파일됨:
      //
      //   fun main() {
      //     var a by {
      //       val a$delegate = Delegates.notNull()
      //       get() {
      //         return a$delegate.getValue(null, ::a$delegate)
      //       }
      //       set(<set-?>: Int) {
      //         return a$delegate.setValue(null, ::a$delegate, <set-?>)
      //       }
      //     }
      //     println(<get-a>())
      //   }
      //
      // 이렇게 프로퍼티 델리게이션은 컴파일될 때 'a$delegate' 처럼 델리게이션의 backing field가
      // 생성되는데, 이 'a$delegate'가 'IrLocalDelegatedPropertyReference'로 표현됨.
      // 즉, 이 IR은 오직 코틀린 컴파일러만 만들 수 있음. (직접 코틀린 코드를 작성하여 생성 불가)
      is IrLocalDelegatedPropertyReference -> Stability.Stable

      // some default parameters and consts can be wrapped in composite.
      is IrComposite -> {
        if (expr.statements.all { it is IrExpression && stabilityOfExpression(expr = it).knownStable() }) {
          Stability.Stable
        } else {
          baseStability
        }
      }

      else -> baseStability
    }
  }

  // MEMO 호출되는 함수에 붙은 @StableMarker은 'fun IrExpression.isStaticExpression(): Boolean'로 검사됨
  // MEMO baseStability는 'stabilityOfType(type = expr.type)'으로 추론됨
  //  -> 즉, Call의 반환 타입만이 안정성에 영향을 줄 수 있음
  private fun stabilityOfCall(expr: IrCall, baseStability: Stability): Stability {
    val function = expr.symbol.owner
    val fqName = function.kotlinFqName

    return when (val mask = KnownStableConstructs.stableFunctions[fqName.asString()]) {
      null -> baseStability
      0 -> Stability.Stable
      else -> Stability.Combined(
        elements = expr.typeArguments.indices.mapNotNull { index ->
          // 1이라면: "해당 위치의 제네릭 유형도 안정된 유형이 아니면 구조체가 안정된 것으로 간주할 수 없음을 나타냅니다."
          if (mask and (0b1 shl index) != 0) {
            val subject = expr.typeArguments[index]
            if (subject != null) stabilityOfType(type = subject) else Stability.Unstable
          } else null
        },
      )
    }
  }

  // MEMO type의 type argument까지는 검사하지 않음?
  private fun stabilityOfTypeImpl(
    type: IrType,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability =
    when {
      type is IrErrorType -> Stability.Unstable
      type is IrDynamicType -> Stability.Unstable

      type.isUnit() ||
        type.isPrimitiveType() ||
        type.isString() ||
        type.isFunctionOrKFunction() ||
        type.isSyntheticComposableFunction()
        -> Stability.Stable

      type.isTypeParameter() -> {
        // classifier로 Symbol을 가져옴 -> TypeParameter는 IrTypeParameterSymbol이 항상 있으므로 orFail를 사용
        val classifier = type.classifierOrFail
        val arg = substitutions[classifier]
        val symbol = SymbolForAnalysis(symbol = classifier, typeArguments = emptyList())

        if (arg != null && symbol !in currentlyAnalyzing) {
          stabilityOfProjection(
            argument = arg,
            substitutions = substitutions,
            currentlyAnalyzing = currentlyAnalyzing + symbol,
          )
        } else {
          Stability.Parameter(typeParameter = classifier.owner as IrTypeParameter)
        }
      }

      type.isNullable() -> {
        stabilityOfTypeImpl(
          type = type.makeNotNull(),
          substitutions = substitutions,
          currentlyAnalyzing = currentlyAnalyzing,
        )
      }

      // value class
      type.isInlineClassType() -> {
        val cls = type.getClass() ?: error("Failed to resolve the class definition of inline type $type")

        if (cls.hasStableMarker())
          Stability.Stable
        else
          stabilityOfTypeImpl(
            type = getInlineClassUnderlyingType(irClass = cls),
            substitutions = substitutions,
            currentlyAnalyzing = currentlyAnalyzing,
          )
      }

      type is IrSimpleType -> {
        stabilityOfClassifier(
          classifier = type.classifier,
          substitutions = substitutions + type.substitutionMap(), // TypeArgument 검사의 진입점
          currentlyAnalyzing = currentlyAnalyzing,
        )
      }

      // `typealias MyMap = Map<String, Int>` 같은 타입
      // [KT-78482 Drop unused fields from IrSimpleType] 작업으로 이 분기 사라짐
      type is IrTypeAbbreviation -> {
        stabilityOfTypeImpl(
          type = type.typeAlias.owner.expandedType,
          substitutions = substitutions,
          currentlyAnalyzing = currentlyAnalyzing,
        )
      }

      else -> error("Unexpected IrType: $type")
    }

  private fun stabilityOfProjection(
    argument: IrTypeArgument,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability =
    when (argument) {
      is IrStarProjection -> Stability.Unstable
      is IrTypeProjection -> {
        stabilityOfTypeImpl(
          type = argument.type,
          substitutions = substitutions,
          currentlyAnalyzing = currentlyAnalyzing,
        )
      }
    }

  private fun stabilityOfClassifier(
    classifier: IrClassifierSymbol,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability =
    when (val owner = classifier.owner) {
      is IrClass -> {
        stabilityOfClass(
          declaration = owner,
          substitutions = substitutions,
          currentlyAnalyzing = currentlyAnalyzing,
        )
      }

      // stabilityOfTypeImpl에서 TypeParameter의 안정성 검사가 진행된 이후 -> 항상 불안정한 걸로 간주
      is IrTypeParameter -> Stability.Unstable

      // 하나의 클래스를 갖는 코틀린 파일로 컴파일됨
      is IrScript -> Stability.Stable

      else -> error("Unexpected IrClassifier: $owner")
    }

  // equals 및 hashCode 구현은 신경쓰지 않음
  private fun stabilityOfClass(
    declaration: IrClass,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability {
    val symbol = declaration.symbol
    val typeArguments = declaration.typeParameters.map { substitutions[it.symbol] }
    val fullSymbol = SymbolForAnalysis(symbol = symbol, typeArguments = typeArguments)

    // MEMO `class A(val a: A)` 처럼 내가 나를 참조하고 있을 때 Unstable로 추론됨
    if (fullSymbol in currentlyAnalyzing) return Stability.Unstable

    // MEMO 상속 타입이 Stable해도 현재 클래스가 Stable로 추론됨
    if (declaration.hasStableMarkerDescendant()) return Stability.Stable

    if (declaration.isEnumClass || declaration.isEnumEntry) return Stability.Stable
    if (declaration.defaultType.isPrimitiveType()) return Stability.Stable
    if (declaration.isProtobufType()) return Stability.Stable

    if (declaration.origin == IrDeclarationOrigin.IR_BUILTINS_STUB) {
      error("Builtins Stub: ${declaration.name}")
    }

    val analyzing = currentlyAnalyzing + fullSymbol

    // KnownStableConstructs에 있거나, 외부 모듈에 정의되었거나,
    // ExternalStableType인 경우
    if (
      isKnownStableTypeOrExternalDeclaration(declaration = declaration) ||
      declaration.isExternalStableType()
    ) {
      val fqName = declaration.fqNameWhenAvailable?.toString().orEmpty()
      val typeParameters = declaration.typeParameters
      val stability: Stability
      val mask: Int

      when {
        fqName in KnownStableConstructs.stableTypes -> {
          mask = KnownStableConstructs.stableTypes[fqName]!!
          stability = Stability.Stable
        }

        declaration.isExternalStableType() -> {
          mask = externalTypeMatcherCollection.maskForName(fqName = declaration.fqNameWhenAvailable) ?: 0
          stability = Stability.Stable
        }

        declaration.isInterface && declaration.isInCurrentModule() -> {
          // trying to avoid extracting stability bitmask for interfaces in current module
          // to support incremental compilation.
          //
          // 현재 모듈의 인터페이스에 대해서는 안정성 비트마스크 추출을 피하려고 합니다.
          // 이는 증분 컴파일을 지원하기 위함입니다.
          return Stability.Unknown(declaration = declaration)
        }

        // 외부 모듈에 정의된 경우
        else -> {
          // 안정성 추론이 가능한 typeParameter 인덱스의 비트를 1로 설정함
          // 클레스가 안정으로 추론됐다면 typeParameters.size 인덱스의 비트를 1로 설정함
          //
          // MEMO List처럼 컴포즈 컴파일러가 없는 외부 타입은 @StabilityInferred가 없으므로 항상 Unstable로 추론됨
          val stabilityInferredBitmask = declaration.stabilityInferredArgumentBitmask() ?: return Stability.Unstable

          // 1 000 000
          val knownStableMask = if (typeParameters.size < 32) 0b1 shl typeParameters.size else 0

          // stabilityInferredBitmask의 MSB가 1이라면 안정적인 타입임
          val isKnownStable = stabilityInferredBitmask and knownStableMask != 0

          // knownStableMask.inv(): 0 111 111 -> typeParameter의 개수만큼 0b1로 채움
          //                                     (KnownStableConstructs의 비트마스킹과 동일한 형태)
          mask = stabilityInferredBitmask and knownStableMask.inv()

          // supporting incremental compilation, where declaration stubs can be
          // in the same module, so we need to use already inferred values.
          //
          // 증분 컴파일을 지원하기 위해, 선언 스텁이 같은 모듈 안에 있을 수 있으므로
          // 이미 추론된 값을 사용해야 합니다.
          stability = if (isKnownStable && declaration.isInCurrentModule()) {
            Stability.Stable
          } else {
            // MEMO Runtime 추론의 유일한 공간
            Stability.Runtime(declaration = declaration)
          }
        }
      }

      // stabilityOfClass 자체를 끝내는 return
      return when {
        mask == 0 || typeParameters.isEmpty() -> stability
        else -> stability + Stability.Combined(
          elements = typeParameters.mapIndexedNotNull { index, typeParameter ->
            if (index >= 32) return@mapIndexedNotNull null
            if (mask and (0b1 shl index) != 0) { // typeParameter의 mask가 1이라면
              val typeArgument = substitutions[typeParameter.symbol]
              if (typeArgument != null)
                stabilityOfProjection(
                  argument = typeArgument,
                  substitutions = substitutions,
                  currentlyAnalyzing = analyzing,
                )
              else
                Stability.Parameter(typeParameter = typeParameter)
            } else null // typeParameter의 mask가 0이라면 항상 Stable함 (해당하는 typeParameter 없음)
          },
        )
      }
    }

    // KnownStableConstructs에 없거나, 외부 모듈에 정의되지 않았거나,
    // ExternalStableType이 아닌 경우
    else {
      if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB) {
        return Stability.Unstable
      }
    }

    if (declaration.isInterface) {
      return Stability.Unknown(declaration = declaration)
    }

    var stability = Stability.Stable

    for (member in declaration.declarations) {
      when (member) {
        is IrProperty -> {
          // MEMO backingField가 있을 때만 안정성 추론 가능
          //  즉, Unstable한 타입인 프로퍼티라도 backingField가 없다면 Stable로 추론됨.
          //  ```
          //  var a: Any by mutableStateOf(Any())
          //  ```
          //  일 때 `a.returnType`은 `Any`이지만, `a.backingField.returnType`은 `MutableState`임
          //
          // MEMO delegated가 아닌 var 프로퍼티는 항상 불안정하지만, delegated인 var 프로퍼티는
          //  안정할 수 있음. val은 delegated와 무관하게 안정할 수 있음.
          member.backingField?.let { backingField ->
            if (member.isVar && !member.isDelegated) return Stability.Unstable

            // MEMO Stability.Parameter로 추론될 수 있는 진입점 (실제로 쓰이는 typeParameter만 검사하면 됨)
            stability += stabilityOfTypeImpl(
              type = backingField.type,
              substitutions = substitutions,
              currentlyAnalyzing = analyzing,
            )
          }
        }

        // $stable 필드, class delegation으로 컴파일 타임에 생성되는 필드 외에는 다 IrProperty임
        is IrField -> {
          // Stability.Parameter로 추론될 수 있는 진입점 (실제로 쓰이는 typeParameter만 검사하면 됨)
          stability += stabilityOfTypeImpl(
            type = member.type,
            substitutions = substitutions,
            currentlyAnalyzing = analyzing,
          )
        }
      }
    }

    declaration.superClass?.let { superClass ->
      stability += stabilityOfClass(
        declaration = superClass,
        substitutions = substitutions,
        currentlyAnalyzing = analyzing,
      )
    }

    return stability
  }

  // MEMO IC 단계? 확인 로직
  @OptIn(ObsoleteDescriptorBasedAPI::class)
  private fun IrDeclaration.isInCurrentModule(): Boolean =
    module == currentModule

  private fun IrClass.isProtobufType(): Boolean {
    // Quick exit as all protos are final.
    if (!isFinalClass) return false

    val directParentClassName =
      superTypes.lastOrNull { !it.isInterface() }?.classOrNull?.owner?.fqNameWhenAvailable?.toString()

    return directParentClassName == "com.google.protobuf.GeneratedMessageLite" ||
      directParentClassName == "com.google.protobuf.GeneratedMessage"
  }

  private fun IrClass.isExternalStableType(): Boolean =
    externalTypeMatcherCollection.matches(name = fqNameWhenAvailable, superTypes = superTypes)

  // IR_EXTERNAL_DECLARATION_STUB: ExternalStableType 검사 가능
  private fun isKnownStableTypeOrExternalDeclaration(declaration: IrClass): Boolean {
    val fqName = declaration.fqNameWhenAvailable?.toString().orEmpty()

    return fqName in KnownStableConstructs.stableTypes ||
      declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
  }

  // substitution은 ‘대체’ 또는 ‘치환’이라는 뜻으로, 사람이나 사물을 다른 사람이나 사물로 바꾸는 행위,
  // 또는 어떤 것을 다른 것으로 바꾸어 넣는 것을 의미합니다.
  private fun IrSimpleType.substitutionMap(): Map<IrTypeParameterSymbol, IrTypeArgument> {
    val cls = this.classOrNull ?: return emptyMap()
    val params: List<IrTypeParameterSymbol> = cls.owner.typeParameters.map(IrTypeParameter::symbol)
    val args: List<IrTypeArgument> = this.arguments

    return params.zip(args)
      // 'class Wrapper<T>(value: T)'에서 Wrapper 클래스 타입의 <T> param과 <T> arg는 동일한 T임.
      // 'Wrapper<Int>(1)' 표현식 타입의 <T> param은 T이고, <T> arg는 Int임.
      //
      // 첫 번째 상황은 제외하고, 오직 두 번째 상황만 남기는 필터 로직.
      .filter { (param, arg) -> param != (arg as? IrSimpleType)?.classifier }
      .toMap()
  }
}
