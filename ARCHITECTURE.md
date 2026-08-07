# Virter 아키텍처

## 목적

여러 Codeforces 사용자의 참여 이력을 기준으로, 모두에게 아직 참여 이력이 없는 Virtual Contest 후보를 최대 세 개 추천한다.

## 기술 구성

| 영역 | 기술 | 역할 |
| --- | --- | --- |
| Frontend | SvelteKit | 사용자 입력과 추천 결과 표시 (현재 기본 화면) |
| Backend | Spring Boot 4 / Java 25 | 검색 API와 Codeforces 연동 |
| 외부 API | Codeforces API | 사용자 제출 이력과 대회 목록 조회 |
| 런타임 파일 저장소 | `data/divisions/*.json` | 실행 중 생성되는 Div별 완료 대회 목록의 로컬 스냅샷 |

## 디렉터리 개요

```text
front/                         SvelteKit 애플리케이션
backend/                       Spring Boot 애플리케이션
  src/main/java/.../
    controller/                HTTP API 진입점
    service/                   검색 서비스
      codeforces/              Codeforces 연동
    dto/search/                검색 요청·응답 DTO
    dto/codeforces/            Codeforces API 응답 DTO
    config/                    RestClient 설정
    exception/                 API 예외와 공통 오류 응답 처리
data/divisions/                실행 중 생성되는 Div별 대회 목록 JSON (저장소에 미포함)
```

## Backend 요청 흐름

```text
GET /search?handle={handle...}&dif={mask}
        |
        v
SearchController
        |
        v
SearchService
  1. 각 handle의 user.status를 조회
  2. 참여한 contest ID를 합침
  3. dif가 선택한 Div 목록에서 참여 이력을 제외
  4. 후보를 섞어 최대 3개 반환
        |
        v
SearchResponse { contestList }
```

`CodeforcesService`는 `RestClient`로 `user.status`를 호출한다. `contest.list` 호출 코드는 2차 구현 대상으로 주석 처리되어 있다.

## 요청 검증과 오류 처리

`SearchController`는 `handle`이 하나 이상이고 공백이 아닌지, `dif`가 `1` 이상 `127` 이하의 정수인지 검증한다. 잘못된 요청은 HTTP 400과 `INVALID_SEARCH_REQUEST`로 응답한다.

`CodeforcesService`는 `user.status` 응답을 다음과 같이 변환한다.

| 상황 | HTTP 상태 | 오류 코드 |
| --- | ---: | --- |
| 사용자를 찾을 수 없음 | 400 | `CODEFORCES_USER_NOT_FOUND` |
| 통신 실패 또는 그 밖의 Codeforces 실패 | 502 | `CODEFORCES_API_FAILED` |
| 응답이 없거나 형식이 올바르지 않음 | 502 | `CODEFORCES_API_FAILED` |

공통 예외 처리기는 클라이언트에 상태와 오류 코드만 반환하고 상세 원인은 서버 로그에 기록한다.

## Div 데이터 초기화

애플리케이션 시작 시 `CodeforcesService.initContestList()`이 다음 파일을 읽어 메모리 목록을 채운다.

| 파일 | 메모리 목록 |
| --- | --- |
| `Div1.json` | `div1ContestList` |
| `Div1Plus2.json` | `div12ContestList` |
| `Div2.json` | `div2ContestList` |
| `Div2Educational.json` | `div2EduContestList` |
| `Div3.json` | `div3ContestList` |
| `Div4.json` | `div4ContestList` |
| `others.json` | `otherContestList` |

저장 경로는 `app.divisions-directory` 설정으로 변경할 수 있으며 기본값은 실행 디렉터리 기준 `data/divisions`다. 저장소는 이 디렉터리나 초기 JSON 파일을 제공하지 않는다. 애플리케이션은 디렉터리 또는 파일이 없으면 빈 Codeforces API 응답 형식으로 생성하며, 생성된 데이터는 Git에서 제외된다.

대회 목록 자동 갱신 및 JSON 반영 로직은 현재 2차 구현 대상으로 주석 처리되어 있다.

## `dif` 비트마스크 계약

`dif`는 선택된 Div를 나타내는 10진수 비트마스크다. 비트가 `1`이면 해당 목록을 추천 후보에 포함한다.

| 비트 값 | Div |
| ---: | --- |
| 1 | Div. 4 |
| 2 | Div. 3 |
| 4 | Div. 2 |
| 8 | Div. 2 Educational |
| 16 | Div. 1 + Div. 2 |
| 32 | Div. 1 |
| 64 | 기타 대회 (현재 미사용) |

비트 값 `64`는 `otherContestList`를 위한 예약 값이다. 현재 검색 추천 로직은 이 비트를 사용하지 않는다.

## MVP 제약과 다음 단계

- Frontend 검색 UI와 Backend API 연결이 아직 없다.
- Codeforces API 재시도와 호출 제한 전용 처리가 없다.
- 추천은 균등 무작위이며 난이도·최근성·대회 길이는 고려하지 않는다.
- `data/divisions`의 배포 환경용 영속 저장 방식은 정해지지 않았다.
