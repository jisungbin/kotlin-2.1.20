## visitRestartableComposableFunction 공부

> 만약 함수가 restartable하고, 반환 타입이 Unit이라면 실행되는 콜백

### restartable하지 않은 조건  

- inline 함수
- 반환 타입이 Unit이 아닌 경우 (향후 완화될 수 있음)
- 람다인 경우 (`ComposableLambda` 클래스를 대신 사용함)
- `@Composable fun interface` 구현체가 아닌 로컬 함수인 경우
- `@NonRestartableComposable` 어노테이션이 지정된 경우
- `@ExplicitGroupsComposable` 어노테이션이 지정된 경우
- `val a by remember { mutableStateOf(..) }` 처럼 컴포저블 함수를 델리게이트할 경우
- `$composer` 매개변수가 없는 경우
- 기본 인자가 있는 컴포저블의 원본 함수 (ComposableDefaultParamLowering로 만들어진 스텁 함수의 원본 함수)
- open 함수

### 스킵 가능 조건 

- `@NonSkippableComposable` 어노테이션이 없음
- 함수에 전달된 인자가 모두 안정한 값인 경우
- 안정적인 파라미터 모두가 이전 실행과 동일한 값인 경우
- `composer.skipping` 호출이 true를 반환하는 경우

---

### 1. `$default`에 따라 기본 인자값 넣는 작업

`$default`가 있고, 기본 인자값이 있다면... 

**1. skippable하고, 기본 인자가 static하지 않고, `$dirty` 변수가 있는 경우**

만약 현재 매개변수에 인자가 제공되지 않았다면 
  - 기본 인자값을 제공함
  - `$dirty`의 현재 슬롯에 uncertain을 넣음

**else. skippable하지 않거나, 기본 인자가 static하거나, `$changed`만 사용하는 경우**

만약 현재 매개변수에 인자가 제공되지 않았다면 
  - 기본 인자값을 제공함

<br/>

### 2. 리컴포지션 스킵을 지원하는 매개변수인지 조회하는 작업

강한 건너뛰기가 비활성화되어 있을 떄만 조건에 따라 리컴포지션 스킵이 불가능함.
하지만 현재는 강한 건너뛰기가 기본으로 활성화되어 있음.

<br/>

### 3. `$changed`와 `$default`에 따라 `$dirty`를 업데이트하는 작업

> 매개변수의 타입이 불안정하다면 아무것도 안함

**1. 리컴포지션 스킵이 이미 불가능하거나, 현재 매개변수가 사용되지 않는다면**

아무것도 하지 않음

**2. `$changed`만 사용한다면**

아무것도 하지 않음

**3. 강한 건너뛰기가 비활성되어 있고, 불안정한 타입의 매개변수이고, `$default`가 있고, 기본 인자가 제공되었다면**

강한 건너뛰기는 기본으로 활성화되어 있으므로 이 분기는 건너뜀

**4. 강한 건너뛰기가 활성화되어 있거나, 매개변수의 타입이 불안정하지 않다면**

> 강한 건너뛰기는 기본으로 활성화되어 있으므로 이 분기는 항상 실행됨

- `$default`가 있고, 기본 인자가 없거나 static하다면
  - 매개변수에 인자가 제공되지 않았다면 (기본 인자를 사용한다면)
    - `$dirty`의 현재 슬롯에 Static을 넣음
  - _else 본문과 동일한 분기 실행_
- else. `$default`가 없거나, 기본 인자가 있으며 static하지 않다면
  - `$changed`의 현재 슬롯이 uncertain하거나 unknown이라면
    - `$default`가 있고, 기본 인자가 있으며 static하지 않다면
      - 매개변수에 인자가 제공됐을 때, 제공된 인자값에 `changed()` 호출
    - else. `$default`가 없거나, 기본 인자가 없거나 static하다면
      - 제공된 인자값에 `changed()` 호출
    - => `changed()` 호출 결과가..
      - 변경됐다면: `$dirty` 슬롯을 "Different"로 업데이트
      - 동일하다면: `$dirty` 슬롯을 "Same"으로 업데이트

<br/>

### 4. 모든 trackedParameter의 기본 인자 제거 

<br/>

### 5. skippable 그룹 추가 작업

**1. 리컴포지션을 건너뛸 수 없거나, 모든 매개변수의 기본 인자가 없거나 static하다면**

컴포저블이 절대 리컴포지션 스킵되지 않으므로 그룹 생성을 건너뜀

**else. 리컴포지션을 건너뛸 수 있거나, 매개변수들에 기본 인자가 하나라도 있고 해당 기본 인자가 static하지 않다면**

- default group 시작
- `$changed`의 LSB가 0이거나 `$composer.defaultsInvalid`가 true라면, 매개변수를 기본 인자로 다시 초기화
- default group 닫음

<br/>

### 6. 만약 리컴포지션 스킵이 가능하다면

- trackedParameters가 모두 ParamState.Same이고,
- `$changed`의 LSB가 0이라면

리컴포지션 스킵!
