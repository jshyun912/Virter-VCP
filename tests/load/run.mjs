import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { cp, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { availableParallelism, totalmem, tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify, stripVTControlCharacters } from 'node:util';

const execute = promisify(execFile);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export function summarize(samples, elapsedMs) {
  assert.ok(samples.length > 0 && elapsedMs > 0, 'Measurements must be non-empty');
  const durations = samples.map(sample => sample.ms).sort((a, b) => a - b);
  const percentile = p => durations[Math.max(0, Math.ceil(durations.length * p) - 1)];
  return {
    requests: samples.length,
    statuses: samples.reduce((counts, sample) => {
      counts[sample.status] = (counts[sample.status] ?? 0) + 1;
      return counts;
    }, {}),
    requestsPerSecond: Number((samples.length * 1000 / elapsedMs).toFixed(2)),
    medianMs: Number(percentile(0.5).toFixed(2)),
    p95Ms: Number(percentile(0.95).toFixed(2)),
    maxMs: Number(durations.at(-1).toFixed(2))
  };
}

export function countRedisCommands(info) {
  return [...info.matchAll(/:calls=(\d+)/g)].reduce((sum, match) => sum + Number(match[1]), 0);
}

export function parseDockerStatsLine(line) {
  const clean = stripVTControlCharacters(line).trim();
  return clean ? JSON.parse(clean) : null;
}

export function isValidSearchResponse(status, body) {
  return status === 200
    ? Array.isArray(body?.contestList) && body.contestList.length === 3
      && body.contestList.every(c => c.id !== 1 && Number.isInteger(c.id))
    : status === 503 && body?.message === 'SEARCH_TEMPORARILY_UNAVAILABLE';
}

async function run() {
  const seconds = Number(process.env.LOAD_DURATION_SECONDS ?? 30);
  assert.ok(Number.isInteger(seconds) && seconds >= 1 && seconds <= 300,
    'LOAD_DURATION_SECONDS must be an integer from 1 to 300');
  const thinkMs = Number(process.env.LOAD_THINK_MS ?? 250);
  assert.ok(Number.isInteger(thinkMs) && thinkMs >= 0 && thinkMs <= 10000,
    'LOAD_THINK_MS must be an integer from 0 to 10000');
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const artifacts = await mkdtemp(join(tmpdir(), 'virter-load-'));
  const data = join(artifacts, 'divisions');
  const project = `virter-load-${randomUUID().slice(0, 8)}`;
  const args = ['compose', '-p', project, '-f', join(root, 'tests/load/compose.yaml')];
  const env = { ...process.env, LOAD_DATA_DIRECTORY: data };
  const report = {
    project, startedAt: new Date().toISOString(), durationSeconds: seconds, thinkMs,
    host: { logicalCpus: availableParallelism(), memoryGiB: totalmem() / 1024 ** 3 },
    environment: 'One backend, real Redis and front Nginx; isolated HTTP mock; generator shares host',
    passed: false, scenarios: []
  };
  const compose = async (...command) => {
    const result = await execute('docker', [...args, ...command], {
      env, timeout: 600000, maxBuffer: 16 * 1024 ** 2
    });
    return result.stdout.trim();
  };
  const saveReport = () => writeFile(join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
  const redis = (...command) => compose('exec', '-T', '-e', 'REDISCLI_AUTH=load-test-only',
    'redis', 'redis-cli', '--raw', ...command);
  async function waitFor(check, label, timeout = 60000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      try { if (await check()) return; } catch { /* Services can still be starting. */ }
      await sleep(250);
    }
    throw new Error(`Timed out waiting for ${label}`);
  }
  const resources = [];
  let statsProcess;
  let redisRunning = true;
  try {
    await mkdir(data);
    await cp(join(root, '.github/fixture/divisions'), data, { recursive: true });
    console.log(`Artifacts: ${artifacts}\nBuilding isolated project ${project}...`);
    await writeFile(join(artifacts, 'compose-up.log'), await compose('up', '-d', '--build'));
    const front = `http://${await compose('port', 'front', '80')}`;
    const mock = `http://${await compose('port', 'mock-codeforces', '8080')}`;
    report.frontUrl = front;
    const backendId = await compose('ps', '-q', 'backend');
    async function mockCall(path, body) {
      const response = await fetch(mock + path, body === undefined
        ? { signal: AbortSignal.timeout(5000) }
        : { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body), signal: AbortSignal.timeout(5000) });
      assert.equal(response.status, 200, `Mock control ${path}`);
      return response.json();
    }
    await waitFor(async () => (await fetch(front + '/api/health', {
      signal: AbortSignal.timeout(3000)
    })).status === 200, 'backend health');
    // A local mock plus the internal Docker network keeps load off the real API.
    await waitFor(async () => (await mockCall('/__stats')).contestListCalls > 0, 'mock routing');
    const ids = (await compose('ps', '-q', 'backend', 'redis')).split(/\s+/);
    statsProcess = spawn('docker', ['stats', '--format', '{{json .}}', ...ids], {
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let buffer = '';
    statsProcess.stdout.on('data', chunk => {
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (!line) continue;
        try {
          const row = parseDockerStatsLine(line);
          if (row) resources.push({ at: Date.now(), container: row.Name,
            cpuPercent: Number(row.CPUPerc.replace('%', '')), memoryUsage: row.MemUsage });
        } catch (error) {
          report.resourceSamplingError = error.message;
        }
      }
    });
    statsProcess.on('error', error => { report.resourceSamplingError = error.message; });
    async function search(handle) {
      const start = performance.now();
      try {
        const response = await fetch(`${front}/api/search?handle=${handle}&dif=63`, {
          signal: AbortSignal.timeout(70000)
        });
        let body;
        try { body = await response.json(); } catch { /* Preserve HTTP errors such as Nginx 504. */ }
        const valid = isValidSearchResponse(response.status, body);
        return { status: response.status, ms: performance.now() - start, valid };
      } catch (error) {
        return { status: 0, ms: performance.now() - start, valid: false, error: error.message };
      }
    }
    async function commandCount() {
      const info = await redis('INFO', 'commandstats');
      return countRedisCommands(info);
    }
    async function measure(name, concurrency, action, verify) {
      await waitFor(async () => (await mockCall('/__stats')).activeUserStatusCalls === 0, 'idle mock');
      await mockCall('/__reset', {});
      const before = redisRunning ? await commandCount() : null;
      const startedAt = Date.now();
      const samples = await action();
      const elapsedMs = Date.now() - startedAt;
      const calls = await mockCall('/__stats');
      const sockets = await compose('exec', '-T', 'front', 'cat', '/proc/net/tcp');
      const row = { name, concurrency, elapsedMs, ...summarize(samples, elapsedMs),
        codeforcesCalls: calls.userStatusCalls, maxConcurrentCodeforcesCalls: calls.maxActiveUserStatusCalls,
        callsByHandle: calls.callsByHandle,
        redisCommandDelta: redisRunning ? (await commandCount()) - before : null,
        invalidResponses: samples.filter(s => !s.valid).length,
        upstreamTimeWaitSockets: sockets.split('\n').filter(line => {
          const fields = line.trim().split(/\s+/);
          return fields[3] === '06' && fields[2]?.endsWith(':1F90');
        }).length,
        resources: resources.filter(sample => sample.at >= startedAt) };
      report.scenarios.push(row);
      await saveReport();
      console.log(JSON.stringify({ ...row, callsByHandle: undefined, resources: undefined }));
      assert.equal(report.resourceSamplingError, undefined, 'Docker resource sampling must work');
      assert.equal(row.invalidResponses, 0, `${name}: invalid response or network failure`);
      verify(row);
      return row;
    }
    const burst = (count, handle) => Promise.all(Array.from({ length: count }, (_, i) => search(handle(i))));
    const success = row => assert.deepEqual(row.statuses, { 200: row.requests });
    const levels = [1, 10, 30, 100];

    for (const concurrency of levels) {
      await mockCall('/__control', { delayMs: 1000 });
      const warm = `warm_${concurrency}`;
      assert.equal((await search(warm)).status, 200);
      await measure('cache-hit', concurrency, async () => {
        const samples = [];
        const deadline = Date.now() + seconds * 1000;
        await Promise.all(Array.from({ length: concurrency }, async () => {
          do {
            samples.push(await search(warm));
            if (thinkMs) await sleep(thinkMs);
          } while (Date.now() < deadline);
        }));
        return samples;
      }, row => { success(row); assert.equal(row.codeforcesCalls, 0); });

      await measure('same-handle-cold', concurrency,
        () => burst(concurrency, () => `cold_shared_${concurrency}`), row => {
          success(row);
          assert.equal(row.codeforcesCalls, 1);
          assert.equal(row.maxConcurrentCodeforcesCalls, 1);
        });
      await measure('different-handles-cold', concurrency,
        () => burst(concurrency, i => `cold_distinct_${concurrency}_${i}`), row => {
          success(row);
          assert.equal(row.codeforcesCalls, concurrency);
          assert.ok(Object.values(row.callsByHandle).every(count => count === 1));
        });
    }

    await compose('stop', '-t', '1', 'redis');
    redisRunning = false;
    await mockCall('/__control', { delayMs: 5000 });
    for (const concurrency of levels) {
      await measure('redis-outage', concurrency, async () => {
        const leader = search(`outage_leader_${concurrency}`);
        await waitFor(async () => (await mockCall('/__stats')).activeUserStatusCalls === 1,
          'fallback request', 15000);
        const followers = burst(concurrency - 1, i => `outage_follower_${concurrency}_${i}`);
        const [first, rest] = await Promise.all([leader, followers]);
        return [first, ...rest];
      }, row => {
        assert.deepEqual(row.statuses, concurrency === 1 ? { 200: 1 } : { 200: 1, 503: concurrency - 1 });
        assert.equal(row.codeforcesCalls, 1);
        assert.equal(row.maxConcurrentCodeforcesCalls, 1);
      });
    }
    const recoveryStartedAt = Date.now();
    await compose('start', 'redis');
    redisRunning = true;
    await mockCall('/__control', { delayMs: 1000 });
    await waitFor(async () => await redis('PING') === 'PONG', 'Redis restart');
    await waitFor(async () => {
      const response = await search('recovery_seed');
      return response.status === 200 && await redis('EXISTS', 'recovery_seed') === '1';
    }, 'backend reconnect and cache write');
    report.redisRecoveryMs = Date.now() - recoveryStartedAt;
    assert.equal(await compose('ps', '-q', 'backend'), backendId, 'Backend must reconnect without recreation');
    await measure('redis-recovered-cache-hit', 30, () => burst(30, () => 'recovery_seed'), row => {
      success(row);
      assert.equal(row.codeforcesCalls, 0);
    });
    await measure('redis-recovered-cold', 30, () => burst(30, () => 'recovery_cold'), row => {
      success(row);
      assert.equal(row.codeforcesCalls, 1);
    });
    report.passed = true;
  } catch (error) {
    report.error = error.stack;
    process.exitCode = 1;
    console.error(error.stack);
  } finally {
    // shortcut: forced process termination needs the documented project cleanup; add signal handling for unattended runs.
    statsProcess?.kill();
    try {
      if (!redisRunning) await compose('start', 'redis');
      await writeFile(join(artifacts, 'compose.log'), await compose('logs', '--no-color', '--tail', '300'));
    } catch (error) { report.logCollectionError = error.message; }
    try { await compose('down', '--volumes', '--remove-orphans'); }
    catch (error) { report.cleanupError = error.message; report.passed = false; process.exitCode = 1; }
    await rm(data, { recursive: true, force: true });
    await saveReport();
    await writeFile(join(artifacts, 'resources.json'), JSON.stringify(resources, null, 2));
    console.log(`Report: ${join(artifacts, 'report.json')}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await run();
