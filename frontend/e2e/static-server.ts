import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer } from "node:http";
import path from "node:path";
import { parseArgs } from "node:util";

const distDir = path.resolve(import.meta.dirname, "../dist");
const indexHtml = path.join(distDir, "index.html");

const contentTypes: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".ico": "image/x-icon",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".webp": "image/webp",
  ".woff2": "font/woff2",
};

// 운영 CloudFront처럼 dist에 없는 경로는 모두 index.html로 응답한다.
async function fileToServe(pathname: string) {
  const filePath = path.join(distDir, pathname);
  if (!filePath.startsWith(distDir + path.sep)) return indexHtml;
  const stats = await stat(filePath).catch(() => null);
  return stats?.isFile() ? filePath : indexHtml;
}

const { values } = parseArgs({ options: { port: { type: "string" } } });
await stat(indexHtml).catch(() => {
  throw new Error(indexHtml + "이 없습니다. pnpm build로 먼저 빌드해야 합니다.");
});

createServer(async (request, response) => {
  const { pathname } = new URL(request.url ?? "/", "http://127.0.0.1");
  // mock을 빠져나간 API 요청이 index.html을 받으면 원인 대신 JSON 파싱 에러가 보인다.
  if (pathname === "/api" || pathname.startsWith("/api/")) {
    response.writeHead(404).end();
    return;
  }

  const filePath = await fileToServe(pathname);
  response.writeHead(200, { "Content-Type": contentTypes[path.extname(filePath)] ?? "application/octet-stream" });
  if (request.method === "HEAD") {
    response.end();
    return;
  }
  createReadStream(filePath).pipe(response);
}).listen(Number(values.port), "127.0.0.1");
