# Codeforces handle 오류 구분 설계

## 목적

`CodeforcesService.getSubmissions`에서 존재하지 않는 handle과 Codeforces 통신·응답 실패를 구분한다.

## 응답 계약

- 존재하지 않는 handle은 HTTP 400과 `CODEFORCES_USER_NOT_FOUND`를 반환한다.
- 통신 장애, Codeforces 5xx, 빈 응답, 그 밖의 실패 응답은 HTTP 502와 `CODEFORCES_API_FAILED`를 반환한다.
- 상세 원인은 기존 정책대로 API 응답 본문에 노출하지 않고 `ApiException.detail`을 통해 로그에만 기록한다.

## 처리 흐름

1. `user.status` 요청에서 HTTP 400의 기본 예외 변환만 해제해 Codeforces JSON 본문을 역직렬화한다.
2. `status`가 `OK`이고 `result`가 존재하면 기존 성공 응답을 반환한다.
3. `status`가 `FAILED`이고 `comment`에 대소문자와 관계없이 `not found`가 포함되면 존재하지 않는 handle로 판단한다.
4. 그 밖의 실패 응답은 일반 Codeforces API 실패로 처리한다.
5. HTTP 5xx와 통신 예외는 기존처럼 일반 Codeforces API 실패로 처리한다.

## 오류 상세 메시지

- 존재하지 않는 handle: `Codeforces 사용자 {handle}을 찾을 수 없습니다.`
- Codeforces 실패 응답: Codeforces가 반환한 `comment`를 로그용 상세 메시지에 포함한다.
- 통신 실패: 기존의 handle별 `user.status` 요청 실패 상세 메시지를 유지한다.

## 테스트

- 정상 응답은 그대로 반환한다.
- `not found` 실패 응답은 HTTP 400과 `CODEFORCES_USER_NOT_FOUND`로 변환한다.
- 그 밖의 `FAILED` 응답은 HTTP 502와 `CODEFORCES_API_FAILED`로 변환한다.
- 빈 응답과 `result`가 없는 성공 응답은 HTTP 502로 처리한다.
- 통신 예외는 HTTP 502로 변환하고 원인을 보존한다.

## 범위 밖

- handle 형식 오류와 호출 제한 등 Codeforces의 다른 `comment`를 별도 enum으로 세분화하지 않는다.
- 재시도와 호출 제한 대응은 이번 변경에 포함하지 않는다.
