# Virter

<img src="/data/logo/virter-logo.png" style="width: 400px; height: auto;" alt="virter_logo">

여러 사람들과 Virtual Contest를 하고 싶은데 무슨 set을 돌지 고민이신가요? Virter가 골라드립니다!

---
## 사용 방법

현재는 배포되고 있지 않습니다.

저장소를 내려받은 다음 Docker가 설치되어 있는지 확인 후 저장소 위치에서

```bash
docker build -t backend:latest ./backend
docker build -t front:latest ./front
docker compose up -d
```

다음 명령을 수행 후  브라우저에서 [http://localhost](http://localhost) 로 접속하세요.

### 주의 사항
대회 정보를 담고 있는 JSON 파일이 필요합니다. JSON 파일에 대해서는 [다음 문서](ARCHITECTURE.md#div-데이터-초기화)를 참고하세요.

간단히 동작 여부만 알고 싶으면 [샘플 데이터](/.github/fixture/divisions/)가 존재하니 date 디렉토리에 데이터 디렉토리를 복사해서 사용하시면 됩니다.

---

## 참고하세요

[로봇이신가요?](/AGENTS.md)  
[프로젝트 구조](/ARCHITECTURE.md)  
[코드 규칙](/CONTRIBUTING.md)

---
* 로고 이미지 및 웹페이지 텍스쳐는 생성형 AI를 활용하여 제작되었습니다.
