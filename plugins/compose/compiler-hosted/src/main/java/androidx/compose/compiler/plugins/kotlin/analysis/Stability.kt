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
    override fun toString(): String {
      return if (stable) "Stable" else "Unstable"
    }
  }

  // class Foo(val bar: ExternalType) -> ExternalType.$stable
  class Runtime(val declaration: IrClass) : Stability() {
    override fun toString(): String {
      return "Runtime(${declaration.name.asString()})"
    }
  }

  // interface Foo { fun result(): Int }
  class Unknown(val declaration: IrClass) : Stability() {
    override fun toString(): String {
      return "Uncertain(${declaration.name.asString()})"
    }
  }

  // class <T> Foo(val value: T)
  class Parameter(val parameter: IrTypeParameter) : Stability() {
    override fun toString(): String {
      return "Parameter(${parameter.name.asString()})"
    }
  }

  // class Foo(val foo: A, val bar: B)
  class Combined(val elements: List<Stability>) : Stability() {
    override fun toString(): String {
      return elements.joinToString(",")
    }
  }

  operator fun plus(other: Stability): Stability = when {
    other is Certain -> if (other.stable) this else other
    this is Certain -> if (stable) other else this
    else -> Combined(listOf(this, other))
  }

  operator fun plus(other: List<Stability>): Stability {
    var stability = this
    for (el in other) {
      stability += el
    }
    return stability
  }

  companion object {
    val Stable: Stability = Certain(true)
    val Unstable: Stability = Certain(false)
  }
}

fun Stability.knownUnstable(): Boolean = when (this) {
  is Stability.Certain -> !stable
  is Stability.Runtime -> false
  is Stability.Unknown -> false
  is Stability.Parameter -> false
  is Stability.Combined -> elements.any { it.knownUnstable() }
}

fun Stability.knownStable(): Boolean = when (this) {
  is Stability.Certain -> stable
  is Stability.Runtime -> false
  is Stability.Unknown -> false
  is Stability.Parameter -> false
  // 비어있는 Combined는 Stable로 간주
  is Stability.Combined -> elements.all { it.knownStable() }
}

fun Stability.isUncertain(): Boolean = when (this) {
  is Stability.Certain -> false
  is Stability.Runtime -> true
  is Stability.Unknown -> true
  is Stability.Parameter -> true
  is Stability.Combined -> elements.any { it.isUncertain() }
}

// expressible: 표현할 수 있는
fun Stability.isExpressible(): Boolean = when (this) {
  is Stability.Certain -> true
  is Stability.Runtime -> true
  is Stability.Unknown -> false
  is Stability.Parameter -> true
  is Stability.Combined -> elements.all { it.isExpressible() }
}

fun Stability.normalize(): Stability {
  when (this) {
    // if not combined, there is no normalization needed
    is Stability.Certain,
    is Stability.Parameter,
    is Stability.Runtime,
    is Stability.Unknown,
      -> return this

    is Stability.Combined -> {
      // if combined, we perform the more expensive normalization process
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
        if (!stability.stable)
          return Stability.Unstable
      }

      is Stability.Parameter -> {
        if (stability.parameter.symbol !in parameters) {
          parameters.add(stability.parameter.symbol)
          parts.add(stability)
        }
      }

      is Stability.Runtime -> parts.add(stability)
      is Stability.Unknown -> {
        /* do nothing */
      }
    }
  }
  return Stability.Combined(parts)
}

fun Stability.forEach(callback: (Stability) -> Unit) {
  if (this is Stability.Combined) {
    elements.forEach { it.forEach(callback) }
  } else {
    callback(this)
  }
}

fun IrAnnotationContainer.hasStableMarker(): Boolean =
  annotations.any { it.isStableMarker() }

private fun IrConstructorCall.isStableMarker(): Boolean =
  annotationClass?.owner?.hasAnnotation(ComposeFqNames.StableMarker) == true

private fun IrClass.hasStableMarkerDescendant(): Boolean {
  if (hasStableMarker()) return true
  return superTypes.any {
    !it.isAny() && it.classOrNull?.owner?.hasStableMarkerDescendant() == true
  }
}

private fun IrAnnotationContainer.stabilityInferredArgumentBitmask(): Int? =
  (annotations.findAnnotation(ComposeFqNames.StabilityInferred)
    ?.getValueArgument(0) as? IrConst
    )?.value as? Int

private data class SymbolForAnalysis(
  val symbol: IrClassifierSymbol,
  val typeParameters: List<IrTypeArgument?>,
)

