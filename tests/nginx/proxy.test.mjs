import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { isIP } from 'node:net';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';

const exec = promisify(execFile);
const nginxConfig = fileURLToPath(new URL('../../front/nginx.conf', import.meta.url));
const echoServer = fileURLToPath(new URL('./echo-server.mjs', import.meta.url));

async function docker(...args) {
	const { stdout } = await exec('docker', args, { timeout: 120_000 });
	return stdout.trim();
}

function request(port, agent) {
	return new Promise((resolve, reject) => {
		const outgoing = http.get({
			hostname: '127.0.0.1', port, agent,
			path: '/api/search?handle=tourist&handle=Benq&dif=63',
			headers: { Host: 'virter.test', 'X-Forwarded-For': '198.51.100.27' }
		}, (response) => {
			let body = '';
			response.setEncoding('utf8');
			response.on('data', (chunk) => { body += chunk; });
			response.on('error', reject);
			response.on('end', () => {
				try {
					assert.equal(response.statusCode, 200, body);
					resolve(JSON.parse(body));
				} catch (error) {
					reject(error);
				}
			});
		});
		outgoing.setTimeout(2_000, () => outgoing.destroy(new Error('Proxy request timed out')));
		outgoing.on('error', reject);
	});
}

async function eventually(check, timeoutMs) {
	const deadline = Date.now() + timeoutMs;
	let lastError;
	do {
		try {
			return await check();
		} catch (error) {
			lastError = error;
		}
		await delay(250);
	} while (Date.now() < deadline);
	throw lastError;
}

test('Nginx forwards API requests over reusable connections and follows backend replacement', {
	timeout: 180_000
}, async (t) => {
	const prefix = `virter-nginx-test-${randomUUID()}`;
	const network = `${prefix}-network`;
	const frontend = `${prefix}-front`;
	const backend = `${prefix}-backend`;
	const reservation = `${prefix}-old-ip`;
	const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
	const containers = [];
	let networkCreated = false;
	const backendIp = () => docker('inspect', '--format', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', backend);
	const startBackend = async (instance) => {
		containers.push(backend);
		return docker('run', '--detach', '--name', backend, '--network', network,
			'--network-alias', 'backend', '--env', `INSTANCE=${instance}`,
			'--mount', `type=bind,src=${echoServer},dst=/echo-server.mjs,readonly`,
			'node:24-alpine', 'node', '/echo-server.mjs');
	};

	try {
		await docker('network', 'create', network);
		networkCreated = true;
		await startBackend('original');
		containers.push(frontend);
		const frontendId = await docker('run', '--detach', '--name', frontend,
			'--network', network, '--publish', '127.0.0.1::80',
			'--mount', `type=bind,src=${nginxConfig},dst=/etc/nginx/conf.d/default.conf,readonly`,
			'nginx:stable-alpine');
		await docker('exec', frontend, 'nginx', '-t');
		t.diagnostic(await docker('exec', frontend, 'sh', '-c', 'nginx -v 2>&1'));
		const binding = JSON.parse(await docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', frontend));
		const port = Number(binding['80/tcp'][0].HostPort);
		await eventually(() => request(port, agent), 15_000);

		await t.test('preserves API path, repeated query, headers and upstream keepalive', async () => {
			const ports = new Set();
			for (let i = 0; i < 20; i++) {
				const reply = await request(port, agent);
				assert.equal(reply.instance, 'original');
				assert.equal(reply.url, '/search?handle=tourist&handle=Benq&dif=63');
				assert.equal(reply.headers.host, 'virter.test');
				const forwardedFor = reply.headers['x-forwarded-for'].split(', ');
				assert.equal(forwardedFor.length, 2);
				assert.equal(forwardedFor[0], '198.51.100.27');
				assert.ok(isIP(forwardedFor[1]), `Invalid appended client IP: ${forwardedFor[1]}`);
				assert.equal(reply.httpVersion, '1.1');
				ports.add(reply.remotePort);
			}
			t.diagnostic(`20 sequential requests used ${ports.size} backend TCP ports`);
			assert.ok(ports.size <= 2, `Expected reused upstream connections; 20 requests used ${ports.size} backend TCP ports`);
		});

		await t.test('resolves a recreated backend at a different IP without restarting Nginx', async () => {
			const oldIp = await backendIp();
			await docker('rm', '--force', backend);
			containers.push(reservation);
			await docker('run', '--detach', '--name', reservation, '--network', network,
				'--ip', oldIp, 'node:24-alpine', 'node', '-e', 'setInterval(() => {}, 60000)');
			await startBackend('replacement');
			const newIp = await backendIp();
			assert.notEqual(newIp, oldIp, 'Backend replacement must change the address');
			const started = Date.now();
			await eventually(async () => {
				assert.equal((await request(port, agent)).instance, 'replacement');
			}, 45_000);
			assert.equal(await docker('inspect', '--format', '{{.Id}}', frontend), frontendId);
			t.diagnostic(`Backend changed ${oldIp} -> ${newIp}; Nginx routed to replacement in ${Date.now() - started}ms`);
		});
	} finally {
		agent.destroy();
		await Promise.allSettled([...new Set(containers)].map((container) => docker('rm', '--force', container)));
		if (networkCreated) await docker('network', 'rm', network);
	}
});
