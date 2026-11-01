# 작업 가이드

프로그램 전체 구조를 확인하고 싶으시면 [ARCHITECTURE.md](/ARCHITECTURE.md)를 확인해주세요. 

## 공통
- Repository는 3개의 브랜치로 구성되어 있습니다.
  - main : 작업이 완료된 commit이 저장됩니다.
  - dev : 이 Repository의 Owner가 작업한 commit이 저장됩니다.
  - release : 현재 배포 상태중인 commit이 저장됩니다.
- Owner가 아니라면 PR은 무조건 main으로 올리셔야 합니다.


## 프론트엔드 작업 가이드

[front/README.md](front/README.md)

### 개발 서버 실행과 검색 확인

Node.js 24를 사용한다. 저장소 루트에서 다음을 실행한다.

```bash
cd front
npm ci
npm run dev
```

검색 기능을 사용하려면 별도 터미널에서 백엔드도 실행해야 한다. 백엔드 실행에 필요한 Java 25와 대회 JSON 준비 방법은 [백엔드 작업 가이드](backend/README.md)를 따른다. Vite 개발 서버는 `/api/*` 요청을 `http://localhost:8080`으로 전달하며 `/api` 접두사를 제거한다. Docker 실행에서는 프론트엔드 Nginx가 같은 역할을 수행한다.

화면에서 Codeforces 핸들을 입력하고 공백이나 Enter로 추가한다. 여러 핸들을 공백으로 구분해 한 번에 입력할 수도 있다. 같은 문자열의 중복 핸들은 하나로 합쳐진다. 삭제 버튼이나 빈 입력창에서 Backspace로 핸들을 삭제할 수 있다.

핸들을 추가하면 여섯 대회 유형이 기본 선택된다. 원하는 유형을 선택하고 대회 찾기를 누르면 최대 세 개의 결과를 보여주며, 결과를 누르면 해당 Codeforces 대회를 새 탭으로 연다. 유형을 모두 해제하거나 검색 중이면 검색 버튼을 비활성화한다. 검색 조건이 바뀌면 기존 결과를 비우고, 이전 조건의 늦은 응답은 화면에 반영하지 않는다.

### 프론트엔드 검증

`front`에서 다음을 실행한다. 현재 `npm test`는 Vitest를 사용하며, 도메인 로직과 Svelte 화면 테스트를 실행한다. 화면 테스트는 jsdom과 Testing Library를 사용한다.

```bash
npm test
npm run check
npm run build
```

## 백엔드 작업 가이드

[backend/README.md](backend/README.md)

백엔드 테스트와 실행 JAR 빌드는 `backend`에서 수행한다.

```bash
./gradlew test bootJar
```

헬스 테스트는 HTTP 상태와 JSON 응답, Actuator 상태 변환, 1분 캐시 경계와 복구, 동시 요청 처리, 관리 포트 8081 검증을 확인한다. `HealthService` 테스트는 실제 `HealthEndpoint`와 제어 가능한 점검 항목을 사용하며 Codeforces API를 호출하지 않는다.

## GitHub Actions CI

[ci.yaml](.github/workflows/ci.yaml)은 `main`을 대상으로 하는 Pull Request에서 실행된다. `dev`에 대한 푸시만으로는 실행되지 않는다.

1. `backend`와 `front` 작업이 병렬 실행된다. 백엔드는 Java 25에서 `./gradlew test`를, 프론트엔드는 Node.js 24에서 `npm ci`, `npm test`, `npm run check`를 실행한다.
2. 두 작업이 성공하면 `docker-build`가 백엔드와 프론트엔드 이미지를 최대 두 개 병렬로 빌드한다. 실제 애플리케이션 빌드는 각 Dockerfile 안에서 수행한다.
3. Compose를 시작하고 Proxy Manager의 81번 포트, 프론트엔드의 80번 포트와 페이지의 `Virter` 문자열, `/api/health` 응답의 `backendStatus: 200`을 확인한다.
4. 실패 시 컨테이너 상태와 로그를 출력한다. 성공·실패와 관계없이 테스트용 컨테이너와 볼륨을 제거한다.

현재 워크플로는 테스트와 Compose 실행 검증을 수행하며, 레지스트리로 이미지를 푸시하거나 운영 서버에 배포하지 않는다.

`.github/fixture/divisions`는 CI의 대회 JSON 입력이다. 워크플로가 이를 루트의 `data/divisions` 경로에 준비하여 Compose 마운트에 사용한다. 백엔드 테스트 자체는 `backend/src/test/resources/divisions`의 전용 데이터를 사용한다. 대회 JSON은 자동으로 내려받지 않으므로 두 종류의 테스트 데이터를 운영용 데이터와 구분해 유지한다.

토큰 권한은 `contents: read`로 제한하고, checkout 자격 증명은 저장하지 않는다. CI에서 `docker compose down --volumes`를 사용하는 것은 테스트용 볼륨을 정리하기 위해서다. 운영 환경에서는 같은 옵션이 Proxy Manager 설정과 인증서도 삭제하므로 그대로 실행하지 않는다.
