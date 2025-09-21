## StabilityInferencer

안정성 추론기!

### 정해진 케이스

- `null` -> `IrConst(null)`로 표현되기에 **항상 Stable**

### 공통 매개변수

- `substitutions`: TypeParameter와 TypeArgument로 구성된 맵
- `currentlyAnalyzing` 현재 안정성 분석 중인 심볼들

---

### stabilityOfType

> nullable은 항상 not-null로 다뤄짐 (nullable 여부가 안정성에 영향을 주지 않음)

**1. Unit, 원시타입, String, Function/KFunction**

항상 stable로 추론함  
ㅤ

**2. TypeParameter**

- `substitutions`에 TypeArgument가 제공됐다면, 해당 TypeArgument를 `stabilityOfProjection`으로 추론함
- 제공되지 않았다면 parameter 안정성으로 추론함

ㅤ

**3. value class**

- 클래스에 @StableMarker가 있다면 stable로 추론함
- 아니라면 value class가 감싸는 타입을 `stabilityOfType`으로 추론함

ㅤ

**4. 기타**

이외의 경우는 모두 `stabilityOfClassifier`로 추론함 

<br/>

### stabilityOfProjection

- 만약 StarProjection이라면 unstable로 추론함
- 아니라면 projection된 타입을 `stabilityOfType`으로 추론함

<br/>

### stabilityOfClassifier

- classifier가 IrClass라면 해당 클래스를 `stabilityOfClass`로 추론함
- classifier가 IrScript라면 stable로 추론함

<br/>

### stabilityOfClass

> equals 및 hashCode 구현은 신경쓰지 않음
  
ㅤ

**1. `currentlyAnalyzing`에 내가 포함되어 있는 경우**

> `class A(val a: A)`에서 `A`의 경우

항상 unstable로 추론함  
ㅤ

**2. 나 자신과 나의 superTypes에 @StableMarker가 있는 경우**

> superType의 superType도 @StableMarker 여부를 재귀적으로 검사함

항상 stable로 추론함  
ㅤ

**3. enum class, enum entry, 원시타입, com.google.protobuf.GeneratedMessage\[Lite] 클래스인 경우**

항상 stable로 추론함  
ㅤ

**4. \[KnownStableConstructs에 있거나, 외부 모듈에 정의되었거나], ExternalStableType인 경우**

> `mask`와 `stability` 두 가지 변수를 사용함

**4-1. KnownStableConstructs에 있거나, ExternalStableType인 경우**

`mask`를 얻어오고(TypeParameter 개수만큼 0b1 비트로 구성됨), `stability`는 stable로 기록함  
ㅤ

**4-2. 외부 모듈에 정의된 경우**

- `@StabilityInferred`가 없다면 항상 unstable로 추론하고 반환함 *(`stabilityOfClass` 종료)* 
  - 컴포즈 컴파일러가 없는 외부 모듈에 해당
- `@StabilityInferred`에서 `mask`를 얻어옴
- `@StabilityInferred`의 MSB를 보고 `stability`를 기록함
  - 만약 MSB가 1이라면: stable
  - 만약 MSB가 0이라면: runtime

ㅤ

**4-3. `mask`와 `stability`를 모두 계산했다면**

- 만약 `mask`가 0이거나, 클래스의 TypeParameter가 없다면 `stability`를 그대로 반환
- 아니라면 `stability`와 클래스의 TypeParameter들의 안정성을 조합하여 `Stability.Combined`로 추론하고 반환
  - TypeParameter 인덱스 번째의 mask가 0b1라면, 해당 TypeParameter의 TypeArgument를 `stabilityOfProjection`로 안정성 추론
    - 만약 mask가 0b0이라면, 항상 stable한 TypeParameter이므로 `Stability.Combined`에 제외
  - 해당 TypeParameter의 TypeArgument가 `substitutions`에 없다면 parameter 안정성으로 추론

ㅤ

**5. 외부 자바 모듈에 정의된 경우**

항상 unstable로 추론함  
ㅤ

**6. interface인 경우**

항상 unknown(== uncertain)으로 추론함  
ㅤ

**7. 외부 모듈에 정의되지 않은 경우**

> 최종으로 무조건 실행되는 분기

- 클래스 본문에 정의된 모든 프로퍼티를 순회하며, 프로퍼티 타입의 안정성을 `stabilityOfType`으로 추론함
  - 단, delegated property가 아닌 var은 항상 unstable로 추론하고 반환함 *(`stabilityOfClass` 종료)* 
- 클래스의 superClass의 안정성을 `stabilityOfClass`으로 추론함

위 두 가지 과정으로 추론된 모든 안정성을 `Stability.Stable`에 combined로 추가하여 반환 *(`stabilityOfClass` 종료)* 

<br/>

### stabilityOfCall

> owner에 붙은 @Stable은 'fun IrExpression.isStaticExpression(): Boolean'로 검사됨

- **만약 owner가 KnownStableConstructs에 없다면:** owner의 반환 타입을 `stabilityOfType`으로 추론함
- **만약 owner의 stableFunctions가 0b0이라면:** 항상 stable로 추론함
- **만약 owner의 stableFunctions가 0b0이 아니라면:** `Stability.Combined`로 추론함
  - TypeArgument 인덱스 번째의 mask가 0b1라면, 해당 TypeArgument를 `stabilityOfType`로 안정성 추론
    - 만약 mask가 0b0이라면, 항상 stable한 TypeParameter이므로 `Stability.Combined`에 제외

<br/>

### stabilityOfExpression

먼저 expression의 타입을 `stabilityOfType`로 추론하고, 결과가 stable이라면 stable로 바로 반환함.  
이 추론 결과는 `baseStability`로 저장함.

**1. 상수라면**

항상 stable로 추론함  
ㅤ

**2. 함수 호출이라면**

`stabilityOfCall`로 추론함  
ㅤ

**3. 변수/매개변수 참조라면**

- **만약 변수 참조이고, val 이라면:**
  - 초기화 식이 있다면, 해당 식에 `stabilityOfExpression`하여 추론함
  - 초기화 식이 없다면, `baseStability`를 반환함
- **그 외의 경우 (var이거나 매개변수 참조):** `baseStability`를 반환함

ㅤ

**4. 그 외의 경우**

`baseStability`를 반환함
