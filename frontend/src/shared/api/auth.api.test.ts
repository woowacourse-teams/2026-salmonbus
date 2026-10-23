import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import contract from "../../../../contract/auth-v1.openapi.json";
import { fetchSession, logout, memberRequest, updateNickname, withdraw } from "./auth.api";

const sessionExamples = contract.paths["/api/auth/session"].get.responses["200"].content["application/json"].examples;
const member = contract.paths["/api/me"].patch.responses["200"].content["application/json"].examples.profile.value;
const session = sessionExamples.authenticated.value;
const reauthRequired =
  contract.paths["/api/me"].delete.responses["409"].content["application/json"].examples.reauthRequired.value;
const appleWithdrawal =
  contract.paths["/api/me/withdrawal/apple"].post.responses["200"].content["application/json"].examples.startUrl.value;
const originalFetch = global.fetch;
const unauthenticated = () =>
  response(
    401,
    contract.paths["/api/me"].get.responses["401"].content["application/json"].examples.unauthenticated.value,
  );
function response(status: number, body?: unknown) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status });
}

beforeEach(() => {
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { cookie: "__Host-XSRF-TOKEN=csrf-test" },
  });
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: { location: { origin: "https://www.salmonbus.com" } },
  });
});
afterEach(() => {
  global.fetch = originalFetch;
  Reflect.deleteProperty(globalThis, "document");
  Reflect.deleteProperty(globalThis, "window");
});

describe("쿠키 인증 API", () => {
  it("갱신 가능한 세션이면 한 번 갱신하고 회원을 조회한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(200, sessionExamples.refreshable.value))
      .mockResolvedValueOnce(response(204))
      .mockResolvedValueOnce(response(200, session));
    expect(await fetchSession()).toMatchObject({ ok: true, body: session });
    expect(global.fetch).toHaveBeenNthCalledWith(
      2,
      "/api/auth/refresh",
      expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }) }),
    );
    expect(global.fetch).toHaveBeenCalledTimes(3);
  });
  it("갱신이 401이면 다시 갱신하지 않고 비회원이 된다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(200, sessionExamples.refreshable.value))
      .mockResolvedValueOnce(unauthenticated());
    expect(await fetchSession()).toMatchObject({ ok: true, body: sessionExamples.anonymous.value });
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });
  it("동시에 만료된 회원 요청은 갱신 하나를 공유하고 각 요청만 한 번 재시도한다", async () => {
    let refreshCount = 0;
    let memberCount = 0;
    let finish!: (value: Response) => void;
    const refresh = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    global.fetch = jest.fn<typeof fetch>(async (url) => {
      if (url === "/api/auth/refresh") {
        refreshCount += 1;
        return refresh;
      }
      memberCount += 1;
      return memberCount <= 2 ? unauthenticated() : response(200, member);
    });
    const requests = [memberRequest("/api/me"), memberRequest("/api/me")];
    await new Promise((resolve) => setImmediate(resolve));
    expect(refreshCount).toBe(1);
    finish(response(204));
    expect(await Promise.all(requests)).toEqual([
      expect.objectContaining({ ok: true }),
      expect.objectContaining({ ok: true }),
    ]);
    expect(memberCount).toBe(4);
  });
  it("닉네임을 trim하고 JSON·CSRF를 함께 보낸다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue(response(200, member));
    expect(await updateNickname(`  ${member.nickname}  `)).toMatchObject({ ok: true, body: member });
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/me",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ nickname: member.nickname }),
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test", "Content-Type": "application/json" }),
      }),
    );
  });
  it("204 로그아웃을 JSON 오류로 보지 않는다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue(response(204));
    expect(await logout()).toMatchObject({ ok: true, body: undefined, status: 204 });
  });
  it("로그아웃은 진행 중인 갱신이 끝난 뒤 최신 쿠키를 폐기한다", async () => {
    const order: string[] = [];
    let finish!: (value: Response) => void;
    const refresh = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    let memberCount = 0;
    global.fetch = jest.fn<typeof fetch>(async (url) => {
      order.push(String(url));
      if (url === "/api/auth/refresh") return refresh;
      if (url === "/api/auth/logout") return response(204);
      memberCount += 1;
      return unauthenticated();
    });
    const pending = memberRequest("/api/me");
    await new Promise((resolve) => setImmediate(resolve));
    const signOut = logout();
    expect(order).not.toContain("/api/auth/logout");
    finish(response(204));
    await Promise.all([pending, signOut]);
    expect(order.at(-1)).toBe("/api/auth/logout");
    expect(memberCount).toBe(1);
  });
  it("CSRF 쿠키가 없으면 세션에서 새로 발급받는다", async () => {
    document.cookie = "";
    global.fetch = jest.fn<typeof fetch>(async (url) => {
      if (url === "/api/auth/session") {
        document.cookie = "__Host-XSRF-TOKEN=new-csrf";
        return response(200, session);
      }
      return response(204);
    });
    expect(await logout()).toMatchObject({ ok: true });
    expect(global.fetch).toHaveBeenLastCalledWith(
      "/api/auth/logout",
      expect.objectContaining({ headers: expect.objectContaining({ "X-XSRF-TOKEN": "new-csrf" }) }),
    );
  });
  it("Apple 탈퇴는 서버가 발급한 같은 origin의 재인증 주소로만 진행한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(409, reauthRequired))
      .mockResolvedValueOnce(response(200, { startUrl: "https://evil.test/steal" }));
    expect(await withdraw()).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });
  it("Apple 탈퇴 시작도 액세스 토큰이 만료되면 갱신 후 같은 주소로 한 번 재시도한다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(409, reauthRequired))
      .mockResolvedValueOnce(unauthenticated())
      .mockResolvedValueOnce(response(204))
      .mockResolvedValueOnce(response(200, appleWithdrawal));

    expect(await withdraw()).toMatchObject({ ok: true, body: appleWithdrawal });
    expect(jest.mocked(global.fetch).mock.calls.map(([url]) => url)).toEqual([
      "/api/me",
      "/api/me/withdrawal/apple",
      "/api/auth/refresh",
      "/api/me/withdrawal/apple",
    ]);
    expect(global.fetch).toHaveBeenLastCalledWith(
      "/api/me/withdrawal/apple",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ returnTo: "/" }),
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test" }),
      }),
    );
  });
  it("Apple 탈퇴 시작의 갱신도 401이면 시작 요청을 반복하지 않는다", async () => {
    global.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(409, reauthRequired))
      .mockResolvedValueOnce(unauthenticated())
      .mockResolvedValueOnce(unauthenticated());

    expect(await withdraw()).toMatchObject({ ok: false, failure: { status: 401 } });
    expect(jest.mocked(global.fetch).mock.calls.map(([url]) => url)).toEqual([
      "/api/me",
      "/api/me/withdrawal/apple",
      "/api/auth/refresh",
    ]);
  });
  it("깨진 세션 응답을 비회원이나 회원으로 오인하지 않는다", async () => {
    global.fetch = jest.fn<typeof fetch>().mockResolvedValue(response(200, { authenticated: true }));
    expect(await fetchSession()).toMatchObject({ ok: false, failure: { kind: "malformed" } });
  });
});
