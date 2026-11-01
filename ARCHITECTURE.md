# Virter 아키텍처

## 목적

여러 Codeforces 사용자의 참여 이력을 기준으로, 모두에게 아직 참여 이력이 없는 Virtual Contest 후보를 최대 세 개 추천한다.

## 기술 구성

| 영역 | 기술 | 역할 |
| --- | --- | --- |
| Frontend | SvelteKit / Nginx | 사용자 입력, 추천 결과 표시, 백엔드 내부 프록시 |
| Backend | Spring Boot 4 / Java 25 | 검색 API, 헬스 API와 Codeforces 연동 |
| 외부 API | Codeforces API | 사용자 제출 이력 조회 |
| 운영 입력 파일 | `data/divisions/*.json` | 운영자가 관리하는 Div별 완료 대회 목록 |

## 디렉터리 개요

```text
front/                         SvelteKit 애플리케이션
backend/                       Spring Boot 애플리케이션
  src/main/java/.../
    controller/                HTTP API 진입점
    service/                   검색 서비스
      codeforces/              Codeforces 연동
    dto/search/                검색 요청·응답 DTO
    dto/health/                헬스 응답 DTO
    dto/codeforces/            Codeforces API 응답 DTO
    config/                    RestClient 설정
    exception/                 API 예외와 공통 오류 응답 처리
data/divisions/                운영자가 준비하는 Div별 대회 목록 JSON (저장소에 미포함)
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

`CodeforcesService`는 `RestClient`로 `user.status`를 호출한다. 대회 목록은 Codeforces에서 자동 갱신하지 않고 운영자가 제공한 JSON만 읽는다.

### 검색 API

프록시를 통한 요청 경로는 `GET /api/search`이며, 백엔드 내부 경로는 `GET /search`다. 여러 사용자를 지정할 때는 `handle` 쿼리 파라미터를 반복한다. `dif=63`은 UI에서 제공하는 여섯 대회 유형을 모두 선택한다.

```bash
curl 'http://localhost/api/search?handle=tourist&handle=Benq&dif=63'
```

성공하면 HTTP 200과 `contestList`를 반환한다. 다음은 주요 필드만 표시한 예시이며, 실제 응답의 대회 객체에는 `Contest` DTO의 다른 필드도 포함된다.

```json
{
  "contestList": [
    {
      "id": 2167,
      "name": "Codeforces Round 1062 (Div. 4)",
      "durationSeconds": 8100
    }
  ]
}
```

후보가 세 개보다 적으면 있는 만큼 반환하고, 후보가 없으면 `{"contestList":[]}`를 반환한다. `durationSeconds`의 단위는 초다. 추천은 입력한 사용자 중 누구에게도 제출 이력이 없는 대회에서 무작위로 선택하므로, 같은 조건으로 다시 검색해도 결과가 달라질 수 있다.

각 핸들의 제출 이력은 순서대로 조회한다. 검색용 캐시는 없으며 같은 닉네임을 다시 검색하면 Codeforces API를 다시 호출한다. 연결·읽기 타임아웃은 `backend/src/main/resources/application.yaml`의 다음 설정으로 조절한다. 이 값은 핸들별 HTTP 호출에 적용되며 전체 검색 요청의 제한 시간은 아니다.

```yaml
app:
  codeforces:
    connect-timeout: 5s
    read-timeout: 30s
```

## 배포 요청 흐름

```text
Internet
   |
   v
NGINX Proxy Manager (도메인 / TLS)
   |
   v
