import assert from 'node:assert/strict';
import { once } from 'node:events';
import http from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { createMockServer } from './mock-codeforces.mjs';

test('mock serves synthetic contests and measures delayed user.status requests', { timeout: 5000 }, async (t) => {
  const server = createMockServer();
  t.after(() => {
    server.closeAllConnections();
    return new Promise((resolve) => server.close(resolve));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (path, body) => {
    const response = await fetch(`${base}${path}`, body === undefined ? undefined : {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json() };
  };
  const stats = async () => (await request('/__stats')).body;
  const waitForStats = async (predicate) => {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const current = await stats();
      if (predicate(current)) return current;
      await delay(5);
    }
    assert.fail('mock counters did not reach the expected state');
  };

  const contests = await request('/api/contest.list?gym=false');
  assert.equal(contests.status, 200);
  assert.equal(contests.body.status, 'OK');
  assert.ok(contests.body.result.length >= 7);
  for (const contest of contests.body.result) {
    assert.ok(Number.isInteger(contest.id) && contest.id !== 1);
    assert.equal(contest.phase, 'FINISHED');
    assert.equal(typeof contest.name, 'string');
    assert.equal(typeof contest.frozen, 'boolean');
    assert.ok(contest.durationSeconds > 0);
    assert.ok(contest.startTimeSeconds > 0);
  }

  assert.equal((await request('/__control', { delayMs: 200 })).status, 200);
  const started = performance.now();
  const pending = ['same', 'same', '__proto__'].map((handle) =>
    request(`/api/user.status?handle=${encodeURIComponent(handle)}`));
  const during = await waitForStats((value) => value.activeUserStatusCalls === 3);
  assert.deepEqual(during, {
    userStatusCalls: 3,
    activeUserStatusCalls: 3,
    maxActiveUserStatusCalls: 3,
    callsByHandle: { same: 2, ['__proto__']: 1 },
    contestListCalls: 1,
  });
  assert.equal((await request('/__reset', {})).status, 409);
  for (const response of await Promise.all(pending)) {
    assert.equal(response.status, 200);
    assert.deepEqual(response.body, { status: 'OK', result: [{ contestId: 1 }] });
  }
  assert.ok(performance.now() - started >= 190);
  assert.equal((await stats()).activeUserStatusCalls, 0);

  assert.equal((await request('/__control', { delayMs: 30, statusCode: 503 })).status, 200);
  assert.equal((await request('/__reset', {})).status, 200);
  assert.deepEqual(await stats(), {
    userStatusCalls: 0,
    activeUserStatusCalls: 0,
    maxActiveUserStatusCalls: 0,
    callsByHandle: {},
    contestListCalls: 0,
  });
  const failureStarted = performance.now();
  const failure = await request('/api/user.status?handle=failed');
  assert.ok(performance.now() - failureStarted >= 25);
  assert.equal(failure.status, 503);
  assert.equal(failure.body.status, 'FAILED');
  assert.equal(typeof failure.body.comment, 'string');

  for (const body of [null, [], {}, { delayMs: -1 }, { delayMs: 60001 }, { delayMs: '1' },
    { delayMs: 0.5 }, { delayMs: 0, statusCode: 302 }, { delayMs: 0, statusCode: 600 }]) {
    assert.equal((await request('/__control', body)).status, 400);
  }
  const malformed = await fetch(`${base}/__control`, { method: 'POST', body: '{' });
  assert.equal(malformed.status, 400);
  assert.equal((await malformed.json()).status, 'FAILED');
  assert.equal((await request('/unknown')).status, 404);

  assert.equal((await request('/__control', { delayMs: 200 })).status, 200);
  const disconnected = http.get(`${base}/api/user.status?handle=disconnected`);
  disconnected.on('error', () => {});
  await waitForStats((value) => value.activeUserStatusCalls === 1);
  disconnected.destroy();
  await waitForStats((value) => value.activeUserStatusCalls === 0);
  assert.equal((await request('/__reset', {})).status, 200);
});