class StabilityInferencer(
  private val currentModule: ModuleDescriptor,
  externalStableTypeMatchers: Set<FqNameMatcher>,
) {
  private val externalTypeMatcherCollection = FqNameMatcherCollection(externalStableTypeMatchers)

  fun stabilityOfType(irType: IrType): Stability =
    stabilityOfTypeImpl(type = irType, substitutions = emptyMap(), currentlyAnalyzing = emptySet())

  fun stabilityOfExpression(expr: IrExpression): Stability {
    // look at type first. if type is stable, whole expression is stable
    val baseStability = stabilityOfType(expr.type)
    if (baseStability.knownStable()) return baseStability

    return when (expr) {
      is IrConst -> Stability.Stable
      is IrCall -> stabilityOfCall(expr = expr, baseStability = baseStability)
      is IrGetValue -> {
        val owner = expr.symbol.owner
        if (owner is IrVariable && !owner.isVar) {
          owner.initializer?.let { stabilityOfExpression(it) } ?: baseStability
        } else {
          baseStability
        }
      }

      is IrLocalDelegatedPropertyReference -> Stability.Stable

      // some default parameters and consts can be wrapped in composite
      is IrComposite -> {
        if (expr.statements.all { it is IrExpression && stabilityOfExpression(it).knownStable() }) {
          Stability.Stable
        } else {
          baseStability
        }
      }

      else -> baseStability
    }
  }

  private fun stabilityOfCall(expr: IrCall, baseStability: Stability): Stability {
    val function = expr.symbol.owner
    val fqName = function.kotlinFqName

    return when (val mask = KnownStableConstructs.stableFunctions[fqName.asString()]) {
      null -> baseStability
      0 -> Stability.Stable
      else -> Stability.Combined(
        expr.typeArguments.indices.mapNotNull { index ->
          if (mask and (0b1 shl index) != 0) {
            val sub = expr.typeArguments[index]
            if (sub != null)
              stabilityOfType(sub)
            else
              Stability.Unstable
          } else null
        }
      )
    }
  }

  private fun stabilityOfTypeImpl(
    type: IrType,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability {
    return when {
      type is IrErrorType -> Stability.Unstable
      type is IrDynamicType -> Stability.Unstable

      type.isUnit() ||
        type.isPrimitiveType() ||
        type.isFunctionOrKFunction() ||
        type.isSyntheticComposableFunction() ||
        type.isString() -> Stability.Stable

      type.isTypeParameter() -> {
        // classifier로 Symbol을 가져옴 -> TypeParameter는 IrTypeParameterSymbol이 항상 있으므로 orFail를 사용
        val classifier = type.classifierOrFail
        val arg = substitutions[classifier]
        val symbol = SymbolForAnalysis(classifier, typeParameters = emptyList())
        if (arg != null && symbol !in currentlyAnalyzing) {
          stabilityOfStarProjectionOrTypeProjection(arg, substitutions, currentlyAnalyzing + symbol)
        } else {
          Stability.Parameter(classifier.owner as IrTypeParameter)
        }
      }

      type.isNullable() -> {
        stabilityOfTypeImpl(
          type = type.makeNotNull(),
          substitutions = substitutions,
          currentlyAnalyzing = currentlyAnalyzing
        )
      }

      // value class
      type.isInlineClassType() -> {
        val inlineClassDeclaration = type.getClass()
          ?: error("Failed to resolve the class definition of inline type $type")

        if (inlineClassDeclaration.hasStableMarker()) {
          Stability.Stable
        } else {
          stabilityOfTypeImpl(
            type = getInlineClassUnderlyingType(inlineClassDeclaration),
            substitutions = substitutions,
            currentlyAnalyzing = currentlyAnalyzing,
          )
        }
      }

      type is IrSimpleType -> {
        stabilityOfClassifier(
          classifier = type.classifier,
          substitutions = substitutions + type.substitutionMap(),
          currentlyAnalyzing = currentlyAnalyzing,
        )
      }

      // `typealias MyMap = Map<String, Int>` 같은 타입
      type is IrTypeAbbreviation -> {
        val aliased = type.typeAlias.owner.expandedType
        // TODO(lmr): figure out how type.arguments plays in here
        stabilityOfTypeImpl(type = aliased, substitutions = substitutions, currentlyAnalyzing = currentlyAnalyzing)
      }

      else -> error("Unexpected IrType: $type")
    }
  }

  private fun stabilityOfStarProjectionOrTypeProjection(
    argument: IrTypeArgument,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability {
    return when (argument) {
      is IrStarProjection -> Stability.Unstable
      is IrTypeProjection -> {
        stabilityOfTypeImpl(type = argument.type, substitutions = substitutions, currentlyAnalyzing = currentlyAnalyzing)
      }
    }
  }

  private fun stabilityOfClassifier(
    classifier: IrClassifierSymbol,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability {
    // if isEnum, return true
    // class hasStableAnnotation()
    return when (val owner = classifier.owner) {
      is IrClass -> {
        stabilityOfClass(
          declaration = owner,
          substitutions = substitutions,
          currentlyAnalyzing = currentlyAnalyzing
        )
      }
      // stabilityOfTypeImpl에서 TypeParameter의 안정성 검사가 진행된 이후 -> 항상 불안정한 걸로 간주
      is IrTypeParameter -> Stability.Unstable
      is IrScript -> Stability.Stable
      else -> error("Unexpected IrClassifier: $owner")
    }
  }

  // equals 및 hashCode 구현을 신경쓰지 않음??
  private fun stabilityOfClass(
    declaration: IrClass,
    substitutions: Map<IrTypeParameterSymbol, IrTypeArgument>,
    currentlyAnalyzing: Set<SymbolForAnalysis>,
  ): Stability {
    val symbol = declaration.symbol
    val typeArguments = declaration.typeParameters.map { substitutions[it.symbol] }
    val fullSymbol = SymbolForAnalysis(symbol, typeArguments)

    if (currentlyAnalyzing.contains(fullSymbol)) return Stability.Unstable
    if (declaration.hasStableMarkerDescendant()) return Stability.Stable
    if (declaration.isEnumClass || declaration.isEnumEntry) return Stability.Stable
    if (declaration.defaultType.isPrimitiveType()) return Stability.Stable
    if (declaration.isProtobufType()) return Stability.Stable

    if (declaration.origin == IrDeclarationOrigin.IR_BUILTINS_STUB) {
      error("Builtins Stub: ${declaration.name}")
    }

    val analyzing = currentlyAnalyzing + fullSymbol

    if (isKnownStableTypeOrExternalDeclaration(declaration) || declaration.isExternalStableType()) {
      val fqName = declaration.fqNameWhenAvailable?.toString() ?: ""
      val typeParameters = declaration.typeParameters
      val stability: Stability
      val mask: Int
      when {
        KnownStableConstructs.stableTypes.contains(fqName) -> {
          mask = KnownStableConstructs.stableTypes[fqName] ?: 0
          stability = Stability.Stable
        }
        declaration.isExternalStableType() -> {
          mask = externalTypeMatcherCollection.maskForName(declaration.fqNameWhenAvailable) ?: 0
          stability = Stability.Stable
        }
        declaration.isInterface && declaration.isInCurrentModule() -> {
          // trying to avoid extracting stability bitmask for interfaces in current module
          // to support incremental compilation
          return Stability.Unknown(declaration)
        }
        else -> {
          val bitmask = declaration.stabilityInferredArgumentBitmask() ?: return Stability.Unstable
          val knownStableMask = if (typeParameters.size < 32) 0b1 shl typeParameters.size else 0
          val isKnownStable = bitmask and knownStableMask != 0
          mask = bitmask and knownStableMask.inv()

          // supporting incremental compilation, where declaration stubs can be
          // in the same module, so we need to use already inferred values
          stability = if (isKnownStable && declaration.isInCurrentModule()) {
            Stability.Stable
          } else {
            Stability.Runtime(declaration)
          }
        }
      }
      return when {
        mask == 0 || typeParameters.isEmpty() -> stability
        else -> stability + Stability.Combined(
          typeParameters.mapIndexedNotNull { index, irTypeParameter ->
            if (index >= 32) return@mapIndexedNotNull null
            if (mask and (0b1 shl index) != 0) {
              val sub = substitutions[irTypeParameter.symbol]
              if (sub != null)
                stabilityOfStarProjectionOrTypeProjection(sub, substitutions, analyzing)
              else
                Stability.Parameter(irTypeParameter)
            } else null
          }
        )
      }
    } else if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB) {
      return Stability.Unstable
    }

    if (declaration.isInterface) {
      return Stability.Unknown(declaration)
    }

    var stability = Stability.Stable

    for (member in declaration.declarations) {
      when (member) {
        is IrProperty -> {
          member.backingField?.let {
            if (member.isVar && !member.isDelegated) return Stability.Unstable
            stability += stabilityOfTypeImpl(type = it.type, substitutions = substitutions, currentlyAnalyzing = analyzing)
          }
        }

        is IrField -> {
          stability += stabilityOfTypeImpl(type = member.type, substitutions = substitutions, currentlyAnalyzing = analyzing)
        }
      }
    }

    declaration.superClass?.let {
      stability += stabilityOfClass(declaration = it, substitutions = substitutions, currentlyAnalyzing = analyzing)
    }

    return stability
  }

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  private fun IrDeclaration.isInCurrentModule() =
    module == currentModule

  private fun IrClass.isProtobufType(): Boolean {
    // Quick exit as all protos are final
    if (!isFinalClass) return false
    val directParentClassName =
      superTypes.lastOrNull { !it.isInterface() }
        ?.classOrNull?.owner?.fqNameWhenAvailable?.toString()
    return directParentClassName == "com.google.protobuf.GeneratedMessageLite" ||
      directParentClassName == "com.google.protobuf.GeneratedMessage"
  }

  private fun IrClass.isExternalStableType(): Boolean {
    return externalTypeMatcherCollection.matches(fqNameWhenAvailable, superTypes)
  }

  private fun isKnownStableTypeOrExternalDeclaration(declaration: IrClass): Boolean {
    val fqName = declaration.fqNameWhenAvailable?.toString() ?: ""
    return KnownStableConstructs.stableTypes.contains(fqName) ||
      declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
  }

  private fun IrSimpleType.substitutionMap(): Map<IrTypeParameterSymbol, IrTypeArgument> {
    val cls = classOrNull ?: return emptyMap()
    val params = cls.owner.typeParameters.map { it.symbol }
    val args = arguments
    return params.zip(args).filter { (param, arg) ->
      param != (arg as? IrSimpleType)?.classifier
    }.toMap()
  }
}
