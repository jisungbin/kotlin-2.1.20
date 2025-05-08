/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.compose.compiler.plugins.kotlin.inference

/**
 * An adapter that allows [ApplierInferencer] to determine the declared [Scheme] for a type.
 */
interface TypeAdapter<Type> {
  /**
   * [Type]의 초기 [Scheme]을 정의합니다.
   */
  fun declaredSchemaOf(type: Type): Scheme

  /**
   * Given a type, return the last updated scheme. This is used to reduce the number of times
   * that [updatedInferredScheme] is called. Returning `null` will prevent
   * [updatedInferredScheme] from being called at all (for example, from the front-end which
   * ignores updates). If not caching to prevent [updatedInferredScheme] from being called too
   * often, return [declaredSchemaOf] instead. This will then call [updatedInferredScheme]
   * whenever it is different from what was declared.
   *
   * 주어진 [타입][type]의 마지막으로 계산된 [Scheme]를 반환합니다. 이 함수는 [updatedInferredScheme]가
   * 호출되는 빈도를 줄이기 위해 사용됩니다. `null`이 반환되면 어떠한 경우에도 [updatedInferredScheme]이
   * 호출되지 않습니다. (예: 업데이트를 무시하는 프런트엔드에서). [updatedInferredScheme]가 너무
   * 자주 호출되는 것을 방지하기 위해 캐싱하지 않는 경우, 대신 [declaredSchemaOf]를 반환합니다.
   * 그러면 선언된 것과 다를 때마다 [updatedInferredScheme]이 호출됩니다.
   */
  // STUDY 이게 먼 말이니?? 구현체와 사용처를 보고 다시 이해해 보기.
  fun currentInferredSchemeOf(type: Type): Scheme?

  /**
   * [Type]의 [Scheme]이 업데이트되었을 때 호출됩니다.
   */
  fun updatedInferredScheme(type: Type, scheme: Scheme)
}

/**
 * The kind of node tells the inferencer how treat the node.
 */
enum class NodeKind {
  // The node is a function declaration
  Function,

  // The node is a lambda
  Lambda,

  // The node is a reference to a parameter
  // STUDY composable lambda reference?
  ParameterReference,

  // The node is a variable declaration
  // STUDY composable lambda variable?
  Variable,

  // The node is not special
  Expression,
}

/**
 * An adapter that allows getting information about a node.
 */
interface NodeAdapter<Type, Node> {
  /**
   * Return the container of the node. A container is the function or lambda the node is part of.
   *
   * 노드의 컨테이너를 반환합니다. 컨테이너는 노드가 속한 함수 또는 람다입니다.
   */
  fun containerOf(node: Node): Node

  /**
   * Return the kind of the node that allows the node to be treated correctly by the inferencer.
   *
   * 노드가 추론기에 의해 올바르게 처리될 수 있는 노드의 종류를 반환합니다.
   */
  fun kindOf(node: Node): NodeKind

  /**
   * Return which parameter index this parameter references. The [node] passed in will only be
   * one for [kindOf] returns [NodeKind.ParameterReference].
   *
   * For parameter nodes the inferencer needs to determine which parameter of the scheme of the
   * [node] is being referenced to allow the scheme determined for the usage of the parameter to
   * infer the scheme of the parameter.
   *
   * 이 매개변수가 참조하는 매개변수 인덱스를 반환합니다. [NodeKind.ParameterReference] 타입의
   * [Node]만 허용됩니다.
   *
   * 매개변수 노드의 경우 추론자는 매개변수의 사용처를 결정하여 매개변수의 스키마를 추론할 수
   * 있도록 [node] 스키마의 어떤 매개변수가 참조되는지 확인해야 합니다.
   */
  fun schemeParameterIndexOf(node: Node, container: Node): Int

  /**
   * Return an instance of type where [Type] is the type passed to the [TypeAdapter] of the
   * inferencer.
   *
   * 타입의 인스턴스를 반환하며, 여기서 타입은 추론기의 타입 어댑터에 전달된 타입입니다.
   */
  fun typeOf(node: Node): Type?

