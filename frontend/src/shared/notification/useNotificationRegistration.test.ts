import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import type { ApiResult } from "../api/client";
import type { NotificationRegistration } from "./notification.types";
import {
  allowDeviceRegistration,
  registerDeviceIfGranted,
  registerThisDevice,
  unregisterThisDevice,
} from "./useNotificationRegistration";

const TOKEN = "a1b2c3d4".repeat(8);
const OTHER_TOKEN = "e5f6a7b8".repeat(8);

const noContent: ApiResult<void> = {
  ok: true,
  body: undefined,
  status: 204,
  requestId: null,
  clock: null,
  lifetime: { noStore: true, maxAgeSeconds: null, ageSeconds: 0 },
};
const timedOut: ApiResult<void> = { ok: false, failure: { kind: "timeout" } };

const granted = (token: string): NotificationRegistration => ({
  requestId: "request-1",
  status: "granted",
  token,
  environment: "PRODUCTION",
});

function servicesFor(registration: NotificationRegistration | null = null) {
  return {
    register: jest.fn<(token: string, environment: "SANDBOX" | "PRODUCTION") => Promise<ApiResult<void>>>(),
    unregister: jest.fn<(token: string) => Promise<ApiResult<void>>>().mockResolvedValue(noContent),
    readRegistration: jest.fn<() => Promise<NotificationRegistration | null>>().mockResolvedValue(registration),
  };
}

const registrationClosed: ApiResult<void> = { ok: false, failure: { kind: "aborted" } };

beforeEach(() => {
  allowDeviceRegistration();
});

afterEach(async () => {
  jest.useRealTimers();
  await unregisterThisDevice({ unregister: async () => noContent, readRegistration: async () => null });
});

describe("로그인 때 기기 등록", () => {
  it("권한 창 없이 읽은 token이 허용 상태면 등록하고, 로그아웃 때 그 token을 지운다", async () => {
    const services = servicesFor(granted(TOKEN));
    services.register.mockResolvedValue(noContent);

    await registerDeviceIfGranted(services.readRegistration, services.register);
    services.readRegistration.mockResolvedValue(granted(OTHER_TOKEN));
    await unregisterThisDevice(services);

    expect(services.register.mock.calls).toEqual([[TOKEN, "PRODUCTION"]]);
    expect(services.unregister.mock.calls).toEqual([[TOKEN]]);
  });

  it.each<[string, NotificationRegistration | null]>([
    ["앱 밖이거나 답이 없음", null],
    ["권한을 아직 고르지 않음", { requestId: "request-1", status: "undetermined", token: null, environment: null }],
    ["권한 거절", { requestId: "request-1", status: "denied", token: null, environment: null }],
    ["허용했지만 token 없음", { requestId: "request-1", status: "granted", token: null, environment: null }],
  ])("%s이면 등록하지 않는다", async (_name, registration) => {
    const services = servicesFor(registration);
    await registerDeviceIfGranted(services.readRegistration, services.register);
    expect(services.register).not.toHaveBeenCalled();
  });
});

