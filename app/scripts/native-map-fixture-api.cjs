// Local-only data for checking the real iOS SDK against the existing web board.
// Run with Node 24 from the repository root: node app/scripts/native-map-fixture-api.cjs
const http = require('node:http');
const { createApiData } = require('../../frontend/e2e/fixtures/mock-data.ts');

const port = Number(process.env.NATIVE_MAP_FIXTURE_PORT ?? 4310);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('NATIVE_MAP_FIXTURE_PORT must be a valid TCP port.');
}

http.createServer((request, response) => {
  const url = new URL(request.url ?? '/', `http://127.0.0.1:${port}`);
  if (request.method !== 'GET') {
    response.writeHead(405, { Allow: 'GET' });
    response.end();
    return;
  }

  const data = createApiData();
  // The board refreshes once per observation batch, so the forecast stays fresh while checking.
  const batch = Math.floor(Date.now() / 60_000) * 60_000;
  data.watch.observedAt = new Date(batch).toISOString();
  data.watch.staleAt = new Date(batch + 180_000).toISOString();
  for (const direction of data.watch.route.directions) {
    direction.firstDepartureTime = '00:00';
    direction.lastDepartureTime = '23:59';
  }

  const responses = {
    '/api/v1/routes': data.routes,
    [`/api/v1/routes/${data.watch.route.id}/watch`]: data.watch,
    [`/api/v2/routes/${data.watch.route.id}/geometry`]: data.geometry,
  };
  const body = responses[url.pathname];
  if (body === undefined) {
    response.writeHead(404, { 'Cache-Control': 'no-store' });
    response.end();
    return;
  }
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    Date: new Date().toUTCString(),
  });
  response.end(JSON.stringify(body));
}).listen(port, '127.0.0.1', () => {
  console.log(`Native map fixture API: http://127.0.0.1:${port}`);
  console.log('Board route: /routes/200000001 (synthetic stops and vehicles)');
});
