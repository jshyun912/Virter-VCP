# Codeforces Handle Error Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 존재하지 않는 Codeforces handle을 일반적인 Codeforces 통신·응답 실패와 구분해 HTTP 400으로 반환한다.

**Architecture:** `RestClient.ResponseSpec.onStatus`로 Codeforces의 HTTP 400 응답 본문을 그대로 역직렬화한다. `CodeforcesService`가 `CodeforcesResponse.comment`를 판별해 사용자 부재와 그 밖의 upstream 실패를 기존 `ApiException` 체계로 변환한다.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Framework 7 `RestClient`, JUnit 5, Mockito BDD

## Global Constraints

- 존재하지 않는 handle은 HTTP 400과 `CODEFORCES_USER_NOT_FOUND`를 반환한다.
- 통신 장애, Codeforces 5xx, 빈 응답, 그 밖의 실패 응답은 HTTP 502와 `CODEFORCES_API_FAILED`를 반환한다.
- 상세 원인은 API 응답 본문에 노출하지 않고 `ApiException.detail`을 통해 로그에만 기록한다.
- `comment`에 대소문자와 관계없이 `not found`가 포함되면 존재하지 않는 handle로 판단한다.
- 재시도와 호출 제한 전용 처리는 추가하지 않는다.
- 테스트는 `CONTRIBUTE.md`의 JUnit 5, Mockito BDD, `@DisplayName`, Given/When/Then 규칙을 따른다.
- Java 코드에서 `var`를 사용하지 않는다.

---

### Task 1: getSubmissions 오류 구분

**Files:**
- Modify: `backend/src/main/java/jshyun912/backend/exception/ErrorMessage.java`
- Modify: `backend/src/main/java/jshyun912/backend/service/codeforces/CodeforcesService.java`
- Test: `backend/src/test/java/jshyun912/backend/service/codeforces/CodeforcesServiceTest.java`

**Interfaces:**
- Consumes: `CodeforcesResponse<List<Submission>>`의 `status`, `comment`, `result`
- Produces: `ErrorMessage.CODEFORCES_USER_NOT_FOUND`
- Produces: `CodeforcesService.getSubmissions(String handle)`의 구분된 `ApiException`

- [ ] **Step 1: 존재하지 않는 handle 테스트를 작성한다**

기존 실패 상태 테스트를 실제 Codeforces 실패 형식처럼 `result == null`로 바꾸고 다음 계약을 검증한다.

```java
@Test
@DisplayName("존재하지 않는 handle이면 사용자 조회 예외를 던진다")
void thenThrowsUserNotFoundWhenHandleDoesNotExist() {
    // given
    CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>(
            "FAILED",
            "handle: User with handle tourist not found",
            null
    );
    willReturn(response).given(responseSpec)
            .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

    // when
    ApiException exception = assertThrows(ApiException.class,
            () -> codeforcesService.getSubmissions(HANDLE));

    // then
    assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
    assertEquals(ErrorMessage.CODEFORCES_USER_NOT_FOUND, exception.getErrorMessage());
    assertEquals("Codeforces 사용자 " + HANDLE + "을 찾을 수 없습니다.", exception.getDetail());
}
```

- [ ] **Step 2: 그 밖의 Codeforces 실패 응답 테스트를 작성한다**

```java
@Test
@DisplayName("그 밖의 실패 응답이면 Codeforces API 예외를 던진다")
void thenThrowsApiExceptionWhenCodeforcesReturnsOtherFailure() {
    // given
    CodeforcesResponse<List<Submission>> response = new CodeforcesResponse<>(
            "FAILED",
            "Call limit exceeded",
            null
    );
    willReturn(response).given(responseSpec)
            .body(ArgumentMatchers.<ParameterizedTypeReference<CodeforcesResponse<List<Submission>>>>any());

    // when
    ApiException exception = assertThrows(ApiException.class,
            () -> codeforcesService.getSubmissions(HANDLE));

    // then
    assertEquals(HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
    assertEquals(ErrorMessage.CODEFORCES_API_FAILED, exception.getErrorMessage());
    assertEquals("Codeforces API 요청에 실패했습니다: Call limit exceeded", exception.getDetail());
}
```