  /**
   * When [node] is the target of a call this should return the container (e.g. the function or
   * lambda) being called. Otherwise, return `null`.
   *
   * 노드가 호출 대상인 경우 호출되는 컨테이너(예: 함수 또는 람다)를 반환해야 합니다.
   * 그렇지 않으면 null을 반환합니다.
   */
  fun referencedContainerOf(node: Node): Node?
}

/**
 * An adapter that can be used to adjust where temporary information about function types are stored.
 *
 * 기능 유형에 대한 임시 정보가 저장되는 위치를 조정하는 데 사용할 수 있는 어댑터입니다.
 */
interface LazySchemeStorage<Node> {
  /**
   * Retrieve a lazy scheme from the store (such as a mutableMapOf<Node, LazyScheme>().
   */
  fun getLazyScheme(node: Node): LazyScheme?

  /**
   * Store the lazy scheme [value] for [node].
   */
  fun storeLazyScheme(node: Node, value: LazyScheme)
}

/**
 * An adapter that is used to report errors detected during applier inference.
 */
interface ErrorReporter<Node> {
  /**
   * Report a call node applier is not correct.
   *
   * Applier 그룹 안에서 다른 Applier를 사용하는 컴포저블이 호출된 경우
   */
  fun reportCallError(node: Node, expected: String, received: String)

  /**
   * Report that the value or lambda passed to a parameter to a call was not correct.
   *
   * Applier 그룹 안에서 다른 Applier를 사용하는 컴포저블을 매개변수로 전달한 경우
   */
  fun reportParameterError(node: Node, index: Int, expected: String, received: String)

  /**
   * Log internal errors detected that indicate problems in the inference algorithm or when the
   * adapters violate an internal constraint such as the schemes are not the same shape for the
   * target of a call.
   *
   * 추론 알고리즘에 문제가 있거나 [Scheme]이 호출 대상과 동일한 모양이 아닌 경우와 같이 내부
   * 제약 조건을 위반하는 경우 감지된 내부 오류를 기록합니다.
   */
  fun log(node: Node?, message: String)
}

/**
 * The applier inference. This class can infer the token of the applier from the information
 * passed to [visitCall] and [visitVariable] given the adapters provided in the constructor.
 *
 * The inferencer uses [unification][https://en.wikipedia.org/wiki/Unification_(computer_science)]
 * to infer the applier token similar to how type inference uses unification to infer types in a
 * functional programming language (e.g. ML or Haskell).
 *
 * Only calls and variables are required as operators and property references can be treated as
 * calls (as Kotlin does). Control flow (other than the functions and calls themselves) are not
 * used to determine the applier as the applier can only be supplied as a parameter to a call and
 * cannot be influenced by control-flow other than a call. The inferencer does not need to be
 * informed about control-flow directly, just the informed of the variables and calls they
 * contain. If necessary, even control flow can be reduced to function calls (such as is done in
 * lambda calculus) but, this is not necessary for Kotlin.
 *
 * [ApplierInferencer] is open to allow it to infer appliers using either the front-end AST or
 * back-end IR nodes as well as allows for easier testing and debugging of the itself algorithm
 * without requiring either tree.
 *
 * ===========================================================================
 *
 * 이 클래스는 생성자에 제공된 어댑터를 기반으로 [visitCall]과 [visitVariable]에 전달된 정보에서
 * Applier의 토큰을 추론할 수 있습니다.
 *
 * Inferencer는 함수형 프로그래밍 언어(예: ML 또는 Haskell)에서 타입 추론이 통일성을 사용하여
 * 타입을 추론하는 방식과 유사하게 통일성을 사용하여 Applier 토큰을 추론합니다.
 *
 * 연산자로 호출(call)과 변수(variable)만 필요하며, property reference는 Kotlin처럼 호출(call)로
 * 처리할 수 있습니다. Applier는 호출의 매개변수로만 제공될 수 있고 호출 이외의 제어 흐름의 영향을
 * 받을 수 없으므로 제어 흐름(함수 및 호출 자체 제외)은 Applier를 결정하는 데 사용되지 않습니다.
 * 추론자는 제어 흐름에 대해 직접 알 필요가 없으며, 변수와 호출에 포함된 정보만 알면 됩니다.
 * 필요한 경우 제어 흐름도 함수 호출로 축소할 수 있지만(람다 계산에서처럼), Kotlin에서는 필요하지
 * 않습니다.
 *
 * [ApplierInferencer]는 프런트엔드 AST나 백엔드 IR 노드를 사용하여 적용자를 추론할 수 있도록 열려
 * 있으며, 두 트리 중 어느 것도 필요하지 않아 자체 알고리즘의 테스트와 디버깅을 더 쉽게 해줍니다.
 */
