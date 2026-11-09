# Virter 아키텍처

## 목적

여러 Codeforces 사용자의 참여 이력을 기준으로, 모두에게 아직 참여 이력이 없는 Virtual Contest 후보를 최대 세 개 추천한다.

## 기술 구성

| 영역 | 기술 | 역할 |
| --- | --- | --- |
| Frontend | SvelteKit / Nginx | 사용자 입력, 추천 결과 표시, 백엔드 내부 프록시 |
| Backend | Spring Boot 4 / Java 25 | 검색 API, 헬스 API와 Codeforces 연동 |
| Cache | Redis 8.10.3 | 핸들별 참여 대회 ID의 2시간 캐시와 분산 락 |
| 외부 API | Codeforces API | 사용자 제출 이력과 완료 대회 목록 조회 |
| 대회 목록 파일 | `data/divisions/*.json` | 저장소의 빈 목록에서 시작해 자동 갱신하는 Div별 완료 대회 목록 |

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
data/divisions/                빈 JSON 7개를 Git으로 제공하며 실행 중 대회 목록을 추가
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
  1. 각 handle의 참여 대회 ID 캐시를 조회하고, 없으면 락을 획득해 user.status를 조회
  2. 참여한 contest ID를 합침
  3. dif가 선택한 Div 목록에서 참여 이력을 제외
  4. 후보를 섞어 최대 3개 반환
        |
        v
SearchResponse { contestList }
```

`CodeforcesService`는 `RedisService`로 캐시를 읽고, 캐시가 없을 때 `RestClient`로 `user.status`를 호출한다. 대회 목록은 최초 JSON을 읽은 뒤 시작 시와 매일 한국 시간 낮 12시에 Codeforces에서 갱신하며, 검색은 메모리에 보관한 목록을 사용한다.

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

각 핸들의 참여 대회 ID는 순서대로 조회한다. Redis에 유효한 캐시가 있으면 Codeforces를 호출하지 않는다. 연결·읽기 타임아웃은 `backend/src/main/resources/application.yaml`의 다음 설정으로 조절한다. 이 값은 핸들별 HTTP 호출에 적용되며 전체 검색 요청의 제한 시간은 아니다.

```yaml
app:
  codeforces:
    connect-timeout: 5s
    read-timeout: 30s