HTTP 400 본문을 읽는 체인을 위해 공통 요청 setup에 다음 stubbing도 추가한다.

```java
willReturn(responseSpec).given(responseSpec)
        .onStatus(ArgumentMatchers.any(), ArgumentMatchers.any());
```

기존 빈 응답 테스트의 detail 기대값은 `Codeforces API 응답이 없습니다.`로, `OK`이지만 `result`가 null인 테스트의 기대값은 `Codeforces API 응답이 올바르지 않습니다.`로 변경한다.

- [ ] **Step 3: 대상 테스트를 실행해 실패를 확인한다**

Run:

```bash
cd backend
./gradlew test --tests jshyun912.backend.service.codeforces.CodeforcesServiceTest
```

Expected: `CODEFORCES_USER_NOT_FOUND`가 없거나 실패 응답이 기존 `CODEFORCES_API_FAILED`로 합쳐져 테스트가 실패한다.

- [ ] **Step 4: 오류 enum과 HTTP 400 본문 처리를 구현한다**

`ErrorMessage`에 다음 값을 추가한다.

```java
CODEFORCES_USER_NOT_FOUND,
```

`getSubmissions`의 요청 체인을 다음처럼 변경한다.

```java
CodeforcesResponse<List<Submission>> response = restClientConfig.restClient().get()
        .uri("https://codeforces.com/api/user.status?handle={handle}", handle)
        .retrieve()
        .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value(), (request, errorResponse) -> {
        })
        .body(new ParameterizedTypeReference<>() {
        });
return requireSuccessfulResponse(response, handle);
```

성공과 실패 응답 판별을 다음 계약으로 변경한다.

```java
private <T> CodeforcesResponse<T> requireSuccessfulResponse(CodeforcesResponse<T> response, String handle) {
    if (response == null) {
        throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED,
                "Codeforces API 응답이 없습니다.");
    }
    if ("OK".equals(response.status()) && response.result() != null) {
        return response;
    }
    if ("FAILED".equals(response.status()) && response.comment() != null
            && response.comment().toLowerCase(Locale.ROOT).contains("not found")) {
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorMessage.CODEFORCES_USER_NOT_FOUND,
                "Codeforces 사용자 " + handle + "을 찾을 수 없습니다.");
    }
    if ("FAILED".equals(response.status())) {
        String detail = response.comment() == null
                ? "Codeforces API 요청에 실패했습니다."
                : "Codeforces API 요청에 실패했습니다: " + response.comment();
        throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED, detail);
    }
    throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorMessage.CODEFORCES_API_FAILED,
            "Codeforces API 응답이 올바르지 않습니다.");
}
```

`java.util.Locale`를 import한다. 주석 처리된 2차 구현은 이번 변경 범위에서 수정하지 않는다.

- [ ] **Step 5: 대상 테스트를 실행해 통과를 확인한다**

Run:

```bash
cd backend
./gradlew test --tests jshyun912.backend.service.codeforces.CodeforcesServiceTest
```

Expected: 모든 `CodeforcesServiceTest`가 통과한다.

- [ ] **Step 6: 전체 회귀 테스트를 실행한다**

Run:

```bash
cd backend
./gradlew test
```

Expected: 전체 테스트가 통과하고 division 테스트 파일은 `backend/build/test-data/divisions` 아래에만 생성된다.

- [ ] **Step 7: 변경을 커밋한다**

```bash
git add backend/src/main/java/jshyun912/backend/exception/ErrorMessage.java \
        backend/src/main/java/jshyun912/backend/service/codeforces/CodeforcesService.java \
        backend/src/test/java/jshyun912/backend/service/codeforces/CodeforcesServiceTest.java
git commit -m "fix: distinguish missing Codeforces handles"
```