// 와우 구글 엔지니어 짱이다
class ApplierInferencer<Type, Node>(
  private val typeAdapter: TypeAdapter<Type>,
  private val nodeAdapter: NodeAdapter<Type, Node>,
  private val lazySchemeStorage: LazySchemeStorage<Node>,
  private val errorReporter: ErrorReporter<Node>,
) {
  // A set of nodes that are currently being evaluated to prevent recursive evaluations.
  // 재귀 평가를 방지하기 위해 현재 평가 중인 노드 집합입니다.
  private val inProgress = mutableSetOf<Node>()

  // A list of visits to be re-evaluated if the inferencer produces a more refined scheme for
  // one of the LazySchemes referenced during inference.
  //
  // 추론자가 추론 중에 참조한 LazyScheme 중 하나에 대해 더 정교한 스키마를 생성하는 경우
  // 재평가할 방문 목록입니다.
  private val pending = mutableListOf<() -> Boolean>()

  // Produce a token that can be used in error messages.
  private val Binding.safeToken: String get() = token ?: "unbound"

  /**
   * Infer the scheme of the variable from the scheme of the initializer.
   *
   * [initializer]의 스키마에서 [variable]의 스키마를 추론합니다.
   */
  fun visitVariable(variable: Node, initializer: Node) =
    restartable(variable) { bindings, _, callBindingsOf ->
      val initializerBinding = callBindingsOf(initializer) ?: return@restartable
      val variableBindings = callBindingsOf(variable) ?: return@restartable

      // Unify the initializer with the variable as must have the same scheme.
      // Any use of the variable validates or determines the scheme of the initializer.
      //
      // initializer는 반드시 동일한 체계를 가져야 하므로 변수와 initializer를 통합합니다.
      // 변수를 사용하면 initializer의 스키마가 유효성을 검사하거나 결정됩니다.
      bindings.unify(call = variable, a = variableBindings, b = initializerBinding)
    }

  /**
   * Infer the scheme of the container the target and the arguments of the call. This also infers
   * a scheme for the call when it is used as an argument or variable initializer.
   *
   * 호출의 대상과 인수가 되는 컨테이너의 스키마를 추론합니다. 또한 인자 또는 변수 initializer로
   * 사용되는 경우 호출에 대한 스키마도 추론합니다.
   */
  fun visitCall(call: Node, target: Node, arguments: List<Node>) =
    restartable(call) { bindings, currentApplier, callBindingsOf ->
      // Produce the call bindings implied by the target of the call.
      val targetCallBindings = callBindingsOf(target) ?: run {
        errorReporter.log(call, "Cannot find target")
        return@restartable
      }

      // Produce the call bindings implied by the call and its arguments
      val parameters = arguments.map { callBindingsOf(it) }
      if (parameters.any { it == null }) {
        errorReporter.log(call, "Cannot determine a parameter scheme")
        return@restartable
      }

      val result = if (targetCallBindings.result != null) {
        callBindingsOf(call)
      } else null

      val callBinding = CallBindings(
        target = currentApplier,
        parameters = parameters.filterNotNull(),
        result = result,
        anyParameters = false
      )

      // Unify the call bindings. They should unify to the same bindings or there is an
      // error in the source.
      //
      //  호출 바인딩을 통합합니다. 동일한 바인딩으로 통합해야 하며 그렇지 않으면 소스에
      //  오류가 있는 것입니다.
      bindings.unify(call, callBinding, targetCallBindings)

      // Assume all lambdas that are not explicitly bound, capture the applier. This handles
      // the case of, for example, `strings.forEach { Text(it) }` where the lambda passed to
      // `forEach` captures the applier.
      //
      // 명시적으로 바인딩되지 않은 모든 람다를 가정하고 Applier를 캡처합니다. 예를 들어
      // `strings.forEach { Text(it) }`에서 `forEach`로 전달된 람다가 Applier를 캡처하는 경우를
      // 처리합니다.
      if (callBinding.parameters.size == arguments.size) {
        arguments.forEachIndexed { index, argument ->
          if (nodeAdapter.kindOf(argument) == NodeKind.Lambda) {
            val parameter = callBinding.parameters[index]
            val lambdaTarget = parameter.target
            if (lambdaTarget.token == null) {
              bindings.unify(a = lambdaTarget, b = currentApplier)
            }
          }
        }
      }

      // Try communicate resolved target bindings to lambda's type to produce more accurate
      // errors. This cause an error to be produced in the lambda if the lambda applier is
      // not what the parameter requires instead on the lambda itself.
      //
      // 더 정확한 오류를 생성하려면 해결된 대상 바인딩을 람다의 타입에 전달해 보세요.
      // 이렇게 하면 람다 적용자가 매개변수에 필요한 내용이 아닌 경우 람다 자체에서 오류가
      // 발생합니다.
      for ((parameterBinding, argument) in callBinding.parameters.zip(arguments)) {
        if (
          nodeAdapter.kindOf(argument) == NodeKind.Lambda &&
          parameterBinding.target.token != null
        ) {
          val lambdaScheme = argument.toLazyScheme()
          if (lambdaScheme.target.token == null) {
            lambdaScheme.bindings.unify(lambdaScheme.target, parameterBinding.target)
          }
        }
      }
    }

  /**
   * For testing, produce the scheme inferred or the scheme from the declaration.
   *
   * 테스트를 위해 추론된 스키마 또는 선언에서 스키마를 생성합니다.
   */
  fun toFinalScheme(node: Node) = node.toLazyScheme().toScheme()

  /**
   * Perform structural unification of two call bindings. All bindings that are in the same
   * structural place must unify or there is an error in the source. That is the targets are
   * unified and the parameter call bindings are unified recursively as well as the call
   * binding of the result. If [call] is `null` then the error is reported by the caller
   * instead. For example, failing to unify the parameters of a call binding should be
   * considered a failure to unify the entire binding not just the parameter.
   *
   * 두 호출 바인딩의 구조적 통합을 수행합니다. 동일한 구조적 위치에 있는 모든 바인딩은
   * 통합되어야 하며, 그렇지 않으면 소스에 오류가 발생합니다. 즉, target이 통합되고 매개변수
   * 호출 바인딩과 result의 호출 바인딩이 재귀적으로 통합됩니다. [call]이 null이면 호출자가
   * 오류를 보고합니다. 예를 들어, 호출 바인딩의 매개변수를 통합하지 못하면 매개변수뿐만
   * 아니라 전체 바인딩을 통합하지 못한 것으로 간주해야 합니다.
   */
  private fun Bindings.unify(call: Node?, a: CallBindings, b: CallBindings): Boolean {
    if (!unify(a.target, b.target)) {
      if (call != null) {
        val aName = a.target.safeToken
        val bName = b.target.safeToken
        errorReporter.reportCallError(call, aName, bName)
      }
      return false
    }

    val count =
      if (a.parameters.size != b.parameters.size) {
        if (call != null) errorReporter.log(call, "Type disagreement $a <=> $b")

        if (a.parameters.size > b.parameters.size)
          b.parameters.size
        else
          a.parameters.size
      } else {
        a.parameters.size
      }

    for (i in 0 until count) {
      val aParameter = a.parameters[i]
      val bParameter = b.parameters[i]

      if (!unify(call = null, a = aParameter, b = bParameter)) {
        if (call != null) {
          val aToken = aParameter.target.token
          val bToken = bParameter.target.token

          if (aToken != null && bToken != null) {
            errorReporter.reportParameterError(
              node = call,
              index = i,
              expected = bParameter.target.token!!,
              received = aParameter.target.token!!
            )
          } else {
            unify(call, aParameter, bParameter)
          }
        }
      }
    }

    val aResult = a.result
    val bResult = b.result
    if (aResult != null && bResult != null) {
      // Disagreement in whether a result is used is ignored but if both are present then
      // they must unify. This is because it is often unclear, when the result is unused,
      // whether an expression has a result or not.
      //
      // 결과의 사용 여부에 대한 불일치는 무시되지만 둘 다 존재하는 경우 통합해야 합니다.
      // 결과가 사용되지 않은 경우 표현식에 결과가 있는지 여부가 불분명한 경우가 많기 때문입니다.
      return unify(null, aResult, bResult)
    }

    return true
  }

  /**
   * Restart [block] if a [LazyScheme] used to produce a [CallBindings] changes. This also
   * informs the [TypeAdapter] when the inferencer infers a refinement of the scheme for the type
   * of the container of [node].
   *
   * CallBindings 생성에 사용된 LazyScheme이 변경되면 블록을 다시 시작합니다.
   * 또한 추론기가 노드 컨테이너 유형에 대한 스키마의 세부 사항을 추론할 때 TypeAdapter에 이를 알립니다.
   */
  private fun restartable(
    node: Node,
    block: (Bindings, Binding, (Node) -> CallBindings?) -> Unit,
  ): Boolean {
    if (node in inProgress) return false
    inProgress.add(node)

    try {
      val container = nodeAdapter.containerOf(node)
      val containerLazyScheme = container.toLazyScheme()
      val bindings = containerLazyScheme.bindings

      fun observed(lazyScheme: LazyScheme): LazyScheme {
        if (lazyScheme.bindings != bindings && !lazyScheme.closed) {
          // This scheme might change as more calls are processed so observe the changes
          // that could cause this call's result to change.
          //
          // 더 많은 호출이 처리됨에 따라 이 Scheme은 변경될 수 있으므로 이 호출의 결과가
          // 변경될 수 있는 변경 사항을 살펴보세요.
          var remove = {}
          val result: () -> Unit = {
            if (node !in inProgress) {
              remove()
              pending.add { restartable(node, block) }
            }
          }
          remove = lazyScheme.onChange(result)
        }

        return lazyScheme
      }

      fun schemeOf(node: Node): Scheme =
        observed(node.toLazyScheme()).toScheme()

      fun callBindingsOf(node: Node): CallBindings? =
        when (nodeAdapter.kindOf(node)) {
          // For parameters we extract the part of the lazy scheme associated with
          // the parameter being referenced.
          //
          // 매개변수의 경우 참조되는 매개변수와 관련된 지연 스키마의 일부를 추출합니다.
          NodeKind.ParameterReference -> {
            val parameterContainer = nodeAdapter.containerOf(node)
            val parameterContainerLazyScheme = parameterContainer.toLazyScheme()
            val parameterContainerScheme = nodeAdapter.schemeParameterIndexOf(node, parameterContainer)

            if (parameterContainerScheme !in parameterContainerLazyScheme.parameters.indices) {
              null
            }

            parameterContainerLazyScheme
              .parameters[parameterContainerScheme]
              .toCallBindings()
          }

          // Lambdas, variables and expression all bind in the current
          // binding context. That is, all uses of these nodes must agree on
          // a token scheme.
          //
          // 람다, 변수, 표현식은 모두 현재 바인딩 컨텍스트에서 바인딩됩니다.
          // 즉, 이러한 노드를 사용하는 모든 사용자는 토큰 체계에 동의해야 합니다.
          NodeKind.Lambda, NodeKind.Variable, NodeKind.Expression ->
            observed(node.toLazyScheme(bindings)).toCallBindings()

          // Function calls are a point of let-bound polymorphism (this is, the open
          // parameters of the function bind independently of the function itself
          // as functions with open variables is polymorphic) so the scheme of the
          // function is given unique binding variables for any open variables.
          //
          // 함수 호출은 let-bound 다형성의 지점입니다(즉, 함수의 open 매개변수는
          // open 변수를 가진 함수처럼 함수 자체와 독립적으로 바인딩됩니다. 따라서
          // 함수의 체계에는 모든 열린 변수에 대해 고유한 바인딩 변수가 제공됩니다).
          NodeKind.Function -> schemeOf(node).toCallBindings(bindings)
        }

      block(bindings, containerLazyScheme.target, ::callBindingsOf)

      // Recalculate any nodes that might have changed.
      if (pending.isNotEmpty()) {
        val skipped = mutableListOf<() -> Boolean>()
        while (pending.isNotEmpty()) {
          val pendingCall = pending.removeAt(pending.lastIndex)
          if (!pendingCall()) skipped.add(pendingCall)
        }
        skipped.forEach { pending.add(it) }
      }
    } finally {
      inProgress.remove(node)
    }

    return true
  }

  // Produce a cached lazy scheme for a node. The scheme starts off being the declared scheme
  // (which, if no declarations are present, has open appliers by default) that will be further
  // refined during inference (i.e. the lazy part of LazyScheme).
  //
  // 노드에 대해 캐시된 지연 스키마를 생성합니다. 스키마는 선언된 스키마(선언이 없는 경우 기본적으로
  // Open Applier가 있음)로 시작하여 추론하는 동안 더 구체화됩니다(즉, LazyScheme의 지연된 부분).
  private fun Node.toLazyScheme(bindings: Bindings = Bindings()): LazyScheme =
    lazySchemeStorage.getOrPut(this) {
      fun declaredSchemeOf(node: Node): LazyScheme {
        val type = nodeAdapter.typeOf(node) ?: return LazyScheme.open()
        return LazyScheme(scheme = typeAdapter.declaredSchemaOf(type), bindings = bindings).also { lazyScheme ->
          if (typeAdapter.currentInferredSchemeOf(type) != null) {
            lazyScheme.onChange {
              val current = typeAdapter.currentInferredSchemeOf(type)
              val newScheme = lazyScheme.toScheme()
              if (newScheme != current) {
                typeAdapter.updatedInferredScheme(type, newScheme)
              }
            }
          }
        }
      }

      val referencedContainer = nodeAdapter.referencedContainerOf(this)
      if (referencedContainer != null) {
        lazySchemeStorage.getOrPut(referencedContainer) {
          declaredSchemeOf(referencedContainer)
        }
      } else {
        declaredSchemeOf(this)
      }
    }

  // Produce a CallBinding from a scheme in the context of the current bindings. A CallBinding
  // differs from a LazyScheme in that it can borrow bindings from a LazyScheme where a lazy
  // scheme only has bindings that it owns.
  //
  // 현재 바인딩의 컨텍스트에서 스킴을 가져와서 CallBinding을 생성합니다. CallBinding은 LazyScheme에서
  // Binding을 가져올 수 있는 반면, LazyScheme은 소유하는 Binding만 사용할 수 있다는 점에서 둘은 다릅니다.
  private fun Scheme.toCallBindings(
    bindings: Bindings,
    context: MutableList<Binding> = mutableListOf(),
  ): CallBindings =
    CallBindings(
      target = target.toBinding(bindings, context),
      parameters = parameters.map { it.toCallBindings(bindings, context) },
      result = result?.toCallBindings(bindings, context),
      anyParameters = anyParameters,
    )
}

private inline fun <Node> LazySchemeStorage<Node>.getOrPut(
  node: Node,
  value: () -> LazyScheme,
): LazyScheme =
  getLazyScheme(node) ?: run {
    val result = value()
    storeLazyScheme(node, result)
    result
  }
