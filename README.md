# Virter

<img src="/data/logo/virter-logo.png" style="width: 400px; height: auto;" alt="virter_logo">

여러 사람들과 Virtual Contest를 하고 싶은데 무슨 set을 돌지 고민이신가요? Virter가 골라드립니다!


## 사용 방법

### 준비 사항
- Docker (compose 플러그인 필요)
- 호스트의 80·443·81번 포트가 사용 가능해야함
- 설치 직전 [Codeforces Health](https://codeforces.com/api/system.status)의 Status가 OK인지 확인하세요.
    - OK가 아니거나 반응이 없다면 정상으로 돌아올때 설치해주세요.

### 설치 방법

1. 이 저장소를 clone 하세요.

2. 저장소 루트에서 `umask 077`을 적용해 `redis_password.txt`를 만들고 Redis 비밀번호를 작성해주세요. 비밀번호는 비어 있지 않은 16진수 문자열이어야 합니다. 기존 파일은 `chmod 600 redis_password.txt`로 권한을 제한해주세요. 생성 명령은 [Docker Redis 설정](backend/README.md#docker-redis-설정)을 참고하세요.

3. 저장소 루트에서 아래 스크립트를 터미널에 입력해주세요.

```bash
docker compose up -d --build
```

4. 브라우저에서 [http://localhost](http://localhost) 로 접속하세요.

### 서버 중단 및 재개 방법

저장소 루트에서

```bash
docker compose down
```

로 서버를 중단할 수 있습니다.

```bash
docker compose up -d
```

로 중단한 서버를 재개할 수 있습니다.

### 삭제 방법

저장소 루트에서 다음 명령으로 서버를 중단하고 컨테이너·Compose 네트워크 및 관련 서비스 이미지를 삭제합니다.

```bash
docker compose down --rmi all
```

Proxy Manager 설정·인증서 볼륨과 로컬 파일은 유지됩니다.

---

## 참고하세요

[로봇이신가요?](/AGENTS.md)  
[프로젝트 구조](/ARCHITECTURE.md)  
[코드 규칙](/CONTRIBUTING.md)

---
* 로고 이미지 및 웹페이지 텍스쳐는 생성형 AI를 활용하여 제작되었습니다.
