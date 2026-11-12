import http from 'node:http';
import { pathToFileURL } from 'node:url';

const contests = [
  { id: 910001, name: 'Synthetic Codeforces Round 1 (Div. 2)' },
  { id: 910002, name: 'Synthetic Codeforces Round 2 (Div. 3)' },
  { id: 910003, name: 'Synthetic Codeforces Round 3 (Div. 4)' },
  { id: 910004, name: 'Synthetic Codeforces Round 4 (Div. 1)' },
  { id: 910005, name: 'Synthetic Codeforces Round 5 (Div. 1 + Div. 2)' },
  { id: 910006, name: 'Educational Codeforces Round 6 (Rated for Div. 2)' },
  { id: 910007, name: 'Synthetic Global Round 7' },
  { id: 910008, name: 'Synthetic Codeforces Round 8 (Div. 2)' },
].map((contest) => ({
  ...contest,
  type: 'CF',
  phase: 'FINISHED',
  frozen: false,
  durationSeconds: 7200,
  startTimeSeconds: 1700000000,
}));

function emptyStats() {
  return {
    userStatusCalls: 0,
    activeUserStatusCalls: 0,
    maxActiveUserStatusCalls: 0,
    callsByHandle: Object.create(null),
    contestListCalls: 0,
  };
}

export function createMockServer() {
  let stats = emptyStats();
  let control = { delayMs: 1000, statusCode: 200 };

  return http.createServer(async (request, response) => {
    const send = (statusCode, body) => {
      response.writeHead(statusCode, { 'content-type': 'application/json' });
      response.end(JSON.stringify(body));
    };
    const fail = (statusCode, comment) => send(statusCode, { status: 'FAILED', comment });
    const url = new URL(request.url, 'http://mock-codeforces');

    if (request.method === 'GET' && url.pathname === '/__stats') {
      send(200, stats);
      return;
    }
    if (request.method === 'POST' && url.pathname === '/__reset') {
      if (stats.activeUserStatusCalls !== 0) {
        fail(409, 'Cannot reset while user.status requests are active');
        return;
      }
      stats = emptyStats();
      send(200, { status: 'OK' });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/__control') {
      try {
        let body = '';
        for await (const chunk of request) {
          body += chunk;
          if (body.length > 4096) {
            fail(413, 'Control body is too large');
            return;
          }
        }
        const value = JSON.parse(body);
        if (!value || Array.isArray(value) || typeof value !== 'object'
          || !Number.isInteger(value.delayMs) || value.delayMs < 0 || value.delayMs > 60000
          || (value.statusCode !== undefined && value.statusCode !== 200
            && (!Number.isInteger(value.statusCode) || value.statusCode < 400 || value.statusCode > 599))
          || Object.keys(value).some((key) => key !== 'delayMs' && key !== 'statusCode')) {
          fail(400, 'Expected delayMs from 0 to 60000 and optional statusCode of 200 or 400 to 599');
          return;
        }
        control = { delayMs: value.delayMs, statusCode: value.statusCode ?? 200 };
        send(200, { status: 'OK', ...control });
      } catch {
        if (!response.destroyed) fail(400, 'Expected a JSON control object');
      }
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/contest.list') {
      stats.contestListCalls += 1;
      send(200, { status: 'OK', result: contests });
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/user.status') {
      const handle = url.searchParams.get('handle');
      if (!handle?.trim()) {
        fail(400, 'handle is required');
        return;
      }
      stats.userStatusCalls += 1;
      stats.callsByHandle[handle] = (stats.callsByHandle[handle] ?? 0) + 1;
      stats.activeUserStatusCalls += 1;
      stats.maxActiveUserStatusCalls = Math.max(stats.maxActiveUserStatusCalls, stats.activeUserStatusCalls);

      const { delayMs, statusCode } = control;
      const timer = setTimeout(() => {
        if (statusCode >= 400) fail(statusCode, 'Synthetic Codeforces failure');
        else send(200, { status: 'OK', result: [{ contestId: 1 }] });
      }, delayMs);
      let complete = false;
      const finish = () => {
        if (complete) return;
        complete = true;
        clearTimeout(timer);
        stats.activeUserStatusCalls -= 1;
      };
      response.once('finish', finish);
      response.once('close', finish);
      return;
    }
    fail(404, 'Unknown mock endpoint');
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT ?? 8080);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be from 1 to 65535');
  createMockServer().listen(port, '0.0.0.0', () => {
    console.log(`Mock Codeforces listening on ${port}`);
  });
}