describe("로그아웃 전 기기 삭제", () => {
  it("등록에 성공한 token을 지우고, 앱에 다시 묻지 않는다", async () => {
    const services = servicesFor(granted(OTHER_TOKEN));
    services.register.mockResolvedValue(noContent);
    await registerThisDevice(TOKEN, "SANDBOX", services.register);

    await unregisterThisDevice(services);

    expect(services.register).toHaveBeenCalledWith(TOKEN, "SANDBOX");
    expect(services.unregister.mock.calls).toEqual([[TOKEN]]);
    expect(services.readRegistration).not.toHaveBeenCalled();
  });

  it("등록에 실패한 token은 기억하지 않아 앱이 지금 주는 token을 지운다", async () => {
    const services = servicesFor(granted(OTHER_TOKEN));
    services.register.mockResolvedValue(timedOut);
    await registerThisDevice(TOKEN, "SANDBOX", services.register);

    await unregisterThisDevice(services);

    expect(services.unregister.mock.calls).toEqual([[OTHER_TOKEN]]);
  });

  it.each<[string, NotificationRegistration | null]>([
    ["앱 밖이거나 답이 없음", null],
    ["권한 거절", { requestId: "request-1", status: "denied", token: null, environment: null }],
    ["허용했지만 token 없음", { requestId: "request-1", status: "granted", token: null, environment: null }],
  ])("기억한 token이 없고 %s이면 아무것도 지우지 않는다", async (_name, registration) => {
    const services = servicesFor(registration);
    await unregisterThisDevice(services);
    expect(services.unregister).not.toHaveBeenCalled();
  });

  it("한 번 지운 token은 다음 로그아웃에서 다시 지우지 않는다", async () => {
    const services = servicesFor();
    services.register.mockResolvedValue(noContent);
    await registerThisDevice(TOKEN, "SANDBOX", services.register);

    await unregisterThisDevice(services);
    await unregisterThisDevice(services);

    expect(services.unregister.mock.calls).toEqual([[TOKEN]]);
    expect(services.readRegistration).toHaveBeenCalledTimes(1);
  });

  it("삭제가 끝나지 않아도 시간 제한이 지나면 로그아웃을 이어 간다", async () => {
    jest.useFakeTimers();
    const services = servicesFor(granted(TOKEN));
    services.unregister.mockReturnValue(new Promise(() => {}));
    let finished = false;
    const removal = unregisterThisDevice({ ...services, timeoutMs: 3_000 }).then(() => {
      finished = true;
    });

    await jest.advanceTimersByTimeAsync(2_999);
    expect(finished).toBe(false);
    await jest.advanceTimersByTimeAsync(1);
    await removal;
    expect(finished).toBe(true);
    expect(services.unregister).toHaveBeenCalledWith(TOKEN);
  });

  it("등록 요청이 아직 서버에 가는 중이면 끝나길 기다린 뒤 그 token을 지운다", async () => {
    const services = servicesFor(granted(OTHER_TOKEN));
    let answerRegistration: (result: ApiResult<void>) => void = () => {};
    services.register.mockReturnValue(new Promise((resolve) => (answerRegistration = resolve)));
    const registration = registerDeviceIfGranted(async () => granted(TOKEN), services.register);
    await Promise.resolve();

    const removal = unregisterThisDevice(services);
    await Promise.resolve();
    expect(services.unregister).not.toHaveBeenCalled();

    answerRegistration(noContent);
    await Promise.all([registration, removal]);
    expect(services.unregister.mock.calls).toEqual([[TOKEN]]);
    expect(services.readRegistration).not.toHaveBeenCalled();
  });

  it("등록 요청이 시간 제한 안에 끝나지 않으면 기다리지 않고 앱이 주는 token을 지운다", async () => {
    jest.useFakeTimers();
    const services = servicesFor(granted(OTHER_TOKEN));
    let answerRegistration: (result: ApiResult<void>) => void = () => {};
    services.register.mockReturnValue(new Promise((resolve) => (answerRegistration = resolve)));
    const registration = registerThisDevice(TOKEN, "SANDBOX", services.register);

    const removal = unregisterThisDevice({ ...services, timeoutMs: 3_000 });
    await jest.advanceTimersByTimeAsync(2_999);
    expect(services.unregister).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(1);
    await removal;
    expect(services.unregister.mock.calls).toEqual([[OTHER_TOKEN]]);

    answerRegistration(timedOut);
    await registration;
  });

  it("앱 읽기나 삭제가 예외를 던져도 로그아웃을 막지 않는다", async () => {
    const services = servicesFor();
    services.readRegistration.mockRejectedValue(new Error("bridge"));
    await expect(unregisterThisDevice(services)).resolves.toBeUndefined();
  });

  it("로그아웃이 시작된 뒤에는 서버에 다시 등록하지 않는다", async () => {
    const services = servicesFor(granted(TOKEN));
    services.register.mockResolvedValue(noContent);
    await unregisterThisDevice(services);

    await expect(registerThisDevice(TOKEN, "SANDBOX", services.register)).resolves.toEqual(registrationClosed);
    await registerDeviceIfGranted(services.readRegistration, services.register);

    expect(services.register).not.toHaveBeenCalled();
  });

  it("해제가 끝나기 전에 시작한 등록은 서버로 가지 않는다", async () => {
    const services = servicesFor(granted(OTHER_TOKEN));
    let answerRegistration: (result: ApiResult<void>) => void = () => {};
    services.register.mockReturnValue(new Promise((resolve) => (answerRegistration = resolve)));
    const registration = registerThisDevice(TOKEN, "SANDBOX", services.register);
    const removal = unregisterThisDevice(services);
    await Promise.resolve();

    await expect(registerThisDevice(OTHER_TOKEN, "PRODUCTION", services.register)).resolves.toEqual(registrationClosed);
    expect(services.register.mock.calls).toEqual([[TOKEN, "SANDBOX"]]);

    answerRegistration(noContent);
    await Promise.all([registration, removal]);
    expect(services.unregister.mock.calls).toEqual([[TOKEN]]);
  });

  it("기기 정보를 읽는 중에 로그아웃이 시작되면 그 뒤에는 서버에 등록하지 않는다", async () => {
    let answerRead: (registration: NotificationRegistration | null) => void = () => {};
    const readRegistration = jest.fn<() => Promise<NotificationRegistration | null>>(
      () => new Promise((resolve) => (answerRead = resolve)),
    );
    const register = jest
      .fn<(token: string, environment: "SANDBOX" | "PRODUCTION") => Promise<ApiResult<void>>>()
      .mockResolvedValue(noContent);
    const pending = registerDeviceIfGranted(readRegistration, register);
    await Promise.resolve();

    const removal = unregisterThisDevice({ unregister: async () => noContent, readRegistration: async () => null });
    answerRead(granted(TOKEN));
    await Promise.all([pending, removal]);

    expect(register).not.toHaveBeenCalled();
  });

  it("해제 시간 제한 뒤에 성공한 등록은 token을 다시 기억하지 않는다", async () => {
    jest.useFakeTimers();
    const services = servicesFor(granted(OTHER_TOKEN));
    let answerRegistration: (result: ApiResult<void>) => void = () => {};
    services.register.mockReturnValue(new Promise((resolve) => (answerRegistration = resolve)));
    const registration = registerThisDevice(TOKEN, "SANDBOX", services.register);

    const removal = unregisterThisDevice({ ...services, timeoutMs: 3_000 });
    await jest.advanceTimersByTimeAsync(3_000);
    await removal;
    expect(services.unregister.mock.calls).toEqual([[OTHER_TOKEN]]);

    answerRegistration(noContent);
    await expect(registration).resolves.toEqual(noContent);

    services.readRegistration.mockResolvedValue(null);
    await unregisterThisDevice(services);
    expect(services.unregister).toHaveBeenCalledTimes(1);
  });

  it("다시 로그인하면 기기 등록을 막지 않는다", async () => {
    const services = servicesFor(granted(TOKEN));
    services.register.mockResolvedValue(noContent);
    await unregisterThisDevice(services);

    allowDeviceRegistration();
    await registerDeviceIfGranted(services.readRegistration, services.register);

    expect(services.register.mock.calls).toEqual([[TOKEN, "PRODUCTION"]]);
  });
});
