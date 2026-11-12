import { describe, expect, it, jest } from "@jest/globals";
import type { ApiResult } from "@/shared/api/client";
import type { NotificationSchedule, NotificationSetting } from "@/shared/api/notifications.types";
import type { NotificationRegistration } from "@/shared/notification/notification.types";
import {
  nextNotificationRowState,
  removeNotification,
  settingChangeOf,
  submitNotification,
  type NotificationRowEvent,
  type NotificationRowState,
  type NotificationSaveServices,
} from "./notificationRowState";

const target = { routeId: "200000001", stopId: "200000011", direction: "UP" } as const;
const schedule: NotificationSchedule = { time: "08:10", weekdays: ["MON", "SAT"] };
const setting: NotificationSetting = {
  id: "20000000-0000-4000-8000-000000000001",
  ...target,
  time: "07:40",
  weekdays: ["MON", "TUE", "WED", "THU", "FRI"],
};
const saved: NotificationSetting = { ...setting, ...schedule };

function success<T>(body: T, status = 200): ApiResult<T> {
  return {
    ok: true,
    body,
    status,
    requestId: null,
    clock: null,
    lifetime: { noStore: true, maxAgeSeconds: null, ageSeconds: 0 },
  };
}
function contractFailure(
  code: "NOTIFICATION_TARGET_NOT_FAVORITE" | "INVALID_PUSH_DEVICE" | "NOTIFICATION_SETTING_NOT_FOUND",
  status: number,
) {
  return {
    ok: false as const,
    failure: {
      kind: "contract" as const,
      error: { code, message: code, requestId: "request-1" },
      status,
      retryAfterMs: null,
    },
  };
}

function servicesFor(registration: NotificationRegistration | null) {
  return {
    requestRegistration: jest.fn<NotificationSaveServices["requestRegistration"]>().mockResolvedValue(registration),
    registerPushDevice: jest
      .fn<NotificationSaveServices["registerPushDevice"]>()
      .mockResolvedValue(success(undefined, 204)),
    saveNotificationSetting: jest
      .fn<NotificationSaveServices["saveNotificationSetting"]>()
      .mockResolvedValue(success(saved)),
  };
}

async function outcomeOf(
  start: NotificationRowState,
  run: (apply: (event: NotificationRowEvent) => void) => Promise<void>,
) {
  let state = start;
  const events: NotificationRowEvent[] = [];
  await run((event) => {
    events.push(event);
    state = nextNotificationRowState(state, event);
  });
  return { state, events };
}

const idle: NotificationRowState = { status: "idle" };
const editingNew = nextNotificationRowState(idle, { type: "open", setting: null });
const editingExisting = nextNotificationRowState(idle, { type: "open", setting });

describe("알림 줄 상태", () => {
  it("설정이 없는 줄을 열면 평일 07:30으로, 설정이 있는 줄을 열면 저장된 값으로 창을 연다", () => {
    expect(editingNew).toEqual({
      status: "editing",
      schedule: { time: "07:30", weekdays: ["MON", "TUE", "WED", "THU", "FRI"] },
    });
    expect(editingExisting).toEqual({ status: "editing", schedule: setting });
  });

  it("창을 취소하면 진행 중인 일이 없는 상태로 돌아간다", () => {
    expect(nextNotificationRowState(editingNew, { type: "cancel" })).toEqual(idle);
    expect(nextNotificationRowState(editingExisting, { type: "cancel" })).toEqual(idle);
  });

  it("거절 안내 중에도 스위치를 다시 켜면 창을 연다", () => {
    expect(nextNotificationRowState({ status: "denied" }, { type: "open", setting: null })).toMatchObject({
      status: "editing",
    });
  });

  it("끄기에 실패한 줄을 열면 저장된 값으로 창을 연다", () => {
    expect(nextNotificationRowState({ status: "removeFailed", message: "실패" }, { type: "open", setting })).toEqual({
      status: "editing",
      schedule: setting,
    });
  });

  it("저장 중에는 열기, 취소, 끄기를 받지 않는다", () => {
    const saving: NotificationRowState = { status: "saving", schedule };
    for (const event of [{ type: "open", setting }, { type: "cancel" }, { type: "remove" }] as const)
      expect(nextNotificationRowState(saving, event)).toBe(saving);
  });

  it("저장에 실패하면 창에 고른 값과 문구가 남고, 취소하면 창을 닫는다", () => {
    const failed = nextNotificationRowState({ status: "saving", schedule }, { type: "failed", message: "실패" });
    expect(failed).toEqual({ status: "failed", schedule, message: "실패" });
    expect(nextNotificationRowState(failed, { type: "cancel" })).toEqual(idle);
  });
});

