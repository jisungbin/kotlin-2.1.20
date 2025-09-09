## ClassStabilityTransformer

### transformed이 무시되는 경우

- public이나 internal이 아님
- enum class, enum entry
- interface
- annotation class
- anonymous object
- expect class
- inner class
- file class
- companion object
- inline class

---

### @StabilityInferred 인자

> **이미 @StableMarker가 있다면 어노테이션을 추가하지 않음**

- 클래스가 소유한 type argument 개수 만큼 0b1 비트가 추가됨
- 클래스의 property, field, superclass 타입 기반으로 추론한 안정성이 stable이라면, MSB를 0b1로 설정함

외부 모듈에 정의된 클래스의 안정성을 추론하는 데 이 값이 사용됨 (StabilityInferencer 참고)

<br/>

### $stable 필드

> **이미 @StableMarker가 있다면 stable임**

- 현재 클래스 외부에 정의된 type argument가 쓰인다면 unstable임
- 위의 경우가 아니라면 클래스의 property, field, superclass 타입 기반으로 추론한 안정성의 표현식을 그대로 사용함

Runtime Stability의 실제 값을 조회하는 데 이 값이 사용됨
