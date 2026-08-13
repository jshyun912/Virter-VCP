export type Contest = {
	id: number;
	name: string;
	durationSeconds: number;
};

export type SearchResponse = {
	contestList: Contest[];
};

export type ApiErrorResponse = {
	message?: string;
};

export function parseHandles(value: string): string[] {
	return [...new Set(value.trim().split(/\s+/).filter(Boolean))];
}

export function mergeHandles(current: readonly string[], input: string): string[] {
	return [...new Set([...current, ...parseHandles(input)])];
}

export function getDifficultyMask(values: readonly number[]): number {
	return values.reduce((mask, value) => mask | value, 0);
}

export function buildSearchUrl(handles: readonly string[], difficultyMask: number): string {
	const query = new URLSearchParams();
	for (const handle of handles) {
		query.append('handle', handle);
	}
	query.set('dif', String(difficultyMask));
	return `/api/search?${query}`;
}

export function getSearchErrorMessage(errorCode?: string): string {
	switch (errorCode) {
		case 'CODEFORCES_USER_NOT_FOUND':
			return 'Codeforces 사용자를 찾을 수 없습니다.';
		case 'INVALID_SEARCH_REQUEST':
			return '검색 조건을 확인해 주세요.';
		case 'CODEFORCES_API_FAILED':
			return 'Codeforces 서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.';
		default:
			return '검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
	}
}

export function formatDuration(durationSeconds: number): string {
	const hours = Math.floor(durationSeconds / 3600);
	const minutes = Math.floor((durationSeconds % 3600) / 60);
	return minutes === 0 ? `${hours}시간` : `${hours}시간 ${minutes}분`;
}
