import assert from 'node:assert/strict';
import { test } from 'node:test';
import { countRedisCommands, isValidSearchResponse, parseDockerStatsLine, summarize } from './run.mjs';

test('부하 검증 데이터의 검색 성공 응답은 대회가 정확히 세 개 있어야 한다', () => {
  for (const contestList of [[], [{ id: 910001 }], [{ id: 910001 }, { id: 910002 }]]) {
    assert.equal(isValidSearchResponse(200, { contestList }), false,
      `${contestList.length}개 대회를 정상 결과로 인정하면 안 된다`);
  }
  assert.equal(isValidSearchResponse(200, {
    contestList: [{ id: 910001 }, { id: 910002 }, { id: 910003 }]
  }), true);
  assert.equal(isValidSearchResponse(200, {
    contestList: [{ id: 910001 }, { id: 910002 }, { id: 910003 }, { id: 910004 }]
  }), false);
});

test('참여 이력 대회와 잘못된 ID는 거절하고 제한된 조회 실패 응답만 인정한다', () => {
  for (const id of [1, '910001', 1.5]) {
    assert.equal(isValidSearchResponse(200, {
      contestList: [{ id }, { id: 910002 }, { id: 910003 }]
    }), false);
  }
  assert.equal(isValidSearchResponse(200, {}), false);
  assert.equal(isValidSearchResponse(200), false);
  assert.equal(isValidSearchResponse(503, { message: 'SEARCH_TEMPORARILY_UNAVAILABLE' }), true);
  assert.equal(isValidSearchResponse(503, { message: 'UNKNOWN_ERROR' }), false);
  assert.equal(isValidSearchResponse(502, { message: 'SEARCH_TEMPORARILY_UNAVAILABLE' }), false);
});

test('상태별 건수와 지연 분포 및 처리량을 실제 측정값으로 계산한다', () => {
  const result = summarize([
    { status: 200, ms: 10 }, { status: 503, ms: 40 },
    { status: 200, ms: 20 }, { status: 504, ms: 30 }
  ], 2000);
  assert.deepEqual(result.statuses, { 200: 2, 503: 1, 504: 1 });
  assert.equal(result.requests, 4);
  assert.equal(result.requestsPerSecond, 2);
  assert.equal(result.medianMs, 20);
  assert.equal(result.p95Ms, 40);
  assert.equal(result.maxMs, 40);
});

test('측정값이 없으면 결과를 정상인 것처럼 보고하지 않는다', () => {
  assert.throws(() => summarize([], 1000));
  assert.throws(() => summarize([{ status: 200, ms: 1 }], 0));
});

test('Redis 명령 횟수에 포함된 실패·거절 카운터를 다시 더하지 않는다', () => {
  const info = 'cmdstat_evalsha:calls=10,usec=500,rejected_calls=3,failed_calls=2\r\n'
    + 'cmdstat_get:calls=7,usec=100,rejected_calls=0,failed_calls=0\r\n';
  assert.equal(countRedisCommands(info), 17);
});

test('Docker 통계 출력의 터미널 제어문자는 제거하고 JSON만 읽는다', () => {
  assert.deepEqual(parseDockerStatsLine('\u001b[H{"CPUPerc":"0.34%"}\u001b[K'), { CPUPerc: '0.34%' });
  assert.equal(parseDockerStatsLine('\u001b[2J\u001b[H'), null);
});
