import { describe, expect, it } from "@jest/globals";
import {
  composerPlaceholder,
  connectionNotice,
  deliveryLabel,
  errorText,
  launcherLabel,
  nicknameLabel,
  sizeToggleLabel,
} from "./chatCopy";
import type { ChatErrorFrame } from "./chatProtocol";

function errorFrame(code: string, message: string): ChatErrorFrame {
  return { v: 1, type: "error", requestId: null, code, message, retryAfterMs: null, fatal: false };
}

describe("connectionNotice", () => {
  it("연결된 상태에서는 상태 띠를 두지 않는다", () => {
    expect(connectionNotice("ready")).toBeNull();
  });

  it("처음 연결과 다시 연결은 진행 중으로 알리고 문구로 구분한다", () => {
    expect(connectionNotice("connecting")).toEqual({ tone: "progress", text: "대화방에 연결하는 중이에요" });
    expect(connectionNotice("reconnecting")).toEqual({
      tone: "progress",
      text: "연결이 끊겼어요. 다시 연결하는 중이에요",
    });
  });

  it("연결 실패와 서버에서 꺼진 상태는 오류로 알리고 문구로 구분한다", () => {
    expect(connectionNotice("failed")).toEqual({ tone: "error", text: "대화방에 연결하지 못했어요" });
    expect(connectionNotice("unavailable")).toEqual({ tone: "error", text: "지금은 채팅을 쓸 수 없어요" });
  });
});

describe("errorText", () => {
  it("아는 오류 코드는 서버 문구 대신 코드별 문구로 바꾼다", () => {
    expect(errorText(errorFrame("RATE_LIMITED", "too fast"))).toBe(
      "메시지를 너무 빨리 보냈어요. 잠시 후 다시 보내 주세요.",
    );
    expect(errorText(errorFrame("INVALID_BODY", "bad"))).toBe("메시지 내용을 확인해 주세요.");
    expect(errorText(errorFrame("ID_CONFLICT", "conflict"))).toBe("메시지를 다시 작성해 보내 주세요.");
    expect(errorText(errorFrame("CHAT_UNAVAILABLE", "off"))).toBe("지금은 채팅을 쓸 수 없어요.");
  });

  it("프로토콜 오류 코드는 모두 연결 실패 문구로 바꾼다", () => {
    const codes = ["INVALID_FRAME", "HELLO_REQUIRED", "UNSUPPORTED_PROTOCOL"];

    expect(codes.map((code) => errorText(errorFrame(code, "protocol")))).toEqual([
      "대화방에 연결하지 못했어요",
      "대화방에 연결하지 못했어요",
      "대화방에 연결하지 못했어요",
    ]);
  });

  it("모르는 오류 코드는 서버가 보낸 문구를 그대로 쓴다", () => {
    expect(errorText(errorFrame("ROOM_CLOSED", "방이 닫혔어요"))).toBe("방이 닫혔어요");
  });
});

describe("composerPlaceholder", () => {
  it("연결 상태마다 보낼 수 있는지와 방법을 다르게 안내한다", () => {
    expect(composerPlaceholder("ready")).toBe("버스 상황을 나눠 보세요");
    expect(composerPlaceholder("failed")).toBe("다시 연결하면 보낼 수 있어요");
    expect(composerPlaceholder("unavailable")).toBe("지금은 메시지를 보낼 수 없어요");
    expect(composerPlaceholder("connecting")).toBe("연결되면 보낼 수 있어요");
    expect(composerPlaceholder("reconnecting")).toBe("연결되면 보낼 수 있어요");
  });
});

describe("launcherLabel", () => {
  it("안 읽은 메시지가 없으면 개수를 붙이지 않는다", () => {
    expect(launcherLabel("3330", 0)).toBe("3330 채팅 열기");
  });

  it("안 읽은 메시지가 있으면 개수를 붙인다", () => {
    expect(launcherLabel("3330", 2)).toBe("3330 채팅 열기, 안 읽은 메시지 2개");
  });
});

describe("sizeToggleLabel", () => {
  it("전체로 열려 있으면 접기로, 그 밖에는 크게 보기로 안내한다", () => {
    expect(sizeToggleLabel("full")).toBe("채팅 접기");
    expect(sizeToggleLabel("half")).toBe("채팅 크게 보기");
  });
});

describe("nicknameLabel", () => {
  it("이름을 받기 전에는 준비 중으로, 받은 뒤에는 그 이름으로 참여 중이라고 보여 준다", () => {
    expect(nicknameLabel(null, false)).toBe("익명 이름을 준비하고 있어요");
    expect(nicknameLabel("수다스러운 야탑역", false)).toBe("수다스러운 야탑역 님으로 참여 중");
  });

  it("이름을 받기 전에 연결이 멈추면 부제를 비우고, 이미 받은 이름은 그대로 보여 준다", () => {
    expect(nicknameLabel(null, true)).toBeNull();
    expect(nicknameLabel("수다스러운 야탑역", true)).toBe("수다스러운 야탑역 님으로 참여 중");
  });
});

describe("deliveryLabel", () => {
  it("보내는 중과 전송 실패를 구분한다", () => {
    expect(deliveryLabel("sending")).toBe("보내는 중");
    expect(deliveryLabel("failed")).toBe("전송 실패");
  });
});