virter-frontend:80 (정적 SvelteKit / Nginx)
   |  /api/*
   v
backend:8080 (Spring Boot)
   |
   v
Codeforces API
```

NPM, 프론트엔드, 백엔드는 같은 Compose에서 실행한다. NPM과 프론트엔드는 Compose가 생성하는 proxy 네트워크를 공유한다. NPM의 80·443 포트는 외부에 공개하고, 관리 화면용 81 포트는 호스트의 127.0.0.1에만 바인딩한다. 프론트엔드는 호스트 포트를 공개하지 않고 NPM을 통해 접근한다. NPM은 `proxy-manager/localhost.conf`를 HTTP 설정에 포함하여 `http://localhost`와 `http://127.0.0.1` 요청을 프론트엔드로 전달한다. 외부 도메인과 TLS 인증서는 NPM 관리 화면에서 설정한다. 백엔드는 프론트엔드와의 app 네트워크에만 참가하고 호스트 포트를 공개하지 않는다. app 네트워크는 Codeforces API 호출을 위해 외부 통신을 허용한다. NPM의 설정과 SQLite 데이터, TLS 인증서는 별도의 Docker 볼륨에 보관한다.

### 이미지 빌드와 데이터

백엔드는 Java 25 JDK 빌드 단계에서 Gradle로 `bootJar`를 실행하고, 실행 단계에는 Java 25 JRE와 `app.jar`만 복사한다. 프론트엔드는 Node.js 24 빌드 단계에서 `npm ci`와 `npm run build`를 실행하고, 생성된 정적 파일을 Nginx 이미지에 복사한다. Docker로 실행할 때 호스트에 Java·Node.js를 설치하거나 JAR·프론트엔드 빌드를 미리 만들 필요는 없다.

대회 JSON은 이미지에 포함하지 않고 실행 중 백엔드에 읽기 전용으로 마운트한다. 파일을 바꾸면 `docker compose restart backend`로 메모리 목록을 다시 읽는다. 프론트엔드는 이 JSON을 직접 읽지 않는다.

## 헬스 API와 Actuator

애플리케이션의 요약 상태는 `GET /health`로 확인한다. Compose 배포에서 프록시를 통한 경로는 `GET /api/health`다.

```bash
curl -i http://localhost/api/health
```

`HealthController`는 `HealthService`가 반환한 결과에 따라 HTTP 상태를 선택한다. 응답 필드는 camelCase를 사용하며, 캐시를 사용하는 동안에는 마지막 점검 결과를 나타낸다.

| 점검 결과 | HTTP 상태 | 응답 본문 |
| --- | ---: | --- |
| Actuator의 통합 상태가 `UP` | 200 | `{"isHealthy":true,"backendStatus":200}` |
| `DOWN`, `OUT_OF_SERVICE`, `UNKNOWN` 등 `UP` 이외의 상태 | 503 | `{"isHealthy":false,"backendStatus":503}` |
| 최초 점검이 진행 중인 동안 들어온 다른 요청 | 503 | `{"isHealthy":false,"backendStatus":503}` |

`backendStatus`는 Actuator 상태를 위 규칙으로 변환한 값이다. `HealthService`는 주입받은 `HealthEndpoint.health()`를 직접 호출한다. 자기 서버에 HTTP 요청을 보내지 않으며 Codeforces 상태도 조회하지 않는다. Actuator의 상세 점검 내용은 요약 응답에 포함하지 않는다. 이 API가 정상이어도 Codeforces 검색 요청의 성공까지 보장하지는 않는다.

### 캐시와 동시 요청

첫 요청이 상태를 점검하고, 점검 완료 시각부터 1분 동안 결과를 재사용한다. 정확히 60초까지는 캐시를 사용하며 1분을 넘긴 뒤 들어온 요청이 갱신한다. 정상과 비정상 결과 모두 같은 기간 동안 저장한다. 시스템 시각이 마지막 점검 시각보다 뒤로 조정되면 다시 점검한다.

별도 주기 작업은 없다. 갱신은 요청을 처리하는 스레드에서 동기로 실행하며, 동시에 하나의 요청만 갱신한다. 다른 요청은 갱신을 기다리지 않고 저장된 결과를 반환한다. 따라서 갱신 중에는 만료된 이전 정상 결과로 HTTP 200을 반환할 수도 있다. 아직 점검 결과가 없으면 초기값인 비정상 상태를 반환한다. HTTP 호출을 제거했어도 Actuator의 실제 점검 항목이 느리면 갱신 요청은 기다릴 수 있다.

### 관리 서버 접근 범위

Actuator 관리 서버는 `management.server.address=127.0.0.1`, `management.server.port=8081`로 설정한다. **관리 포트는 8081로 고정하며 변경하지 않는다.** `HealthService` 생성 시 설정을 검사하여 다른 포트 값이면 애플리케이션 시작에 실패한다. 임의 포트 `0`이나 비활성화 값 `-1`도 허용하지 않는다.

Compose에서는 8081을 호스트에 공개하지 않는다. 관리 서버의 `127.0.0.1`은 백엔드 컨테이너 자신의 루프백 주소이므로 다른 컨테이너에서도 관리 서버에 직접 연결할 수 없다. 프론트엔드의 `/api/*` 프록시는 백엔드 애플리케이션 포트 8080으로 연결되며 관리 포트로 전달하지 않는다. 외부에서 프록시를 통해 확인할 수 있는 것은 요약 헬스 API이며, Actuator 관리 서버는 백엔드 컨테이너 내부에서만 접근한다.

## 요청 검증과 오류 처리

`SearchController`는 `handle`이 하나 이상이고 공백이 아닌지, `dif`가 `1` 이상 `127` 이하의 정수인지 검증한다. 잘못된 요청은 HTTP 400과 `INVALID_SEARCH_REQUEST`로 응답한다.

`CodeforcesService`는 `user.status` 응답을 다음과 같이 변환한다.

| 상황 | HTTP 상태 | 오류 코드 |
| --- | ---: | --- |
| 사용자를 찾을 수 없음 | 400 | `CODEFORCES_USER_NOT_FOUND` |
| 통신 실패 또는 그 밖의 Codeforces 실패 | 502 | `CODEFORCES_API_FAILED` |
| 응답이 없거나 형식이 올바르지 않음 | 502 | `CODEFORCES_API_FAILED` |

공통 예외 처리기는 클라이언트에 상태와 오류 코드만 반환하고 상세 원인은 서버 로그에 기록한다.

Spring MVC가 처리하는 HTTP 오류는 원래 상태와 응답 헤더를 유지한다. 존재하지 않는 경로는 404, 지원하지 않는 메서드는 405와 `Allow` 헤더를 반환하며, 이러한 4xx 응답의 오류 코드는 `HTTP_REQUEST_FAILED`다. 응답 본문은 기존 `status`, `message` 형식을 유지하고 예상하지 못한 서버 예외는 500과 `INTERNAL_SERVER_ERROR`로 처리한다.

예를 들어 검색 사용자를 찾을 수 없으면 HTTP 400과 다음 본문을 반환한다. `message`는 상세 설명이 아닌 오류 코드이며, 프론트엔드가 이를 사용자용 문구로 변환한다.

```json
{
  "status": "FAILED",
  "message": "CODEFORCES_USER_NOT_FOUND"
}
```

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

저장 경로는 `app.divisions-directory` 설정으로 변경할 수 있으며 기본값은 실행 디렉터리 기준 `data/divisions`다. 저장소는 이 디렉터리나 JSON 파일을 제공하지 않는다. 운영자가 일곱 파일을 모두 준비해야 하며, 파일 누락 또는 역직렬화 실패는 애플리케이션 시작 실패로 처리한다.

각 파일은 `status`와 `result`를 가진 Codeforces 응답 형태로 준비한다. 다음은 `Div4.json` 예시다. 필드 이름은 `durationSeconds`, `startTimeSeconds`처럼 camelCase를 사용한다.

```json
{
  "status": "OK",
  "result": [
    {
      "id": 2167,
      "name": "Codeforces Round 1062 (Div. 4)",
      "type": "ICPC",
      "phase": "FINISHED",
      "frozen": false,
      "durationSeconds": 8100,
      "startTimeSeconds": 1761662100
    }
  ]
}
```

목록이 비어 있는 유형도 파일은 필요하며 `{"status":"OK","result":[]}`로 준비할 수 있다. 운영자는 각 유형에 맞는 완료 대회를 제공한다. 로딩 시 유형이나 완료 여부를 자동으로 분류하지 않는다. `.github/fixture/divisions`에는 테스트용 예제가 있으며, 운영용 최신 대회 목록과는 별개다.

로컬에서 `backend/gradlew`의 `bootRun` 태스크를 사용하면 애플리케이션은 저장소 루트에서 실행되어 루트의 `data/divisions`를 읽는다. IDE에서 메인 클래스를 직접 실행할 때도 작업 디렉터리를 저장소 루트로 지정하거나 `APP_DIVISIONS_DIRECTORY`에 데이터 디렉터리의 절대 경로를 지정한다.

Docker 배포에서는 호스트의 `data/divisions`를 `/app/data/divisions`에 읽기 전용으로 마운트하고 `APP_DIVISIONS_DIRECTORY=/app/data/divisions`를 지정한다.

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

## 현재 제약

- 검색용 Codeforces API 캐시, 재시도와 호출 제한 전용 처리가 없다.
- 추천은 균등 무작위이며 난이도·최근성·대회 길이는 고려하지 않는다.
- 대회 목록 JSON은 운영자가 직접 갱신해야 한다.