```

### Redis 캐시와 장애 처리

Redis에는 제출 기록 원문 대신 참여한 대회 ID의 집합을 핸들별로 저장한다. 최초 저장 시점부터 2시간 동안 유지하며, 재검색해도 값이나 TTL을 갱신하지 않는다. 모든 집합에 대회 ID `0`을 표식으로 추가해 제출 기록이 없는 사용자도 캐시하고, 검색에 사용할 때 표식을 제거한다. TTL이 없는 기록은 신뢰하지 않고 캐시 미스로 처리한다. TTL 확인과 읽기, TTL을 포함한 최초 저장은 Lua 스크립트로 원자적으로 수행한다.

[Codeforces Submission 명세](https://codeforces.com/apiHelp/objects#Submission)에서 `contestId`는 생략될 수 있다. ID가 없는 제출은 참여 대회 집합과 캐시 저장 대상에서 제외한다.

캐시가 없으면 `lock:codeforces:{handle}` 키로 락을 획득하고 캐시를 다시 확인한 뒤 조회한다. 락의 유효 기간은 60초이며 대기 한도는 40초다. 서로 다른 핸들은 별도로 처리하며, 락 해제 시 소유 토큰을 검사해 이전 요청이 새 소유자의 락을 지우지 않도록 한다.

`RedisService.tryLock()`은 획득을 한 번 시도하고 실패하면 `null`을 반환한다. `CodeforcesService`는 100ms 간격으로 캐시 확인과 락 획득을 재시도한다. 대기 중 캐시가 채워지면 락 해제를 기다리지 않고 결과를 반환하며, 다른 요청이 보유한 락은 해제하지 않는다.

Codeforces의 연결·읽기 타임아웃은 각각 양수이고 합계가 40초 이하여야 한다. 60초 락에서 Redis 명령과 응답 처리를 위해 20초의 여유를 남기며, 이 범위를 벗어나는 설정은 시작 시 거부한다. 이는 HTTP 설정의 상한이며 긴 JVM 정지나 후처리 전체의 실행시간을 보장하지는 않는다. 락의 자동 연장은 사용하지 않는다.

Redis 연결 실패나 명령 타임아웃이 발생하면 백엔드 인스턴스당 동시에 한 요청만 Codeforces를 직접 조회하고, 초과 요청은 HTTP 503과 `SEARCH_TEMPORARILY_UNAVAILABLE`로 응답한다. 락 대기 실패나 메모리 부족 등 그 밖의 Redis 명령 실패도 같은 503으로 응답하며 직접 조회를 추가하지 않는다. 이미 Codeforces에서 조회한 뒤 캐시 저장만 실패했다면 재조회하지 않고 가져온 결과를 반환한다. 락 해제 실패는 원래 결과나 예외를 보존하고 락의 TTL 만료에 맡긴다.

Redis 데이터 메모리는 64MiB, 컨테이너 전체는 128MiB로 제한한다. `noeviction`으로 만료 전 캐시와 락을 보호하며, 한도에 도달하면 새 쓰기를 거부한다. 새 RDB 스냅샷과 AOF 저장은 비활성화한다. 비밀번호는 Git에서 제외한 루트 `redis_password.txt`를 Compose secret으로 전달하며, 백엔드는 Spring `configtree`로 읽는다. 준비 방법은 [백엔드 작업 가이드](backend/README.md#docker-redis-설정)를 따른다.

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
   |  캐시 조회 / 락
   +--------------------> redis:6379 (인증 필요, 호스트 포트 미공개)
   |  캐시 미스 또는 제한된 장애 우회
   |
   v
Codeforces API
```

NPM, 프론트엔드, 백엔드는 같은 Compose에서 실행한다. NPM과 프론트엔드는 Compose가 생성하는 proxy 네트워크를 공유한다. NPM의 80·443 포트는 외부에 공개하고, 관리 화면용 81 포트는 호스트의 127.0.0.1에만 바인딩한다. 프론트엔드는 호스트 포트를 공개하지 않고 NPM을 통해 접근한다. NPM은 `proxy-manager/localhost.conf`를 HTTP 설정에 포함하여 `http://localhost`와 `http://127.0.0.1` 요청을 프론트엔드로 전달한다. 외부 도메인과 TLS 인증서는 NPM 관리 화면에서 설정한다. 백엔드는 프론트엔드와의 app 네트워크에만 참가하고 호스트 포트를 공개하지 않는다. app 네트워크는 Codeforces API 호출을 위해 외부 통신을 허용한다. NPM의 설정과 SQLite 데이터, TLS 인증서는 별도의 Docker 볼륨에 보관한다.

Redis도 같은 Compose의 app 네트워크에 참가하며 6379를 호스트에 공개하지 않는다. 백엔드는 Redis의 인증된 헬스체크가 성공한 뒤 시작한다.

### 이미지 빌드와 데이터

백엔드는 Java 25 JDK 빌드 단계에서 Gradle로 `bootJar`를 실행하고, 실행 단계에는 Java 25 JRE와 `app.jar`만 복사한다. 프론트엔드는 Node.js 24 빌드 단계에서 `npm ci`와 `npm run build`를 실행하고, 생성된 정적 파일을 Nginx 이미지에 복사한다. Docker로 실행할 때 호스트에 Java·Node.js를 설치하거나 JAR·프론트엔드 빌드를 미리 만들 필요는 없다.

대회 JSON은 이미지에 포함하지 않고 실행 중 백엔드에 읽기·쓰기 가능하게 마운트한다. 호스트의 디렉터리와 파일에도 백엔드가 쓸 수 있는 권한이 필요하다. 자동 갱신은 파일 저장 후 메모리 목록에 반영하므로 재시작할 필요가 없다. 프론트엔드는 이 JSON을 직접 읽지 않는다.

## 헬스 API와 Actuator

