import { describe, expect, it, jest } from "@jest/globals";
import type { AuthSession, Member } from "../api/auth.types";
import type { ApiResult } from "../api/client";
import { createAuthStore, type AuthServices, type AuthState } from "./authStore";

const MEMBER: Member = { id: "3f2b8c1e-5a47-4d09-9e6b-0c1d2e3f4a5b", nickname: "살몽", provider: "KAKAO" };
const RENAMED: Member = { ...MEMBER, nickname: "새살몽" };

function succeeded<T>(body: T, status = 200): ApiResult<T> {
  return {
    ok: true,
    body,
    status,
    requestId: null,
    clock: null,
    lifetime: { noStore: true, maxAgeSeconds: null, ageSeconds: 0 },
  };
}

function sessionFor(member: Member): ApiResult<AuthSession> {
  return succeeded({ authenticated: true, refreshable: false, member });
}

const NO_CONTENT = succeeded<void>(undefined, 204);
const TIMED_OUT: ApiResult<void> = { ok: false, failure: { kind: "timeout" } };

function createFakeServices() {
  return {
    fetchSession: jest.fn<AuthServices["fetchSession"]>(),
    updateNickname: jest.fn<AuthServices["updateNickname"]>(),
    logout: jest.fn<AuthServices["logout"]>(),
    withdraw: jest.fn<AuthServices["withdraw"]>(),
    unregisterThisDevice: jest.fn<AuthServices["unregisterThisDevice"]>().mockResolvedValue(undefined),
    allowDeviceRegistration: jest.fn<AuthServices["allowDeviceRegistration"]>(),
    registerDeviceIfGranted: jest.fn<AuthServices["registerDeviceIfGranted"]>().mockResolvedValue(undefined),
  };
}

type AuthStore = ReturnType<typeof createAuthStore>;

const MUTATIONS: [string, (store: AuthStore) => Promise<unknown>, AuthState][] = [
  ["닉네임 변경", (store) => store.changeNickname(RENAMED.nickname), { status: "authenticated", member: RENAMED }],
  ["로그아웃", (store) => store.signOut(), { status: "anonymous" }],
  ["탈퇴", (store) => store.withdraw(), { status: "anonymous" }],
];

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

describe("변경 요청과 세션 조회", () => {
  it.each(MUTATIONS)(
    "%s 요청보다 먼저 보낸 세션 조회의 응답이 늦게 와도 상태를 바꾸지 않는다",
    async (_name, mutate, expected) => {
      let answerSession: (result: ApiResult<AuthSession>) => void = () => {};
      const services = createFakeServices();
      services.fetchSession
        .mockResolvedValueOnce(sessionFor(MEMBER))
        .mockReturnValueOnce(new Promise((resolve) => (answerSession = resolve)));
      services.updateNickname.mockResolvedValue(succeeded(RENAMED));
      services.logout.mockResolvedValue(NO_CONTENT);
      services.withdraw.mockResolvedValue(NO_CONTENT);
      const store = createAuthStore(services);
      await store.reload();
      const earlier = store.reload();

      await mutate(store);
      answerSession(sessionFor(MEMBER));
      await earlier;

      expect(store.authState()).toEqual(expected);
    },
  );

  it.each(MUTATIONS)("%s 요청이 진행 중이면 reload가 세션을 조회하지 않는다", async (_name, mutate) => {
    const unanswered = new Promise<never>(() => {});
    const services = createFakeServices();
    services.updateNickname.mockReturnValue(unanswered);
    services.logout.mockReturnValue(unanswered);
    services.withdraw.mockReturnValue(unanswered);
    const store = createAuthStore(services);

    void mutate(store);
    await store.reload();

    expect(services.fetchSession).not.toHaveBeenCalled();
  });
});

describe("로그아웃", () => {
  it("기기 등록 해제가 끝난 뒤에 로그아웃 요청을 보낸다", async () => {
    let finishUnregister: () => void = () => {};
    const services = createFakeServices();
    services.unregisterThisDevice.mockReturnValue(new Promise((resolve) => (finishUnregister = resolve)));
    services.logout.mockResolvedValue(NO_CONTENT);
    const store = createAuthStore(services);

    const signingOut = store.signOut();
    await Promise.resolve();
    expect(services.logout).not.toHaveBeenCalled();

    finishUnregister();
    await signingOut;
    expect(services.logout).toHaveBeenCalledTimes(1);
    expect(store.authState()).toEqual({ status: "anonymous" });
    expect(services.allowDeviceRegistration).not.toHaveBeenCalled();
  });

  it("로그아웃 요청이 실패하면 회원 상태를 그대로 두고 기기 등록을 다시 연다", async () => {
    const services = createFakeServices();
    services.fetchSession.mockResolvedValue(sessionFor(MEMBER));
    services.logout.mockResolvedValue(TIMED_OUT);
    const store = createAuthStore(services);
    await store.reload();
    const before = store.authState();

    const result = await store.signOut();

    expect(result).toBe(TIMED_OUT);
    expect(store.authState()).toBe(before);
    expect(services.allowDeviceRegistration).toHaveBeenCalledTimes(1);
    expect(services.registerDeviceIfGranted).toHaveBeenCalledTimes(1);
  });
});

describe("겹친 변경 요청", () => {
  it("먼저 시작한 요청의 응답은 상태를 바꾸지 않고, 호출한 쪽은 결과를 받는다", async () => {
    let answerNickname: (result: ApiResult<Member>) => void = () => {};
    const services = createFakeServices();
    services.fetchSession.mockResolvedValue(sessionFor(MEMBER));
    services.updateNickname.mockReturnValue(new Promise((resolve) => (answerNickname = resolve)));
    services.logout.mockResolvedValue(NO_CONTENT);
    const store = createAuthStore(services);
    await store.reload();

    const renaming = store.changeNickname(RENAMED.nickname);
    await store.signOut();
    answerNickname(succeeded(RENAMED));
    const result = await renaming;

    expect(result).toMatchObject({ ok: true, body: RENAMED });
    expect(store.authState()).toEqual({ status: "anonymous" });
  });

  it("둘 중 하나만 끝났으면 reload가 세션을 조회하지 않는다", async () => {
    const services = createFakeServices();
    services.updateNickname.mockReturnValue(new Promise<never>(() => {}));
    services.logout.mockResolvedValue(NO_CONTENT);
    const store = createAuthStore(services);

    void store.changeNickname(RENAMED.nickname);
    await store.signOut();
    await store.reload();

    expect(services.fetchSession).not.toHaveBeenCalled();
  });
});
