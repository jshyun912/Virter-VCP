import http from 'node:http';

const server = http.createServer((request, response) => {
	response.setHeader('Content-Type', 'application/json');
	response.end(JSON.stringify({
		instance: process.env.INSTANCE,
		url: request.url,
		headers: request.headers,
		httpVersion: request.httpVersion,
		remotePort: request.socket.remotePort
	}));
});

server.keepAliveTimeout = 60_000;
server.listen(8080, '0.0.0.0');