애플리케이션의 요약 상태는 `GET /health`로 확인한다. Compose 배포에서 프록시를 통한 경로는 `GET /api/health`다.

```bash
curl -i http://localhost/api/health
```

`HealthController`는 `HealthService`가 반환한 결과에 따라 HTTP 상태를 선택한다. 응답 필드는 camelCase를 사용하며, 캐시를 사용하는 동안에는 마지막 점검 결과를 나타낸다.

| 점검 결과 | HTTP 상태 | 응답 본문 |
| --- | ---: | --- |
| 통합 상태와 모든 구성 요소가 `UP`이며 Redis도 `UP` | 200 | `{"isHealthy":true,"backendStatus":200,"redisStatus":200}` |
| Redis 연결·인증 실패 등 Redis 상태가 `UP` 이외 | 503 | `{"isHealthy":false,"backendStatus":503,"redisStatus":503}` |
| Redis는 `UP`이지만 다른 구성 요소가 비정상 | 503 | `{"isHealthy":false,"backendStatus":503,"redisStatus":200}` |
| 최초 점검이 진행 중인 동안 들어온 다른 요청 | 503 | `{"isHealthy":false,"backendStatus":503,"redisStatus":503}` |

`backendStatus`는 Redis를 포함한 Actuator 통합 상태와 모든 최상위 구성 요소가 `UP`일 때 200, 그 밖에는 503이다. `redisStatus`는 Redis 점검 항목이 `UP`이면 200이고 비정상이거나 항목이 없으면 503이다. 두 값이 모두 200번대일 때만 전체 정상이다. `HealthService`는 주입받은 `HealthEndpoint.health()`를 직접 호출한다. 자기 서버에 HTTP 요청을 보내지 않으며 Codeforces 상태도 조회하지 않는다. Actuator의 상세 점검 내용은 요약 응답에 포함하지 않는다. Redis 점검은 연결·인증 상태를 확인하므로, 정상이어도 메모리 부족으로 새 캐시 저장이 거부되거나 Codeforces 검색이 실패할 수 있다.

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
| 락 대기 실패·Redis 명령 실패 또는 장애 우회 조회의 동시 요청 한도 초과 | 503 | `SEARCH_TEMPORARILY_UNAVAILABLE` |

공통 예외 처리기는 클라이언트에 상태와 오류 코드만 반환하고 상세 원인은 서버 로그에 기록한다.

프론트엔드는 `SEARCH_TEMPORARILY_UNAVAILABLE`을 받으면 결과 영역에 “검색 서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.”를 표시한다. 입력한 핸들과 선택한 대회 유형을 유지하며, 사용자가 대회 찾기 버튼을 다시 눌러 재검색할 수 있다. 자동 재시도는 하지 않고, 재검색에 성공하면 오류 안내를 지우고 추천 결과를 표시한다.

Spring MVC가 처리하는 HTTP 오류는 원래 상태와 응답 헤더를 유지한다. 존재하지 않는 경로는 404, 지원하지 않는 메서드는 405와 `Allow` 헤더를 반환하며, 이러한 4xx 응답의 오류 코드는 `HTTP_REQUEST_FAILED`다. 응답 본문은 기존 `status`, `message` 형식을 유지하고 예상하지 못한 서버 예외는 500과 `INTERNAL_SERVER_ERROR`로 처리한다.

예를 들어 검색 사용자를 찾을 수 없으면 HTTP 400과 다음 본문을 반환한다. `message`는 상세 설명이 아닌 오류 코드이며, 프론트엔드가 이를 사용자용 문구로 변환한다.

```json
{
  "status": "FAILED",
  "message": "CODEFORCES_USER_NOT_FOUND"
}
```

## Div 데이터 초기화

애플리케이션 시작 시 중단된 갱신을 복구한 뒤 다음 파일을 검증하고 검색용 메모리 목록을 채운다.

| 파일 | 대회 유형 |
| --- | --- |
| `Div1.json` | Div. 1 |
| `Div1Plus2.json` | Div. 1 + Div. 2 |
| `Div2.json` | Div. 2 |
| `Div2Educational.json` | Div. 2 Educational |
| `Div3.json` | Div. 3 |
| `Div4.json` | Div. 4 |
| `others.json` | 기타 대회 |

