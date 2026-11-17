import { describe, expect, it, jest } from "@jest/globals";
import type { AuthSession, Member } from "../api/auth.types";
import type { ApiResult } from "../api/client";
import { createAuthStore, type AuthServices } from "./authStore";

const MEMBER: Member = { id: "3f2b8c1e-5a47-4d09-9e6b-0c1d2e3f4a5b", nickname: "살몽", provider: "KAKAO" };
const RENAMED: Member = { ...MEMBER, nickname: "새살몽" };

function sessionFor(member: Member): ApiResult<AuthSession> {
  return {
    ok: true,
    body: { authenticated: true, refreshable: false, member },
    status: 200,
    requestId: null,
    clock: null,
    lifetime: { noStore: true, maxAgeSeconds: null, ageSeconds: 0 },
  };
}

function createFakeServices() {
  return { fetchSession: jest.fn<AuthServices["fetchSession"]>() };
}

describe("세션 조회", () => {
  it("회원이라고 답하면 로그인 상태가 되고 구독자를 한 번 부른다", async () => {
    const services = createFakeServices();
    services.fetchSession.mockResolvedValue(sessionFor(MEMBER));
    const store = createAuthStore(services);
    const listener = jest.fn();
    store.subscribe(listener);

    await store.reload();

    expect(store.authState()).toEqual({ status: "authenticated", member: MEMBER });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("회원 정보가 같은 응답이 다시 오면 이전과 같은 객체를 돌려주고 구독자를 부르지 않는다", async () => {
    const services = createFakeServices();
    services.fetchSession
      .mockResolvedValueOnce(sessionFor({ ...MEMBER }))
      .mockResolvedValueOnce(sessionFor({ ...MEMBER }));
    const store = createAuthStore(services);
    await store.reload();
    const before = store.authState();
    const listener = jest.fn();
    store.subscribe(listener);

    await store.reload();

    expect(store.authState()).toBe(before);
    expect(listener).not.toHaveBeenCalled();
  });

  it("닉네임이 다른 응답이 오면 새 객체를 돌려주고 구독자를 부른다", async () => {
    const services = createFakeServices();
    services.fetchSession.mockResolvedValueOnce(sessionFor(MEMBER)).mockResolvedValueOnce(sessionFor(RENAMED));
    const store = createAuthStore(services);
    await store.reload();
    const before = store.authState();
    const listener = jest.fn();
    store.subscribe(listener);

    await store.reload();

    expect(store.authState()).not.toBe(before);
    expect(store.authState()).toEqual({ status: "authenticated", member: RENAMED });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("조회 두 개가 겹치면 나중에 보낸 조회의 응답만 반영한다", async () => {
    let answerFirst: (result: ApiResult<AuthSession>) => void = () => {};
    const services = createFakeServices();
    services.fetchSession
      .mockReturnValueOnce(new Promise((resolve) => (answerFirst = resolve)))
      .mockResolvedValueOnce(sessionFor(RENAMED));
    const store = createAuthStore(services);

    const first = store.reload();
    await store.reload();
    answerFirst(sessionFor(MEMBER));
    await first;

    expect(store.authState()).toEqual({ status: "authenticated", member: RENAMED });
  });
});
