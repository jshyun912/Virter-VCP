# Virter

여러 Codeforces 사용자의 참여 이력을 비교해, 모두가 아직 참여하지 않은 Virtual Contest를 최대 세 개 추천합니다.

## 로컬 개발

### 필수 데이터

저장소에 포함되지 않는 `data/divisions` 디렉터리에 다음 파일을 준비해야 합니다.

- `Div1.json`
- `Div1Plus2.json`
- `Div2.json`
- `Div2Educational.json`
- `Div3.json`
- `Div4.json`
- `others.json`

파일은 Codeforces API 응답과 같은 `{ "status": "OK", "result": [...] }` 형식이어야 합니다. 하나라도 없거나 JSON 형식이 잘못되면 백엔드는 시작하지 않습니다.

### 실행

```bash
cd backend
APP_DIVISIONS_DIRECTORY="$PWD/../data/divisions" ./gradlew bootRun
```

다른 터미널에서 프론트엔드를 실행합니다. 개발 서버의 `/api` 요청은 `localhost:8080`의 백엔드로 전달됩니다.

```bash
cd front
npm ci
npm run dev
```

## Docker 배포

NGINX Proxy Manager와 Virter가 같은 Docker 호스트에서 실행되어야 합니다. NPM이 참가한 외부 네트워크 이름을 확인하고 `.env`에 설정합니다.

```bash
cp .env.example .env
docker network ls
```

기본값은 `PROXY_NETWORK=proxy`입니다. 해당 네트워크가 없다면 기존 NPM 네트워크 이름으로 바꾸거나 새로 생성합니다.

```bash
docker network create proxy
docker compose up -d --build
```

NPM의 Proxy Host는 다음과 같이 설정합니다.

- Scheme: `http`
- Forward Hostname: `virter-frontend`
- Forward Port: `80`
- 도메인과 SSL 인증서: 운영 환경에 맞게 NPM에서 설정

프론트엔드와 백엔드는 호스트 포트를 공개하지 않습니다. NPM은 프론트엔드에만 접근하며 `/api/*` 요청은 프론트 Nginx가 내부 네트워크의 백엔드로 전달합니다.

## 검증

```bash
cd backend && ./gradlew test
cd ../front && npm test && npm run check && npm run build
cd .. && docker compose config
```

## 문서

- [아키텍처](ARCHITECTURE.md)
- [기여 가이드](CONTRIBUTE.md)
