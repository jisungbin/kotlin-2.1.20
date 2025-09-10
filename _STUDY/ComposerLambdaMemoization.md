## ComposerLambdaMemoization

람다를 `remember`, `cache`, `ComposableSingletons` 중 하나로 래핑하는 작업.  
컴포저블이 아닌 람다여도 동작한다.

---

### visitBlock

> IrBlock이 함수 레퍼런스(`::function`)로 채워진 경우에만 동작함

- inline 함수의 본문이라면 메모이제이션하지 않음
- 아니라면 레퍼런스 표현식을 `rememberFunctionReference`로 메모이제이션함

<br/>

### visitFunctionReference

- inline 함수의 인자로 제공된 함수 레퍼런스는 메모이제이션하지 않음
- ADAPTER_FOR_CALLABLE_REFERENCE 오리진의 함수 레퍼런스는 메모이제이션하지 않음
- 모두 아니라면 레퍼런스 표현식을 `rememberFunctionReference`로 메모이제이션함

<br/>

### visitFunctionExpression

- 람다가 컴포저블이거나, 컴포저블 인라인 함수의 인자로 제공된 경우, `visitComposableFunctionExpression`로 메모이제이션함  
  (람다 매개변수에 `@DisallowComposableCalls`가 없어야 함)
- 아니라면 `visitNonComposableFunctionExpression`로 메모이제이션함

<br/>

### visitTypeOperator

> SAM 연산과 컴포저블 스코프 안에서만 동작함

SAM 연산 전체를 `rememberExpression`로 메모이제이션함

```kotlin
Closeable {}
```

```kotlin
remember {
  Closeable {}
}
```

---

### visitComposableFunctionExpression

**1. 메모이제이션 가능한 람다인지 검사**

- inline 함수의 인자로 제공되는 noinline이 아닌 람다는 메모이제이션하지 않음
- 반환 타입이 Unit이 아닌 람다는 메모이제이션하지 않음

ㅤ

**2. 메모이제이션 진행**

- **람다가 캡처하는 값이 없다면..**
  - **람다를 담는 부모들(부모 재귀)이 public이라면:** 
    - 람다를 `wrapFunctionExpressionWithComposableLambda(useRememberingFactory = isInComposableScope)`로 래핑하여 메모이제이션함
  - **람다를 담는 부모들(부모 재귀)이 private 이라면:**
    - `wrapFunctionExpressionWithComposableLambda(useRememberingFactory = false)`로 람다를 래핑함
    - 래핑된 람다 인스턴스를 ComposableSingletons 객체에 저장함
    - ComposableSingletons에 저장된 람다를 가져오는 getter의 IrCall로 메모이제이션함
- **람다가 캡처하는 값이 있다면..**
  - 람다를 `wrapFunctionExpressionWithComposableLambda(useRememberingFactory = isInComposableScope)`로 래핑하여 메모이제이션함

<br/>

### visitNonComposableFunctionExpression

- 컴포저블 스코프가 아니거나, inline 함수의 인자로 제공되는 noinline이 아닌 람다는 메모이제이션하지 않음
- 아니라면 람다를 `rememberExpression`로 래핑하여 메모이제이션함

<br/>

### wrapFunctionExpressionWithComposableLambda

`useRememberingFactory` 값에 따라 `rememberComposableLambda` 혹은 `composableLambdaInstance`로 래핑하여 메모이제이션함

<br/>

### rememberExpression

**1. 메모이제이션 가능한 람다인지 검사**

- `@DontMemoize`인 람다 안에 호출되는 람다는 메모이제이션하지 않음
- `@DontMemoize`인 람다는 메모이제이션하지 않음 
- var 변수를 캡처하는 람다는 메모이제이션하지 않음
- 프로퍼티를 레퍼런스하는 델리게이션을 캡처하는 람다는 메모이제이션하지 않음
- inline 함수의 인자로 제공되는 noinline이 아닌 람다를 캡처하는 람다는 메모이제이션하지 않음

ㅤ

**2. 메모이제이션 진행**

람다를 `remember`로 래핑하고, 람다가 캡처하는 값을 key로 전달하여 메모이제이션함

<br/>

### rememberFunctionReference

- context receiver를 갖는 참조는 메모이제이션하지 않음
- 컴포저블 스코프에 속하지 않은 참조는 메모이제이션하지 않음
- 모두 아니라면 `rememberExpression`로 래핑하여 메모이제이션함
  - dispatch receiver와 extension receiver를 key로 추가함
  - 로컬 함수를 레퍼런스한다면, 해당 함수가 캡처하는 값들을 key로 추가함
