# BACKEND 작업 규칙

- DTO `record`의 선언부에서는 변수 하나를 한 줄에 작성합니다.
- Lombok으로 코드를 줄일 수 있으면 사용합니다.

## 로컬 실행

Java 25와 저장소 루트의 `data/divisions`에 대회 JSON 7개를 준비한 뒤 실행합니다.

```bash
cd backend
./gradlew bootRun
```

`bootRun`은 애플리케이션의 실행 디렉터리를 저장소 루트로 지정하므로 기본 경로인 `data/divisions`에서 대회 목록을 읽습니다. 테스트의 실행 디렉터리는 `backend`이며 테스트 전용 JSON을 사용합니다.

IDE에서 메인 클래스를 직접 실행할 때도 작업 디렉터리를 저장소 루트로 지정합니다. 다른 위치의 JSON을 사용하거나 Docker에서 실행할 때는 `APP_DIVISIONS_DIRECTORY` 환경 변수에 컨테이너 또는 실행 환경 내의 절대 경로를 지정합니다. 예를 들어 Docker에서는 `/app/data/divisions`를 지정하고 해당 위치에 JSON 디렉터리를 마운트합니다.

## 테스트 코드
- 테스트는 JUnit 5를 사용합니다.
- 모든 테스트에는 `@DisplayName`을 작성해 의도를 한국어로 설명합니다.
- 외부 의존성을 격리해야 하는 단위 테스트에는 Mockito를 사용합니다.
- Mockito 스터빙과 협력 객체 호출 검증에는 `BDDMockito.given(...)`, `then(...)`을 사용합니다.
- 테스트 본문은 Given / When / Then 순서로 작성합니다.
- 반복되는 Given 또는 When 조건은 `@Nested` 클래스로 그룹화하고, Then은 `@Test` 메서드로 작성합니다.
- Mockito가 필요 없는 순수 단위 테스트에는 Mockito를 사용하지 않습니다.
- 실제 파일 시스템이나 HTTP 서버를 사용하는 테스트는 단위 테스트와 분리해 통합 테스트로 작성합니다.