describe("알림 저장", () => {
  it("새 알림은 권한을 묻고 받은 토큰으로 기기를 등록한 뒤 설정을 저장하고, 저장된 설정을 알린다", async () => {
    const services = servicesFor({
      requestId: "request-1",
      status: "granted",
      token: "device-token",
      environment: "SANDBOX",
    });
    const order: string[] = [];
    services.registerPushDevice.mockImplementation(async () => {
      order.push("device");
      return success(undefined, 204);
    });
    services.saveNotificationSetting.mockImplementation(async () => {
      order.push("setting");
      return success(saved);
    });
    const { state, events } = await outcomeOf(editingNew, (apply) =>
      submitNotification(target, null, schedule, services, apply),
    );
    expect(state).toEqual(idle);
    expect(events.at(-1)).toEqual({ type: "saved", setting: saved });
    expect(services.registerPushDevice).toHaveBeenCalledWith("device-token", "SANDBOX");
    expect(services.saveNotificationSetting).toHaveBeenCalledWith(target, schedule);
    expect(order).toEqual(["device", "setting"]);
  });

  it("허락했지만 토큰이 없으면 기기 등록 없이 설정만 저장한다", async () => {
    const services = servicesFor({ requestId: "request-1", status: "granted", token: null, environment: null });
    const { state, events } = await outcomeOf(editingNew, (apply) =>
      submitNotification(target, null, schedule, services, apply),
    );
    expect(state).toEqual(idle);
    expect(events.at(-1)).toEqual({ type: "saved", setting: saved });
    expect(services.registerPushDevice).not.toHaveBeenCalled();
  });

  it.each<[NotificationRegistration["status"] | null, NotificationRowState]>([
    ["denied", { status: "denied" }],
    ["undetermined", idle],
    [null, idle],
  ])("권한 답이 %s이면 어떤 API도 부르지 않고 저장했다고 알리지 않는다", async (status, expected) => {
    const services = servicesFor(
      status === null ? null : { requestId: "request-1", status, token: "device-token", environment: "PRODUCTION" },
    );
    const { state, events } = await outcomeOf(editingNew, (apply) =>
      submitNotification(target, null, schedule, services, apply),
    );
    expect(state).toEqual(expected);
    expect(events.map((event) => event.type)).toEqual(["submit", "answered"]);
    expect(services.registerPushDevice).not.toHaveBeenCalled();
    expect(services.saveNotificationSetting).not.toHaveBeenCalled();
  });

  it("기기 등록에 실패하면 설정을 저장하지 않고 창에 문구를 남긴다", async () => {
    const services = servicesFor({
      requestId: "request-1",
      status: "granted",
      token: "device-token",
      environment: "PRODUCTION",
    });
    services.registerPushDevice.mockResolvedValue(contractFailure("INVALID_PUSH_DEVICE", 400));
    const { state } = await outcomeOf(editingNew, (apply) =>
      submitNotification(target, null, schedule, services, apply),
    );
    expect(state).toEqual({
      status: "failed",
      schedule,
      message: "이 기기를 알림 받을 기기로 등록하지 못했어요. 잠시 후 다시 시도해 주세요.",
    });
    expect(services.saveNotificationSetting).not.toHaveBeenCalled();
  });

  it("설정이 있는 알림을 고치면 권한을 다시 묻지 않고 설정만 저장한다", async () => {
    const services = servicesFor({ requestId: "request-1", status: "denied", token: null, environment: null });
    const { state, events } = await outcomeOf(editingExisting, (apply) =>
      submitNotification(target, setting, schedule, services, apply),
    );
    expect(state).toEqual(idle);
    expect(events.at(-1)).toEqual({ type: "saved", setting: saved });
    expect(services.requestRegistration).not.toHaveBeenCalled();
    expect(services.registerPushDevice).not.toHaveBeenCalled();
  });

  it("즐겨찾기가 아닌 대상이라는 답이면 창에 고정 문구를 보이고 저장했다고 알리지 않는다", async () => {
    const services = servicesFor({ requestId: "request-1", status: "granted", token: null, environment: null });
    services.saveNotificationSetting.mockResolvedValue(contractFailure("NOTIFICATION_TARGET_NOT_FAVORITE", 422));
    const { state, events } = await outcomeOf(editingExisting, (apply) =>
      submitNotification(target, setting, schedule, services, apply),
    );
    expect(state).toEqual({
      status: "failed",
      schedule,
      message: "즐겨찾기에 저장된 정류장만 알림을 받을 수 있어요. 목록을 다시 확인해 주세요.",
    });
    expect(events.some((event) => event.type === "saved")).toBe(false);
  });
});

