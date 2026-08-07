# 작업 가이드

프로그램 전체 구조를 확인하고 싶으시면 [ARCHITECTURE.md](/ARCHITECTURE.md)를 확인해주세요.

## 공통

## 프론트엔드

## 백엔드

- DTO `record`의 선언부에서는 변수 하나를 한 줄에 작성합니다.
- Lombok으로 코드를 줄일 수 있으면 사용합니다.

- ### 테스트 코드
  - 테스트는 JUnit 5를 사용합니다.
  - 모든 테스트에는 `@DisplayName`을 작성해 의도를 한국어로 설명합니다.
  - 외부 의존성을 격리해야 하는 단위 테스트에는 Mockito를 사용합니다.
  - Mockito 스터빙과 협력 객체 호출 검증에는 `BDDMockito.given(...)`, `then(...)`을 사용합니다.
  - 테스트 본문은 Given / When / Then 순서로 작성합니다.
  - 반복되는 Given 또는 When 조건은 `@Nested` 클래스로 그룹화하고, Then은 `@Test` 메서드로 작성합니다.
  - Mockito가 필요 없는 순수 단위 테스트에는 Mockito를 사용하지 않습니다.
  - 실제 파일 시스템이나 HTTP 서버를 사용하는 테스트는 단위 테스트와 분리해 통합 테스트로 작성합니다.
