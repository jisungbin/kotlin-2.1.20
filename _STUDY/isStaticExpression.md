## isStaticExpression

static expression으로 판단되는 조건:

- IrConst 이거나, (const literal)
- enum entry 참조이거나,
- companion object 참조이거나, (정확히 .Companion 참조)
- 안정한 타입의 object 참조이거나,
  - 타입 자체가 stable해야 함. *`@get:Stable`이나 `@property:Stable`은 무시됨.* 
- 생성자 호출일 때 `isStaticConstructor()`가 참이거나,
- IrCall일 때 `isStaticCall()`이 참이거나,
- IrGetValue일 때 (아래 조건 모두 참이어야 함)
  - IrVariable을 가져오고, *(IrValueParameter는 무시함)*
  - val이고,
  - static expression인 initializer가 있음

---

### isStaticConstructor

static constructor로 판단되는 조건:

- 인라인 클래스의 경우, 박싱하는 타입이 stable하고 (하나뿐인) 매개변수의 인자가 static expression 이어야 함
- 아니라면, 클래스 자체에 @Immutable이 있고(***@Stable은 무시함***) 모든 인자가 static expression 이어야 함

<br/>

### isStaticCall

static call로 판단되는 조건:

- 프로퍼티를 가져오는 호출(IrCall & IrStatementOrigin.GET_PROPERTY)일 때 *(로컬에 정의된 변수는 IrGetValue임)*
  - top-level const를 가져오는 경우나,
  - 아래 조건이 모두 참이거나, *(val인 경우)*
    - val이고, 
    - getter/setter를 직접 정의하지 않았고, 
    - 프로퍼티 타입이 stable하고, 
    - dispatch receiver와 extension receiver가 모두 없거나 static expression임
  - 아래 조건이 모두 참임 *(var인 경우)*
    - 프로퍼티나 게터에 @Stable이 있고, 
    - 프로퍼티 타입이 stable하고, 
    - dispatch receiver와 extension receiver가 모두 없거나 static expression임
- 수학/논리 연산자일 때 (아래 조건 모두 참이어야 함)
  - kotlin stdlib이거나 @Stable이 있고,
  - 연산 반환 타입이 stable하고,
  - 모든 인자가 static expression 이어야 함
- remember 호출일 때, key가 없고 반환 타입이 stable함
- 컴포저블 람다 표현식임 (composableLambda 호출, rememberComposableLambda 호출)
- KnownStableConstructs에 포함된 함수 호출이고, 모든 인자가 static expression임
- **호출하는 함수에 @Stable이 있고, 반환 타입이 stable하고, 모든(dispatch, extension, value) 인자가 static expression임**
  - *IrValueParameter만 검사하고, IrTypeParameter는 무시함*
