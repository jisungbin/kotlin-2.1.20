Copyright @ JetBrains

https://github.com/jisungbin/kotlin-2.1.20/blob/main/plugins/compose/compiler-hosted/src/main/java/androidx/compose/compiler/plugins/kotlin/ComposePlugin.kt#L559

--- 

### ComposableFunctionParamTransformer 핵심 로직

- **`copyFunctionWithComposerParam`**: `$compoer`, `$changed`, `$default` 파라미터 추가하는 로직
- **`copyCallWithComposerParamIfNeeded`**: `$compoer`, `$changed`, `$default` 인자 추가하는 로직
  - `$changed`는 `0b1`로 초기화되고, 진짜 값 계산은 ComposableFunctionBodyTransformer에서 진행됨
  - `$default`는 실제 값이 계산되어 초기화됨

### ComposableFunctionBodyTransformer 핵심 로직

- **`buildPreambleStatementsAndReturnIsSkippable`**: `$dirty`, `$changed`, `$default` 파라미터 다루는 코드 만드는 로직
- **`buildChangedArgumentForCall`**: 부모의 dirty를 사용하여 자식의 `$changed`를 구하는 로직
  - `visitNormalComposableCall`에서 `$changed` 인자 주입됨

---

### FunctionTypeKind

- `FunctionTypeKind`는 `annotationOnInvokeClassId`가 붙은 함수형 타입에 자동으로 매핑되고,
  `FirFunctionTypeKindService`의 `extract~~SpecialKindForFunction`로 함수형 타입에 맞는 `FunctionTypeKind`를 추출한다.
    - `FirSimpleFunction.getFunctionTypeForAbstractMethod(session: FirSession)`
    - `FirAnonymousFunction.constructFunctionTypeRef(session: FirSession, kind: FunctionTypeKind? = null)`
- `FunctionTypeKind`의 인터페이스는 `FirSyntheticFunctionInterfaceProviderBase.createSyntheticFunctionInterface(classId: ClassId, kind: FunctionTypeKind)`
  함수로 생성된다.