describe("알림 끄기", () => {
  it("지우면 지웠다고 알린다", async () => {
    const remove = jest.fn<(id: string) => Promise<ApiResult<void>>>().mockResolvedValue(success(undefined));
    const { state, events } = await outcomeOf(idle, (apply) => removeNotification(setting, remove, apply));
    expect(state).toEqual(idle);
    expect(events).toEqual([{ type: "remove" }, { type: "removed", settingId: setting.id }]);
    expect(remove).toHaveBeenCalledWith(setting.id);
  });

  it("서버에 이미 없는 설정이면 지운 것으로 본다", async () => {
    const remove = jest
      .fn<(id: string) => Promise<ApiResult<void>>>()
      .mockResolvedValue(contractFailure("NOTIFICATION_SETTING_NOT_FOUND", 404));
    const { state, events } = await outcomeOf(idle, (apply) => removeNotification(setting, remove, apply));
    expect(state).toEqual(idle);
    expect(events.at(-1)).toEqual({ type: "removed", settingId: setting.id });
  });

  it("다른 이유로 못 지우면 지웠다고 알리지 않고 문구를 남긴다", async () => {
    const remove = jest.fn<(id: string) => Promise<ApiResult<void>>>().mockResolvedValue({
      ok: false,
      failure: { kind: "timeout" },
    });
    const { state, events } = await outcomeOf(idle, (apply) => removeNotification(setting, remove, apply));
    expect(state).toEqual({
      status: "removeFailed",
      message: "요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.",
    });
    expect(events.some((event) => event.type === "removed")).toBe(false);
  });
});

describe("목록에 알릴 변경", () => {
  it("저장이 끝나면 저장된 설정을, 삭제가 끝나면 지운 설정의 id를 알린다", () => {
    expect(settingChangeOf({ type: "saved", setting: saved })).toEqual({ saved });
    expect(settingChangeOf({ type: "removed", settingId: setting.id })).toEqual({ removedId: setting.id });
  });

  it("저장과 삭제가 끝나지 않은 이벤트는 알리지 않는다", () => {
    const unfinished: NotificationRowEvent[] = [
      { type: "open", setting },
      { type: "cancel" },
      { type: "submit", schedule, first: true },
      { type: "answered", status: "denied" },
      { type: "failed", message: "실패" },
      { type: "remove" },
      { type: "removeFailed", message: "실패" },
    ];
    expect(unfinished.map(settingChangeOf)).toEqual(unfinished.map(() => null));
  });
});

describe("권한을 묻는 동안 취소", () => {
  it("창을 닫으면 진행 중인 일이 없는 상태로 돌아가고, 늦게 온 허락으로는 기기 등록도 설정 저장도 하지 않는다", async () => {
    const services = servicesFor(null);
    let answerPermission: (registration: NotificationRegistration | null) => void = () => {};
    services.requestRegistration.mockReturnValue(new Promise((resolve) => (answerPermission = resolve)));
    const ask = new AbortController();
    let state = editingNew;
    const apply = (event: NotificationRowEvent) => {
      state = nextNotificationRowState(state, event);
    };

    const submission = submitNotification(target, null, schedule, services, apply, ask.signal);
    expect(state.status).toBe("asking");
    ask.abort();
    apply({ type: "cancel" });
    answerPermission({ requestId: "request-1", status: "granted", token: "device-token", environment: "SANDBOX" });
    await submission;

    expect(state).toEqual(idle);
    expect(services.registerPushDevice).not.toHaveBeenCalled();
    expect(services.saveNotificationSetting).not.toHaveBeenCalled();
  });
});