저장 경로는 `app.divisions-directory` 설정으로 변경할 수 있으며 기본값은 실행 디렉터리 기준 `data/divisions`다. 저장소는 `{"status":"OK","result":[]}` 형태의 빈 JSON 일곱 개를 제공한다. 시작 시 API 조회에 성공하면 새 완료 대회를 파일과 메모리 목록에 추가한다. 파일 누락·읽기 실패·잘못된 JSON, 유효하지 않은 응답 형식, `FINISHED`가 아닌 로컬 대회 또는 로컬 ID 중복이 있으면 애플리케이션 시작 실패로 처리한다. ID 중복은 각 파일 안과 일곱 파일 사이에서 모두 검사하며 오류에 ID와 파일명을 기록한다. API 응답에는 미종료 대회도 포함되므로, 완료 상태 강제 검증은 로컬 파일에만 적용한다.

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

목록이 비어 있는 유형도 파일은 필요하다. 위 예시는 갱신 후 파일의 형태이며, 대회 데이터 자체를 커밋·PR·병합 사항에 포함하지 않는다. 로컬 변경을 숨기는 `skip-worktree` 설정과 데이터 관리 규칙은 [기여 가이드](CONTRIBUTING.md#로컬-대회-데이터-관리)를 따른다. `.github/fixture/divisions`의 테스트용 예제는 실행 중 갱신하는 대회 목록과 별개다.

로컬 목록을 읽은 뒤 시작 시와 매일 낮 12시(`Asia/Seoul`)에 다음 갱신을 수행한다.

1. 인증 없이 `GET /api/contest.list?gym=false`를 한 번 호출한다. Gym과 아직 종료되지 않은 대회를 제외하고 `phase`가 `FINISHED`인 대회만 사용한다.
2. `others.json`을 포함한 일곱 파일의 대회 수 합계와 완료 대회 수가 같으면 갱신을 끝낸다.
3. 조회한 완료 대회 수가 더 적으면 갱신 오류로 처리해 갱신을 중단하고 기존 JSON 파일과 메모리 목록을 유지한다.
4. 더 많으면 일곱 파일 전체의 ID와 비교해 없는 ID의 새 완료 대회만 제목으로 분류하고 해당 목록 뒤에 추가한다. 기존 대회의 필드 값·분류·순서를 유지하며 API 응답에 없는 로컬 대회도 삭제하지 않는다. Educational Div. 2와 Div. 1 + Div. 2를 단일 Div보다 먼저 판별하며, 어느 유형에도 해당하지 않으면 `others.json`에 넣는다.
5. 원본 속성을 복사한 임시 파일에 새 JSON을 기록해 Linux Docker에서도 기존 소유자·그룹·접근 권한을 보존한다. POSIX 환경에서는 일곱 준비 파일과 백업의 속성을 원본과 비교하고, 불일치하면 교체 전에 갱신을 중단한다. 준비가 끝나면 `.refresh-*/pending` 표시를 남기고 기존 파일을 교체한다. 모든 교체가 끝나면 표시를 지우고 검색용 메모리 목록을 교체한다. 진행 중인 검색은 갱신 전후 중 하나의 목록을 사용한다.

파일 교체에 실패하면 원본 백업으로 일곱 파일 전체를 복구한다. 프로세스 강제 종료 등으로 `pending` 표시가 남으면 다음 시작 시 로컬 JSON을 읽기 전에 복구하며, 이후 갱신에서도 개수 비교 전에 확인한다. 복구는 백업을 복사해 수행하고 POSIX 속성을 비교한 뒤 교체하므로, 소유자·그룹·접근 권한이 달라지면 해당 파일을 교체하지 않는다. 복구 중 다시 중단되어도 재시도할 수 있도록 복구를 완료하기 전에는 표시와 백업을 삭제하지 않는다. 복구 표시 조회에서 `NoSuchFileException`만 부재로 처리하며, 접근 거부 등 다른 오류는 전파한다. 시작 시 상태를 확인하거나 복구할 수 없으면 서버를 실행하지 않는다. 표시가 없다고 확인된 임시 디렉터리만 정리한다.

초기 API 갱신을 시도한 뒤에도 완료 대회가 전체 0개라면 `CONTEST_DATA_LOAD_FAILED`로 서버 시작에 실패한다. 기존 대회가 있으면 Codeforces 조회나 갱신에 실패해도 로그를 남기고 이전 목록으로 계속 실행하며 즉시 재시도하지 않는다. Compose의 `restart: unless-stopped`는 시작 실패 시 자동 재시작하므로 설치 직전에 Codeforces 상태를 확인한다.

검색은 메모리에 보관한 목록을 사용하며, 실행 중 JSON 파일을 직접 수정해도 다시 읽지 않는다. 자동 갱신은 기존 메모리 목록을 기준으로 파일을 저장하므로, 수동 보정은 백엔드를 중단한 뒤 수행하고 재시작해 반영한다.

로컬에서 `backend/gradlew`의 `bootRun` 태스크를 사용하면 애플리케이션은 저장소 루트에서 실행되어 루트의 `data/divisions`를 읽는다. IDE에서 메인 클래스를 직접 실행할 때도 작업 디렉터리를 저장소 루트로 지정하거나 `APP_DIVISIONS_DIRECTORY`에 데이터 디렉터리의 절대 경로를 지정한다.

Docker 배포에서는 호스트의 `data/divisions`를 `/app/data/divisions`에 읽기·쓰기 가능하게 마운트하고 `APP_DIVISIONS_DIRECTORY=/app/data/divisions`를 지정한다.

### 제목 파싱을 사용하는 이유와 한계

[Codeforces 공식 API의 `Contest` 객체](https://codeforces.com/apiHelp/objects#Contest)에는 Div. 1·2·3·4 또는 Educational 여부를 명시하는 전용 필드가 없다. `type`은 `CF`·`IOI`·`ICPC`와 같은 채점 방식을 뜻하므로 디비전 구분에 사용할 수 없다. 정확한 구분을 위해서는 대회 상세 페이지나 공식 공지의 참가 대상·레이팅 조건 등을 추가로 확인해야 한다.

그러나 [Codeforces의 `robots.txt`](https://codeforces.com/robots.txt)는 대회 상세 페이지가 속한 `/contest` 경로의 크롤링을 제한한다. 이 프로젝트는 상세 페이지를 자동 수집하는 대신 공식 API가 반환하는 대회 제목(`name`)에서 Div 표기와 `Educational` 문자열을 파싱해 분류한다. 이는 공식 디비전 정보를 직접 읽는 방식이 아니라 제목을 근거로 추정하는 방식이므로 정확성을 보장하지 않는다.

특히 오래된 대회는 제목 표기가 현재와 다르거나 디비전 표기가 빠져 있어 오분류될 수 있다. 예를 들어 Educational 대회라도 제목에 Div. 2 표기가 없으면 현재 규칙에서는 `others.json`으로 분류된다. 자동 갱신은 새 ID만 추가하고 기존 항목을 재분류하지 않으므로, 정확한 분류가 필요한 과거 대회는 상세 정보를 직접 확인해 로컬 JSON의 분류를 관리해야 한다.

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

비트 값 `64`는 기타 대회 목록을 위한 예약 값이다. 현재 검색 추천 로직은 이 비트를 사용하지 않는다.

## 현재 제약

- 파일 복구는 단일 백엔드 프로세스 중단을 대상으로 한다. 여러 인스턴스의 같은 디렉터리 동시 갱신이나 전원 차단 시 디스크 동기화까지 보장하지 않는다.
- Codeforces API의 전역 호출 간격 제한과 자동 재시도는 구현하지 않았다. Redis 장애 시 동시 1건 제한만으로 전체 호출 빈도를 보장하지는 않는다.
- 추천은 균등 무작위이며 난이도·최근성·대회 길이는 고려하지 않는다.
