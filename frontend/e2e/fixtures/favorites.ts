import { test as authTest, expect, testMember } from "./auth";
import type {
  FavoriteGroup,
  FavoriteEta,
  FavoriteStatus,
  FavoriteTarget,
  SavedFavorite,
} from "../../src/shared/api/favorites.types";

export const defaultGroupId = "10000000-0000-4000-8000-000000000001";
const uuid = (value: number) => `10000000-0000-4000-8000-${String(value).padStart(12, "0")}`;
interface FavoritesMock {
  groups: FavoriteGroup[];
  statuses: Record<string, FavoriteStatus>;
  etaByVehicleId: Record<string, FavoriteEta>;
  unavailable: boolean;
  mutationError: string | null;
  orderFailureGroupId: string | null;
  requests: { path: string; query: string; method: string; body: Record<string, unknown> | null }[];
}

export const test = authTest.extend<{ favorites: FavoritesMock }>({
  favorites: [
    async ({ auth, api }, use) => {
      auth.member = { ...testMember };
      const favorites: FavoritesMock = {
        groups: [{ id: defaultGroupId, name: "즐겨찾기", favorites: [] }],
        statuses: {},
        etaByVehicleId: {},
        unavailable: false,
        mutationError: null,
        orderFailureGroupId: null,
        requests: [],
      };
      let sequence = 10;
      await api.route(async (route) => {
        const req = route.request();
        const url = new URL(req.url());
        const path = url.pathname;
        if (!path.startsWith("/api/me/favorite")) {
          await route.fallback();
          return;
        }
        const method = req.method();
        const body = req.postData() ? (req.postDataJSON() as Record<string, unknown>) : null;
        favorites.requests.push({ path, query: url.search, method, body });
        const error = (status: number, code: string) =>
          route.fulfill({
            status,
            json: { code, message: code, requestId: "e2e-favorites" },
            headers: { "Cache-Control": "no-store" },
          });
        const json = (value: unknown, status = 200) =>
          route.fulfill({ status, json: value, headers: { "Cache-Control": "no-store" } });
        const done = () => route.fulfill({ status: 204 });
        if (!auth.member) {
          await error(401, "UNAUTHENTICATED");
          return;
        }
        if (favorites.unavailable) {
          await error(404, "ENDPOINT_NOT_FOUND");
          return;
        }
        if (method !== "GET" && req.headers()["x-xsrf-token"] !== "e2e-csrf") {
          await error(400, "CSRF_REJECTED");
          return;
        }
        if (method !== "GET" && favorites.mutationError) {
          await error(409, favorites.mutationError);
          return;
        }
        const groupId = path.split("/")[4];
        const group = favorites.groups.find((candidate) => candidate.id === groupId);
        const equal = (left: FavoriteTarget, right: FavoriteTarget) =>
          left.routeId === right.routeId && left.stopId === right.stopId && left.direction === right.direction;
        if (path === "/api/me/favorite-groups" && method === "GET") {
          if (favorites.groups.length === 0)
            favorites.groups.push({ id: uuid(sequence++), name: "즐겨찾기", favorites: [] });
          await json({ groups: favorites.groups });
        } else if (path === "/api/me/favorites" && method === "GET") {
          const selected = url.searchParams.get("groupId");
          const groups =
            selected === null ? favorites.groups : favorites.groups.filter((candidate) => candidate.id === selected);
          if (selected && groups.length === 0) {
            await error(404, "FAVORITE_GROUP_NOT_FOUND");
            return;
          }
          await json({
            groups: groups.map((candidate) => ({
              ...candidate,
              favorites: candidate.favorites.map((favorite) => {
                const stop = api.data.watch.stops.find(
                  (stop) => stop.stopId === favorite.stopId && stop.direction === favorite.direction,
                );
                const status = favorites.statuses[favorite.id] ?? "READY";
                const hasBoard = status === "READY" || status === "NO_VEHICLE";
                return {
                  id: favorite.id,
                  status,
                  route: hasBoard
                    ? {
                        id: favorite.routeId,
                        displayName: api.data.watch.route.displayName,
                        terminalStopName: api.data.watch.route.directions.find(
                          (direction) => direction.id === favorite.direction,
                        )!.terminalStopName,
                      }
                    : { id: favorite.routeId },
                  stop: hasBoard
                    ? { stopId: favorite.stopId, name: stop?.name ?? "저장한 정류장", direction: favorite.direction }
                    : { stopId: favorite.stopId },
                  approachingVehicles:
                    status === "READY"
                      ? [0, 1, 2].map((index) => ({
                          vehicleId: String(index),
                          remainingStops: 3 + index * 4,
                          forecast: {
                            status: "AVAILABLE",
                            seatAvailableProbability: 0.82 - index * 0.3,
                            expectedSeats: 7.4 - index * 3,
                          },
                          eta:
                            favorites.etaByVehicleId[String(index)] ??
                            (index === 2
                              ? { status: "UNAVAILABLE", reason: "SOURCE_NOT_PROVIDED" }
                              : {
                                  status: "AVAILABLE",
                                  expectedArrivalAt: "2026-09-16T08:07:00+09:00",
                                  remainingSeconds: 420 + index * 60,
                                }),
                        }))
                      : [],
                };
              }),
            })),
          });
        } else if (path === "/api/me/favorite-groups" && method === "POST") {
          if (favorites.groups.length >= 3) {
            await error(409, "FAVORITE_GROUP_LIMIT_EXCEEDED");
            return;
          }
          const name = String(body?.name).trim();
          if (favorites.groups.some((candidate) => candidate.name === name)) {
            await error(409, "FAVORITE_GROUP_NAME_CONFLICT");
            return;
          }
          const created = { id: uuid(sequence++), name, favorites: [] };
          favorites.groups.push(created);
          await json(created, 201);
        } else if (path === "/api/me/favorite-selections" && method === "PUT") {
          const target = body as unknown as FavoriteTarget;
          const ids = body?.favoriteGroupIds as string[];
          if (ids.some((id) => !favorites.groups.some((candidate) => candidate.id === id))) {
            await error(404, "FAVORITE_GROUP_NOT_FOUND");
            return;
          }
          if (
            favorites.groups.some(
              (candidate) =>
                ids.includes(candidate.id) &&
                candidate.favorites.length >= 2 &&
                !candidate.favorites.some((favorite) => equal(favorite, target)),
            )
          ) {
            await error(409, "FAVORITE_LIMIT_EXCEEDED");
            return;
          }
          for (const candidate of favorites.groups) {
            const exists = candidate.favorites.some((favorite) => equal(favorite, target));
            if (ids.includes(candidate.id) && !exists)
              candidate.favorites.push({
                routeId: target.routeId,
                stopId: target.stopId,
                direction: target.direction,
                id: uuid(sequence++),
              });
            else if (!ids.includes(candidate.id))
              candidate.favorites = candidate.favorites.filter((favorite) => !equal(favorite, target));
          }
          await done();
        } else if (path === "/api/me/favorite-groups/order" && method === "PUT") {
          const ids = body?.groupIds as string[];
          if (
            new Set(ids).size !== favorites.groups.length ||
            ids.length !== favorites.groups.length ||
            ids.some((id) => !favorites.groups.some((candidate) => candidate.id === id))
          ) {
            await error(400, "INVALID_FAVORITE_GROUP_ORDER");
            return;
          }
          favorites.groups = ids.map((id) => favorites.groups.find((candidate) => candidate.id === id)!);
          await done();
        } else if (!group) {
          await error(404, "FAVORITE_GROUP_NOT_FOUND");
        } else if (method === "PATCH") {
          const name = String(body?.name).trim();
          if (favorites.groups.some((candidate) => candidate.id !== group.id && candidate.name === name)) {
            await error(409, "FAVORITE_GROUP_NAME_CONFLICT");
            return;
          }
          group.name = name;
          await json({ id: group.id, name });
        } else if (method === "DELETE" && path.split("/").length === 5) {
          if (favorites.groups.length === 1) {
            await error(409, "LAST_FAVORITE_GROUP");
            return;
          }
          favorites.groups = favorites.groups.filter((candidate) => candidate.id !== group.id);
          await done();
        } else if (method === "DELETE") {
          const favoriteId = path.split("/")[6];
          group.favorites = group.favorites.filter((favorite) => favorite.id !== favoriteId);
          await done();
        } else if (method === "PUT" && path.endsWith("/favorites/order")) {
          if (favorites.orderFailureGroupId === group.id) {
            await error(503, "SERVICE_UNAVAILABLE");
            return;
          }
          const ids = body?.favoriteIds as string[];
          if (
            new Set(ids).size !== group.favorites.length ||
            ids.length !== group.favorites.length ||
            ids.some((id) => !group.favorites.some((favorite) => favorite.id === id))
          ) {
            await error(400, "INVALID_FAVORITE_ORDER");
            return;
          }
          group.favorites = ids.map((id) => group.favorites.find((favorite) => favorite.id === id)!);
          await done();
        } else {
          await error(400, "INVALID_REQUEST");
        }
      });
      await use(favorites);
    },
    { auto: true },
  ],
});

export { expect };
export function storedFavorite(target: FavoriteTarget, value = 2): SavedFavorite {
  return { ...target, id: uuid(value) };
}
