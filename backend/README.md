# BACKEND 작업 규칙

- DTO `record`의 선언부에서는 변수 하나를 한 줄에 작성합니다.
- Lombok으로 코드를 줄일 수 있으면 사용합니다.

## 로컬 실행

Java 25를 준비한 뒤 실행합니다. 저장소 루트의 `data/divisions`에는 빈 대회 JSON 7개가 포함되어 있으며, 시작 시 API 조회에 성공하면 완료 대회 목록을 추가합니다.

활성 프로필은 `src/main/resources/application.yaml`의 `spring.profiles.active`에서 선택합니다. 기본값은 `prod`이며 Redis 호스트로 Docker 서비스명 `redis`를 사용합니다. 로컬 개발에서는 값을 `dev`로 바꾸면 `127.0.0.1:6379`로 연결합니다. `dev` 이외의 프로필에서는 `redis:6379`를 사용합니다.

`dev` 실행에는 호스트에서 접근할 수 있는 별도 Redis가 필요합니다. 저장소의 Compose Redis는 호스트에 6379를 공개하지 않으므로 프로필만 변경해서는 연결할 수 없습니다. 로컬에 Redis를 실행하거나 아래처럼 개발용 컨테이너를 별도로 띄웁니다. 비밀번호가 있는 별도 Redis를 사용하면 `SPRING_DATA_REDIS_PASSWORD`도 실행 환경에 설정합니다.

```bash
docker run --rm -d --name virter-redis-dev -p 127.0.0.1:6379:6379 redis:8.10.3-alpine
```

```bash
cd backend
./gradlew bootRun
```

`bootRun`은 애플리케이션의 실행 디렉터리를 저장소 루트로 지정하므로 기본 경로인 `data/divisions`에서 대회 목록을 읽습니다. 테스트의 실행 디렉터리는 `backend`이며 테스트 전용 JSON을 사용합니다.

시작 시 JSON 7개를 먼저 검증하며, 파일 누락·읽기 실패·잘못된 JSON 또는 `status`가 `OK`가 아니거나 `result`가 유효하지 않으면 시작에 실패합니다. 로컬 목록을 읽은 뒤 시작 시와 매일 낮 12시(`Asia/Seoul`)에 `contest.list?gym=false`를 한 번 조회하고 `FINISHED` 대회만 사용합니다. `others.json`을 포함한 일곱 파일의 대회 수 합계와 조회 결과의 완료 대회 수가 같으면 갱신을 건너뛰고, 조회 결과가 더 적으면 갱신 오류로 처리해 기존 JSON 파일과 메모리 목록을 유지합니다. 더 많으면 일곱 파일 전체에 없는 ID의 새 완료 대회만 제목으로 분류해 해당 목록 뒤에 추가합니다. 기존 대회의 필드 값·분류·순서를 유지하며 API 응답에서 빠진 로컬 대회도 삭제하지 않습니다.

