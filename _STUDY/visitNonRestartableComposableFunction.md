## visitNonRestartableComposableFunction 공부

> 함수가 restartable하지 않거나, 반환 타입이 Unit이 아닐 때 실행되는 콜백

- 상황에 따라 replace group으로 감싸짐
- 리컴포지션 skip 로직이 없으므로 `composer.changed()`가 호출되지 않음
- restart 할 수 없으므로 항상 `$changed`임 (`$dirty` 없음)

`???` restart 할 수 없을 땐 리컴포지션을 어떻게 하지? 함수를 다시 실행할 block 기록을 안 하잖아...?
 추측: restart 할 수 있는 부모 컴포저블을 쭈욱 찾아 올라가기?

### replace group으로 감싸지는 조건

- \[@ReadOnlyComposable이 아니고, ComposableDelegatedAccessor가 아니고, @ExplicitGroupsComposable이 아니고, OptimizeNonSkippingGroups가 비활성화됨] 이거나
- \[@ReadOnlyComposable이 아니고, ComposableDelegatedAccessor가 아니고, @ExplicitGroupsComposable이 아니고, early return이 있음] 이거나
- 람다 함수이거나
- 가상(virtual) 메서드일 때 (isOverridableOrOverrides == true 인 함수)

---

### 1. `$default`에 따라 기본 인자값 넣는 작업

`$default`가 있고, 기본 인자값이 있다면... 

**1. skippable하고, 기본 인자가 static하지 않고, `$dirty` 변수가 있는 경우**

항상 skip이 불가능하므로 이 분기는 건너뜀

**else. skippable하지 않거나, 기본 인자가 static하거나, `$changed`만 사용하는 경우**

만약 현재 매개변수에 인자가 제공되지 않았다면 
  - 기본 인자값을 제공함

<br/>

### 2. 리컴포지션 스킵을 지원하는 매개변수인지 조회하는 작업

강한 건너뛰기가 비활성화되어 있을 떄만 조건에 따라 리컴포지션 스킵이 불가능함.
하지만 현재는 강한 건너뛰기가 기본으로 활성화되어 있음.

<br/>

### 3. `$changed`와 `$default`에 따라 `$dirty`를 업데이트하는 작업

**1. 리컴포지션 스킵이 이미 불가능하거나, 현재 매개변수가 사용되지 않는다면**

> 항상 리컴포지션 스킵이 불가능하므로 이 분기가 항상 실행됨

아무것도 하지 않음

**2. `$changed`만 사용한다면**

> 첫  번째 분기에 걸려서 이 분기는 항상 실행되지 않음

**3. 강한 건너뛰기가 비활성되어 있고, 불안정한 타입의 매개변수이고, `$default`가 있고, 기본 인자가 제공되었다면**

> 첫  번째 분기에 걸려서 이 분기는 항상 실행되지 않음

**4. 강한 건너뛰기가 활성화되어 있거나, 매개변수의 타입이 불안정하지 않다면**

> 첫 번째 분기에 걸려서 이 분기는 항상 실행되지 않음

<br/>

### 4. 모든 trackedParameter의 기본 인자 제거 

<br/>
