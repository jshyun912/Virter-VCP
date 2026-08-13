# Frontend 작업 지침

- Svelte 5 runes 문법과 기존 SvelteKit 구조를 사용합니다.
- 검색 도메인 로직은 `$lib/search.ts`에 두고 Node 내장 테스트 러너로 검증합니다.
- UI 라이브러리나 상태 관리 의존성은 추가하지 않습니다.
- 작업 완료 전에 `npm test`, `npm run check`, `npm run build`를 실행합니다.
