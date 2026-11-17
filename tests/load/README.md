# 검색 API 로컬 부하 검증

실제 프론트엔드 Nginx → 백엔드 → Redis 경로에서 캐시, 동시 요청, Redis 장애와 복구를 확인한다. Codeforces는 지연과 호출 수를 제어하는 로컬 HTTP mock으로 대체한다. 프론트엔드는 운영 Nginx 설정을 사용하며 NGINX Proxy Manager와 TLS 구간은 포함하지 않는다.

2026-11-10 측정 수치와 당시 Nginx 연결 오류 분석은 [결과 보고서](REPORT.md)를 참고한다. 이후 연결 재사용을 적용한 2026-11-12의 [변경 전후 비교](../nginx/REPORT.md)에서는 무대기 조건도 통과했다. 현재 실행은 개선된 Nginx 설정을 사용하므로 11월 10일의 502 발생을 기대 결과로 삼지 않는다.

## 실행

Node.js 24와 Docker Compose가 필요하다. 저장소 루트에서 실행한다.

```bash
# 측정값 집계와 mock의 자체 테스트 (Docker 불필요)
node --test tests/load/*.test.mjs

# 이미지 빌드 후 전체 검증
node tests/load/run.mjs

# 빠른 실행 확인: 동시 요청 수별 캐시 적중 측정을 1초로 단축
LOAD_DURATION_SECONDS=1 node tests/load/run.mjs

# 대기 없이 계속 요청해 포화 상태 탐색
LOAD_THINK_MS=0 node tests/load/run.mjs
```

`LOAD_DURATION_SECONDS`는 1~300 사이의 정수이며 기본값은 30초다. 이 시간은 동시 요청 수별 캐시 적중 측정에 각각 적용된다. 콜드 캐시와 장애 검증 시간, 이미지 빌드 시간은 별도로 필요하다.

캐시 적중 지속 요청은 가상 사용자 수를 1·10·30·100명으로 늘리며 각 사용자가 응답을 받은 뒤 기본 250ms 대기한다. `LOAD_THINK_MS`는 0~10000 사이의 정수다. 이 간격은 캐시·락·복구의 기능 검증을 위한 것으로, 최대 처리량을 측정한 값이 아니다. 콜드 캐시와 장애 시나리오는 대기 간격 없이 동시 요청한다.

매번 전용 Compose 프로젝트와 내부 네트워크를 만들고 호스트의 `127.0.0.1`에 임의 포트를 배정한다. `.github/fixture/divisions`를 임시 디렉터리로 복사하므로 로컬 대회 데이터, 운영 컨테이너, 운영 Redis 비밀번호를 사용하지 않는다. 백엔드의 `APP_CODEFORCES_BASE_URL` 기본값은 `https://codeforces.com/api`지만 이 Compose는 항상 mock 주소로 덮어쓴다. 실행 중 실제 Codeforces에 부하를 보내지 않는다.

## 검증 범위와 결과

동시 요청 수 1, 10, 30, 100에서 다음을 측정하고 기대 응답과 호출 수를 검증한다.

| 시나리오 | 확인 사항 |
| --- | --- |
| 캐시 적중 지속 요청 | 모두 200, `user.status` 호출 0회 |
| 같은 핸들의 콜드 캐시 동시 요청 | 모두 200, `user.status` 호출 1회 |
| 서로 다른 핸들의 콜드 캐시 동시 요청 | 모두 200, 핸들별 `user.status` 호출 1회 |
| Redis 중단 | 백엔드 인스턴스의 직접 조회는 동시에 1건, 나머지는 503 |

Redis를 다시 시작한 뒤에는 백엔드를 재생성하지 않고 재연결과 캐시 저장을 확인한다. 복구 후 동시 요청 30건으로 캐시 적중 시 외부 조회 0회, 같은 콜드 핸들 조회 시 1회를 확인한다. mock의 `user.status` 응답 지연은 정상 시 1초, Redis 장애 시 5초다.

실행 중 출력되는 `/tmp/virter-load-*/` 아래에 결과를 보관한다.

- `report.json`: 실행 환경, 시나리오별 HTTP 상태 건수, 처리량, 중앙값·p95·최대 지연, mock 호출 수와 최대 동시 호출 수, Redis 명령 수 증가량, 자원 표본, 검증 결과
- `resources.json`: 백엔드와 Redis의 `docker stats` CPU·메모리 표본
- `compose-up.log`, `compose.log`: 기동 출력과 컨테이너 로그

`report.json`의 `passed`가 `true`이고 종료 코드가 0이면 검증을 통과한 것이다. Redis 명령 수 증가량은 측정용 명령과 헬스체크도 포함하는 근삿값이며, Redis 중단 시에는 측정하지 않는다. 짧은 시나리오에는 자원 표본이 없을 수 있다.

각 시나리오의 `upstreamTimeWaitSockets`는 프론트엔드에서 백엔드 8080번 포트로 연결했다가 `TIME_WAIT` 상태에 남은 IPv4 소켓 수다. 2026-11-10의 개선 전 설정에서는 대기 없는 연속 요청에서 Nginx의 `connect() ... failed (99: Address not available)`와 502가 관찰됐다. 이후 연결 재사용으로 소켓 수와 502가 감소한 근거는 [Nginx 변경 결과](../nginx/REPORT.md)에 보관한다. 실패한 실행의 보고서와 로그도 보존하며, 오류 응답의 빠른 반환을 성공 처리량으로 해석하지 않는다.

이 결과는 캐시와 장애 처리 검증용이다. 부하 생성기와 mock이 같은 호스트를 공유하고 소량의 합성 데이터를 사용하므로 운영 사용자 수나 실제 Codeforces 연동 처리량을 나타내지 않는다. 통과 여부에 임의의 RPS 목표는 사용하지 않는다.

## 정리와 강제 중단 후 복구

정상 종료와 검증 실패 모두 해당 테스트 프로젝트만 컨테이너·볼륨과 함께 제거한다. 임시 대회 데이터는 삭제하고 보고서와 로그는 남긴다.

프로세스 강제 종료 등으로 자동 정리가 실행되지 않았다면 시작 시 출력된 **정확한 프로젝트 이름**과 **Artifacts 경로**를 사용해 저장소 루트에서 정리한다. 아래 두 자리표시자를 실제 출력값으로 바꾼다. 프로젝트 이름은 `report.json`의 `project`에도 기록된다.

```bash
LOAD_DATA_DIRECTORY='/tmp/virter-load-<Artifacts 경로의 접미사>/divisions' \
  docker compose -p 'virter-load-<출력된 프로젝트 접미사>' \
  -f tests/load/compose.yaml down --volumes --remove-orphans
```

`LOAD_DATA_DIRECTORY`는 Compose 변수 해석에 필요하다. 정리할 때는 이미 삭제된 임시 `divisions` 경로를 지정해도 된다. 다른 프로젝트 이름이나 운영 Compose 파일로 바꾸지 않는다.