JSON은 임시 파일로 저장한 뒤 기존 파일을 교체하며, 실패하면 이전 파일로 되돌립니다. 모든 파일 저장에 성공한 뒤 검색용 메모리 목록을 교체합니다. Codeforces 조회나 파일 갱신에 실패하면 로그를 남기고 이전 메모리 목록으로 계속 실행하며 즉시 재시도하지 않습니다. 자동 갱신에는 JSON 디렉터리와 파일의 쓰기 권한이 필요하며, 성공한 갱신은 재시작 없이 검색에 반영됩니다. 자세한 분류 규칙은 [아키텍처 문서](../ARCHITECTURE.md#div-데이터-초기화)를 참고합니다.

빈 JSON 상태에서 최초 조회에 실패해도 서버는 시작합니다. 사용자 제출 이력 조회가 정상적으로 끝나더라도 추천 후보가 없어 `{"contestList":[]}`를 반환하며, 다음 예약 갱신이나 백엔드 재시작 시 대회 목록을 다시 조회합니다. 실행 중 JSON 파일을 직접 수정해도 메모리에 반영되지 않으므로, 수동 보정은 백엔드를 중단한 뒤 수행하고 재시작합니다. 로컬 대회 데이터는 커밋·PR·병합에서 제외하며, `skip-worktree` 설정 등은 [기여 가이드](../CONTRIBUTING.md#로컬-대회-데이터-관리)를 따릅니다.

IDE에서 메인 클래스를 직접 실행할 때도 작업 디렉터리를 저장소 루트로 지정합니다. 다른 위치의 JSON을 사용하거나 Docker에서 실행할 때는 `APP_DIVISIONS_DIRECTORY` 환경 변수에 컨테이너 또는 실행 환경 내의 절대 경로를 지정합니다. 예를 들어 Docker에서는 `/app/data/divisions`를 지정하고 해당 위치에 JSON 디렉터리를 마운트합니다.

## Docker Redis 설정

Compose를 처음 실행하기 전에 저장소 루트에 비밀번호 파일을 생성합니다. 아래 명령은 기존 파일이 있으면 덮어쓰지 않습니다.

```bash
test -e redis_password.txt || (umask 077; openssl rand -hex 32 | tr -d '\n' > redis_password.txt)
docker compose up -d --build
```

`redis_password.txt`는 64자리 무작위 16진수 비밀번호를 담으며 Git에서 제외합니다. Compose는 이를 Redis의 `/run/secrets/redis_password`와 백엔드의 `/run/secrets/spring.data.redis.password`에 마운트합니다. 백엔드는 Spring의 `configtree`로 비밀번호를 읽습니다. Redis는 인증된 PING으로 상태를 확인하고 호스트에 6379 포트를 공개하지 않습니다. 비밀번호 변경 후에는 Redis와 백엔드 컨테이너를 다시 생성합니다.

Compose의 파일 기반 secret은 호스트 파일을 암호화하지 않습니다. 파일 권한은 `600`으로 유지하고 저장소나 이미지에 포함하지 않습니다. CI에서는 운영 비밀번호 대신 실행마다 임시 비밀번호를 만듭니다.

100명분의 참여 대회 ID 캐시를 기준으로 Redis의 `maxmemory`는 **64MiB**, 컨테이너 전체 메모리는 **128MiB**로 제한합니다. Redis 8.10.3에서 100명에게 각각 대회 ID 3,000개와 0 표식을 저장한 합성 데이터는 Redis 사용 메모리 약 8.3MiB, 프로세스 RSS 약 29MiB였습니다. 실제 사용량은 대회 수와 동시 연결 수에 따라 달라집니다. 사용자 수 자체를 100명으로 제한하는 설정은 아닙니다.

캐시는 첫 저장부터 2시간 유지하며 이 기간에 갱신하지 않습니다. 만료 전 캐시와 분산 락을 보존하도록 `noeviction`을 사용하므로 메모리가 차면 기존 키를 지우는 대신 새 쓰기를 거부합니다. 새 RDB 스냅샷과 AOF 저장은 비활성화하며, 재시작 후 현재 캐시의 복구는 보장하지 않습니다.

캐시 저장만 실패하면 이미 조회한 검색 결과는 반환합니다. 메모리 부족으로 락을 획득할 수 없거나 락 대기가 40초를 초과하는 등 검색 진행이 불가능하면 HTTP 503과 `SEARCH_TEMPORARILY_UNAVAILABLE`로 응답합니다. 연결 실패·명령 타임아웃에서는 백엔드당 동시에 한 요청만 Codeforces를 직접 조회합니다.

락을 기다리는 동안에는 100ms 간격으로 캐시를 다시 확인합니다. 캐시가 채워지면 다른 요청이 보유한 락의 해제를 기다리지 않고 결과를 반환하며, 그 락을 해제하지 않습니다.

`app.codeforces.connect-timeout`과 `app.codeforces.read-timeout`은 모두 양수여야 하며 합계는 최대 40초입니다. 0(무한), 음수 또는 합계 초과 설정은 애플리케이션 시작 시 거부합니다. 60초 락 수명에서 Redis 명령과 응답 처리를 위한 20초 여유를 남기며, 기본값은 각각 5초·30초입니다. 이 검증은 HTTP 타임아웃의 설정 범위를 제한하며, 긴 JVM 정지나 모든 후처리 시간을 강제로 제한하지는 않습니다.

## 테스트 코드
- 테스트는 JUnit 5를 사용합니다.
- 모든 테스트에는 `@DisplayName`을 작성해 의도를 한국어로 설명합니다.
- 외부 의존성을 격리해야 하는 단위 테스트에는 Mockito를 사용합니다.
- Mockito 스터빙과 협력 객체 호출 검증에는 `BDDMockito.given(...)`, `then(...)`을 사용합니다.
- 테스트 본문은 Given / When / Then 순서로 작성합니다.
- 반복되는 Given 또는 When 조건은 `@Nested` 클래스로 그룹화하고, Then은 `@Test` 메서드로 작성합니다.
- Mockito가 필요 없는 순수 단위 테스트에는 Mockito를 사용하지 않습니다.
- 실제 파일 시스템이나 HTTP 서버를 사용하는 테스트는 단위 테스트와 분리해 통합 테스트로 작성합니다.

## Redis 통합 테스트

`RedisServiceTest`는 `integration` 태그를 사용하며, 기본적으로 Testcontainers가 일회용 Redis 컨테이너를 생성하고 종료합니다. Docker가 실행 중이고 현재 사용자에게 Docker 접근 권한이 있어야 합니다.

```bash
./gradlew test --tests '*RedisServiceTest'
```

Docker 대신 실행 중인 테스트용 Redis를 사용하려면 호스트와 포트를 함께 지정합니다.

```bash
REDIS_TEST_HOST=127.0.0.1 REDIS_TEST_PORT=16379 ./gradlew test --tests '*RedisServiceTest'
```

각 테스트는 고유한 키를 사용하고 자신이 만든 키만 정리합니다. 캐시의 0 표식, TTL 없는 데이터 거부, 만료 후 재저장, 유효한 캐시의 갱신 방지, 동시 저장과 락 소유권·만료를 검증합니다. Codeforces 서비스와 연결한 통합 테스트에서는 락 해제 없이 캐시가 채워졌을 때 대기 요청이 반환되는지와 실제 40초 대기 한도를 확인합니다. 인터럽트 보존과 HTTP 타임아웃 설정 범위는 단위 테스트에서 검증합니다.
