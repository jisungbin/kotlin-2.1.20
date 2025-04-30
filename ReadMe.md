Copyright @ JetBrains

https://github.com/jisungbin/kotlin-2.1.20/blob/main/plugins/compose/compiler-hosted/src/main/java/androidx/compose/compiler/plugins/kotlin/ComposePlugin.kt#L559

---

- `FunctionTypeKind`는 `annotationOnInvokeClassId`가 붙는 함수형 타입에 자동으로 매핑되고, 
  `extract~~SpecialKindForFunction`로 함수명 타입에 맞는 FunctionTypeKind를 추출한다.
    - `FirSimpleFunction.getFunctionTypeForAbstractMethod(session: FirSession)`
    - `FirAnonymousFunction.constructFunctionTypeRef(session: FirSession, kind: FunctionTypeKind? = null)`
