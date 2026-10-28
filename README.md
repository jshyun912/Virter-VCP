# Virter

<img src="/data/logo/virter-logo.png" style="width: 400px; height: auto;" alt="virter_logo">

여러 사람들과 Virtual Contest를 하고 싶은데 무슨 set을 돌지 고민이신가요? Virter가 골라드립니다!

---
## 사용 방법

현재는 배포되고 있지 않습니다.

### Docker로 실행

Java 25, Docker Compose와 `data/divisions`의 대회 JSON 7개가 필요합니다. 데이터 파일 이름과 형식은 [아키텍처 문서](ARCHITECTURE.md)를 참고하세요.

먼저 `backend` 디렉터리에서 실행 JAR을 빌드합니다. 현재 백엔드 Dockerfile은 이 JAR을 이미지에 복사합니다.

```bash
cd backend
./gradlew bootJar
cd ..
```

저장소 루트에서 실행하면 프론트엔드, 백엔드와 Nginx Proxy Manager가 함께 시작됩니다. 네트워크는 Compose가 생성합니다. 호스트의 80, 443, 81 포트가 사용 가능해야 합니다.

```bash
docker compose up -d --build
```

로컬에서는 `http://localhost`로 접속합니다. Proxy Manager가 `proxy-manager/localhost.conf` 설정을 읽어 프론트엔드로 전달합니다. `http://127.0.0.1`도 사용할 수 있으며, 이 로컬 주소들은 HTTP로 접속합니다. 프론트엔드는 Docker 네트워크에서만 접근하며, Proxy Manager 관리 화면 `http://localhost:81`은 호스트의 `127.0.0.1`에만 공개합니다.

Proxy Manager 첫 실행 시 관리자 계정 설정을 진행합니다. 외부 사용자용 도메인은 관리 화면에서 Proxy Host를 추가하고 Scheme `http`, Forward Hostname `virter-frontend`, Forward Port `80`으로 설정합니다. 도메인과 HTTPS는 Proxy Manager에서 설정합니다. `localhost`는 각 사용자의 PC를 뜻하므로 외부 사용자는 서버 도메인으로 접속합니다. 로컬 주소용 Proxy Host는 파일에서 관리하므로 관리 화면에서 중복으로 추가하지 않습니다.

원격 서버의 관리 화면은 SSH 터널(`ssh -L 8181:127.0.0.1:81 사용자@서버`)을 연결한 뒤 내 PC에서 `http://localhost:8181`로 접속합니다. Compose의 포트 제한 변경은 `docker compose up -d`로 컨테이너를 재생성해야 적용됩니다.

Proxy Manager의 설정과 SQLite 데이터는 `proxy_manager_data`, 인증서는 `proxy_manager_letsencrypt` 볼륨에 보관됩니다. `docker compose down` 후에도 유지되며, `docker compose down -v`는 이 데이터를 삭제합니다.

프론트엔드는 `/api/*`를 백엔드로 전달하며, 백엔드의 8080 포트는 Docker 내부 네트워크에서만 접근합니다. 대회 JSON은 읽기 전용으로 마운트되고 백엔드 시작 시 로드됩니다. JSON 변경 후에는 `docker compose restart backend`를 실행합니다.

---

## 참고하세요

[로봇이신가요?](/AGENTS.md)  
[프로젝트 구조](/ARCHITECTURE.md)  
[코드 규칙](/CONTRIBUTING.md)

---
* 로고 이미지 및 웹페이지 텍스쳐는 생성형 AI를 활용하여 제작되었습니다.
