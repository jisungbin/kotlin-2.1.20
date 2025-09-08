## buildChangedArgumentForCall

- 0b0으로 시작

### 1. 인자 표현식의 안정성 정보를 $changed의 각 슬롯에 넣는 작업

**1. 인자 표현식이 안정한 경우**

`$changed`의 현재 슬롯에 stable을 넣음

**2. 인자 표현식이 안정하지 않은 경우**

`$changed`의 현재 슬롯에 인자 표현식의 안정성 표현식을 넣음

<br/>

### 2. 인자의 메타 정보를 $changed의 각 슬롯에 넣는 작업

**1. 가변 인자의 경우**

`$changed`의 현재 슬롯에 uncertain을 넣음

**2. 기본 인자가 없는 경우**

`$changed`의 현재 슬롯에 uncertain을 넣음

**3. 인자 표현식이 static한 경우**

`$changed`의 현재 슬롯에 static을 넣음

**4. 인자 표현식이 상위 함수의 매개변수로 레퍼런스되지 않은 경우**

`$changed`의 현재 슬롯에 uncertain을 넣음

**else. 인자가 가변하지 않고, 인자에 기본값이 있고, 인자 표현식이 static하지 않고, 상위 함수의 매개변수를 레퍼런스한다면**

상위 함수의 `$dirty`의 현재 슬롯 값을 `$changed`의 현재 슬롯에 넣음