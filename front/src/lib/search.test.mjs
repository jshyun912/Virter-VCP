import assert from 'node:assert/strict';
import test from 'node:test';

import {
	buildSearchUrl,
	formatDuration,
	getDifficultyMask,
	getSearchErrorMessage,
	mergeHandles,
	parseHandles
} from './search.ts';

test('핸들을 모든 공백으로 나누고 입력 순서대로 중복을 제거한다', () => {
	assert.deepEqual(parseHandles('  tourist\nBenq\t tourist  Petr '), ['tourist', 'Benq', 'Petr']);
});

test('새 입력을 기존 핸들 뒤에 합치고 중복과 빈 값을 제거한다', () => {
	assert.deepEqual(mergeHandles(['tourist', 'Benq'], ' Benq\nPetr  tourist new_handle '), [
		'tourist',
		'Benq',
		'Petr',
		'new_handle'
	]);
});

test('선택된 Div 값으로 비트마스크를 만든다', () => {
	assert.equal(getDifficultyMask([1, 2, 4, 8, 16, 32]), 63);
	assert.equal(getDifficultyMask([1, 8, 32]), 41);
	assert.equal(getDifficultyMask([]), 0);
});

test('핸들을 반복 쿼리 파라미터로 검색 URL에 넣는다', () => {
	assert.equal(
		buildSearchUrl(['tourist', 'Benq'], 63),
		'/api/search?handle=tourist&handle=Benq&dif=63'
	);
});

test('백엔드 오류 코드를 사용자용 메시지로 변환한다', () => {
	assert.equal(getSearchErrorMessage('CODEFORCES_USER_NOT_FOUND'), 'Codeforces 사용자를 찾을 수 없습니다.');
	assert.equal(getSearchErrorMessage('INVALID_SEARCH_REQUEST'), '검색 조건을 확인해 주세요.');
	assert.equal(
		getSearchErrorMessage('CODEFORCES_API_FAILED'),
		'Codeforces 서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.'
	);
	assert.equal(getSearchErrorMessage(undefined), '검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.');
});

test('대회 시간을 시간과 분으로 표시한다', () => {
	assert.equal(formatDuration(7200), '2시간');
	assert.equal(formatDuration(9000), '2시간 30분');
});
