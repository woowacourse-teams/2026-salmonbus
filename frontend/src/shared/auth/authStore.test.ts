import { describe, expect, it, jest } from "@jest/globals";
import type { AuthSession, Member } from "@/shared/api/auth.types";
import type { ApiError, ApiResult } from "@/shared/api/client";
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
const TIMED_OUT: ApiError = { ok: false, failure: { kind: "timeout" } };
const UNAUTHENTICATED: ApiError = {
  ok: false,
  failure: {
    kind: "contract",
    error: { code: "UNAUTHENTICATED", message: "로그인이 필요합니다.", requestId: "test" },
    status: 401,
    retryAfterMs: null,
  },
};

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

  it.each<[keyof Member, Member]>([
    ["id", { ...MEMBER, id: "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a" }],
    ["nickname", RENAMED],
    ["provider", { ...MEMBER, provider: "APPLE" }],
  ])("회원 정보 중 %s 값이 다른 응답이 오면 새 객체를 돌려주고 구독자를 부른다", async (_field, changed) => {
    const services = createFakeServices();
    services.fetchSession.mockResolvedValueOnce(sessionFor(MEMBER)).mockResolvedValueOnce(sessionFor(changed));
    const store = createAuthStore(services);
    await store.reload();
    const before = store.authState();
    const listener = jest.fn();
    store.subscribe(listener);

    await store.reload();

    expect(store.authState()).not.toBe(before);
    expect(store.authState()).toEqual({ status: "authenticated", member: changed });
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

describe("변경 요청의 결과", () => {
  function createMemberStore() {
    const services = createFakeServices();
    services.fetchSession.mockResolvedValue(sessionFor(MEMBER));
    return { services, store: createAuthStore(services) };
  }

  it("탈퇴가 다시 인증할 주소를 돌려주면 회원 상태를 그대로 둔다", async () => {
    const reauth = succeeded({ startUrl: "/api/auth/apple/start?intent=withdraw&ticket=test" });
    const { services, store } = createMemberStore();
    services.withdraw.mockResolvedValue(reauth);
    await store.reload();
    const before = store.authState();

    const result = await store.withdraw();

    expect(result).toBe(reauth);
    expect(store.authState()).toBe(before);
  });

  it.each<[string, (store: AuthStore) => Promise<unknown>]>([
    ["닉네임 변경", (store) => store.changeNickname(RENAMED.nickname)],
    ["탈퇴", (store) => store.withdraw()],
  ])("%s 요청이 401로 실패하면 비회원으로 바꾼다", async (_name, mutate) => {
    const { services, store } = createMemberStore();
    services.updateNickname.mockResolvedValue(UNAUTHENTICATED);
    services.withdraw.mockResolvedValue(UNAUTHENTICATED);
    await store.reload();

    await mutate(store);

    expect(store.authState()).toEqual({ status: "anonymous" });
  });

  it.each<[string, (store: AuthStore) => Promise<unknown>]>([
    ["닉네임 변경", (store) => store.changeNickname(RENAMED.nickname)],
    ["탈퇴", (store) => store.withdraw()],
  ])("%s 요청이 401이 아닌 이유로 실패하면 회원 상태를 그대로 둔다", async (_name, mutate) => {
    const { services, store } = createMemberStore();
    services.updateNickname.mockResolvedValue(TIMED_OUT);
    services.withdraw.mockResolvedValue(TIMED_OUT);
    await store.reload();
    const before = store.authState();

    await mutate(store);

    expect(store.authState()).toBe(before);
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

describe("세션을 조회하는 시점", () => {
  function createPage(visibilityState: DocumentVisibilityState) {
    return Object.assign(new EventTarget(), { visibilityState });
  }

  function createStartedStore() {
    const services = createFakeServices();
    services.fetchSession.mockResolvedValue(sessionFor(MEMBER));
    const store = createAuthStore(services);
    const windowEvents = new EventTarget();
    const page = createPage("visible");
    const stop = store.init(windowEvents, page);
    return { services, store, windowEvents, page, stop };
  }

  it("시작할 때 한 번 조회한다", () => {
    const { services } = createStartedStore();

    expect(services.fetchSession).toHaveBeenCalledTimes(1);
  });

  it("창이 focus를 받으면 다시 조회한다", () => {
    const { services, windowEvents } = createStartedStore();

    windowEvents.dispatchEvent(new Event("focus"));

    expect(services.fetchSession).toHaveBeenCalledTimes(2);
  });

  it("탭이 보이는 상태로 바뀌면 다시 조회하고, 숨은 상태로 바뀌면 조회하지 않는다", () => {
    const { services, page } = createStartedStore();

    page.visibilityState = "hidden";
    page.dispatchEvent(new Event("visibilitychange"));
    expect(services.fetchSession).toHaveBeenCalledTimes(1);

    page.visibilityState = "visible";
    page.dispatchEvent(new Event("visibilitychange"));
    expect(services.fetchSession).toHaveBeenCalledTimes(2);
  });

  it("해제한 뒤에는 focus와 visibilitychange에 조회하지 않는다", () => {
    const { services, windowEvents, page, stop } = createStartedStore();

    stop();
    windowEvents.dispatchEvent(new Event("focus"));
    page.dispatchEvent(new Event("visibilitychange"));

    expect(services.fetchSession).toHaveBeenCalledTimes(1);
  });

  it("해제하면 응답을 기다리던 조회의 결과를 버린다", async () => {
    let answerSession: (result: ApiResult<AuthSession>) => void = () => {};
    const services = createFakeServices();
    services.fetchSession.mockReturnValue(new Promise((resolve) => (answerSession = resolve)));
    const store = createAuthStore(services);
    const stop = store.init(new EventTarget(), createPage("visible"));

    stop();
    answerSession(sessionFor(MEMBER));
    await new Promise((resolve) => setImmediate(resolve));

    expect(store.authState()).toEqual({ status: "loading" });
  });
});
