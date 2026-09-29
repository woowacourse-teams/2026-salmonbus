import examples from "../../../contract/examples/chat-v1.json" with { type: "json" };
import { test as apiTest, expect } from "./api";
import { pollIntervalMs, type createApiData } from "./mock-data";

export const chatAvailability = examples.availability;
export const clientFrames = examples.clientFrames;
export const serverFrames = examples.serverFrames;
export const chatRouteId = chatAvailability.routeId;
export const chatPaths = {
  board: `/api/v1/routes/${chatRouteId}/board`,
  vehicles: `/api/v1/routes/${chatRouteId}/vehicles`,
  availability: `/api/chat/rooms/${chatRouteId}`,
  stream: `/api/chat/rooms/${chatRouteId}/stream`,
};

export type ChatFrame = Record<string, unknown>;

export interface ChatSocket {
  readonly path: string;
  readonly received: ChatFrame[];
  send(frame: ChatFrame): void;
  close(code: number): Promise<void>;
}

interface ChatMock {
  enabled: boolean;
  readonly requests: string[];
  readonly sockets: ChatSocket[];
  readonly scripts: string[];
}

export function isChatChunk(url: string): boolean {
  return /features_chat_ChatWidget/.test(new URL(url).pathname);
}

function chatRouteResponse(data: ReturnType<typeof createApiData>, path: string) {
  if (path === chatPaths.board) return { ...data.board, route: { ...data.board.route, id: chatRouteId } };
  if (path === chatPaths.vehicles) return { ...data.vehicles, routeId: chatRouteId };
  return null;
}

export const test = apiTest.extend<{ chat: ChatMock }>({
  chat: [
    async ({ page, api }, use) => {
      const chat: ChatMock = { enabled: false, requests: [], sockets: [], scripts: [] };

      page.on("request", (request) => {
        if (request.resourceType() === "script") chat.scripts.push(request.url());
      });

      await page.route("**/api/**", async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        if (path.startsWith("/api/chat/")) chat.requests.push(`${request.method()} ${path}`);
        if (request.method() !== "GET") {
          await route.fallback();
          return;
        }

        if (path === chatPaths.availability) {
          await route.fulfill(
            chat.enabled
              ? { status: 200, headers: { "Cache-Control": "no-store" }, json: chatAvailability }
              : { status: 404, headers: { "Cache-Control": "no-store" } },
          );
          return;
        }

        const body = chatRouteResponse(api.data, path);
        if (body === null) {
          await route.fallback();
          return;
        }

        const now = await page.evaluate(() => Date.now());
        await route.fulfill({
          status: 200,
          headers: {
            Date: new Date(now).toUTCString(),
            Age: "0",
            "Cache-Control": `public, max-age=${pollIntervalMs / 1_000}`,
          },
          json: body,
        });
      });

      await page.routeWebSocket(
        (url) => url.pathname.startsWith("/api/"),
        (ws) => {
          const received: ChatFrame[] = [];
          ws.onMessage((message) => received.push(JSON.parse(message.toString()) as ChatFrame));
          chat.sockets.push({
            path: new URL(ws.url()).pathname,
            received,
            send: (frame) => ws.send(JSON.stringify(frame)),
            close: (code) => ws.close({ code }),
          });
        },
      );

      await use(chat);
    },
    { auto: true },
  ],
});

export { expect };
