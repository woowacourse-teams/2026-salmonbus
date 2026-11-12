// Local-only data for checking the real iOS SDK against the existing web board.
// Run with Node 24 from the repository root: node app/scripts/native-map-fixture-api.cjs
const http = require('node:http');
const fs = require('node:fs');
const { createApiData } = require('../../frontend/e2e/fixtures/mock-data.ts');
const authContract = require('../../contract/auth-v1.openapi.json');
const session = authContract.paths['/api/auth/session'].get.responses['200']
  .content['application/json'].examples.authenticated.value;

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

  if (url.pathname === '/api/native-map-verification') {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(fs.readFileSync(require.resolve('../verification/native-map-fixture.html')));
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
  const tile = /^\/api\/v2\/stations\/tiles\/14\/(\d+)\/(\d+)$/.exec(url.pathname);
  const route = data.watch.route;
  const stop = data.watch.stops.find(({ boardingAllowed }) => boardingAllowed);
  if (!stop) throw new Error('검증 노선에는 승차 가능한 정류장이 필요합니다.');
  const homeStation = {
    stationId: stop.stopId,
    name: '검증 정류장',
    point: { x: 127.111136, y: 37.394761 },
  };
  const target = { routeId: route.id, stopId: stop.stopId, direction: stop.direction };
  const group = { id: '10000000-0000-4000-8000-000000000001', name: '즐겨찾기' };
  const favoriteId = '10000000-0000-4000-8000-000000000002';

  const responses = {
    '/api/auth/session': session,
    '/api/me/favorite-groups': { groups: [{ ...group, favorites: [{ ...target, id: favoriteId }] }] },
    '/api/me/favorites': {
      groups: [{
        ...group,
        favorites: [{
          id: favoriteId,
          status: 'NO_VEHICLE',
          route: { id: route.id, displayName: route.displayName, terminalStopName: route.endStopName },
          stop: { stopId: stop.stopId, name: homeStation.name, direction: stop.direction },
          approachingVehicles: [],
        }],
      }],
    },
    '/api/v1/routes': data.routes,
    [`/api/v1/routes/${data.watch.route.id}/watch`]: data.watch,
    [`/api/v2/routes/${data.watch.route.id}/geometry`]: data.geometry,
    [`/api/v2/stations/${homeStation.stationId}`]: {
      stationId: homeStation.stationId,
      name: homeStation.name,
      point: homeStation.point,
      coordinateSystem: 'WGS84',
      regionName: null,
      direction: null,
      notice: null,
      generatedAt: data.watch.observedAt,
      status: 'NO_PREDICTION',
      reason: 'NO_APPROACHING_VEHICLE',
      snapshot: null,
      refresh: { status: 'IDLE', reason: null, suggestedRefreshAt: data.watch.staleAt },
      routes: [{
        routeId: route.id,
        name: route.displayName,
        destinationName: route.endStopName,
        routeTypeCode: 11,
        supported: true,
        service: {
          routeStatus: route.status,
          referenceVersionId: route.referenceVersionId,
          stopSequence: stop.sequence,
          matchStatus: 'MATCHED',
          direction: stop.direction,
          schedule: null,
          scheduleWindow: 'UNKNOWN',
        },
        status: 'NO_PREDICTION',
        reason: 'NO_APPROACHING_VEHICLE',
        arrivals: [],
      }],
    },
  };
  const body = tile ? {
    tile: { zoom: 14, x: Number(tile[1]), y: Number(tile[2]) },
    coordinateSystem: 'WGS84',
    status: 'READY',
    stations: tile[1] === '13976' && tile[2] === '6354' ? [homeStation] : [],
  } : responses[url.pathname];
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
