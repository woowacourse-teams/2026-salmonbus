import { describe, expect, it } from "@jest/globals";
import examples from "../../../../contract/examples/chat-v1.json";
import { isKnownErrorCode, parseServerFrame } from "./chatProtocol";

const message = examples.serverFrames["message.created"].message;

describe("parseServerFrame", () => {
  it.each(Object.entries(examples.serverFrames))("계약 예시 %s 프레임을 같은 값으로 파싱한다", (_name, frame) => {
    // given
    const raw = JSON.stringify(frame);

    // when
    const parsed = parseServerFrame(raw);

    // then
    expect(parsed).toEqual(frame);
  });

  it("session.ready의 모르는 필드는 무시한다", () => {
    // given
    const frame = { ...examples.serverFrames["session.ready"], retentionHours: 24 };

    // when
    const parsed = parseServerFrame(JSON.stringify(frame));

    // then
    expect(parsed).toEqual(examples.serverFrames["session.ready"]);
  });

  it("requestId와 retryAfterMs가 null인 오류를 받는다", () => {
    // given
    const frame = {
      v: 1,
      type: "error",
      requestId: null,
      code: "HELLO_REQUIRED",
      message: "session.start가 필요합니다.",
      retryAfterMs: null,
      fatal: true,
    };

    // when
    const parsed = parseServerFrame(JSON.stringify(frame));

    // then
    expect(parsed).toEqual(frame);
  });

  it("requestId와 retryAfterMs가 빠진 오류는 두 값을 null로 받는다", () => {
    // given
    const frame = { v: 1, type: "error", code: "ID_CONFLICT", message: "다른 내용입니다.", fatal: false };

    // when
    const parsed = parseServerFrame(JSON.stringify(frame));

    // then
    expect(parsed).toEqual({ ...frame, requestId: null, retryAfterMs: null });
  });

  it("모르는 오류 코드도 코드와 fatal을 살려 받는다", () => {
    // given
    const frame = {
      v: 1,
      type: "error",
      requestId: null,
      code: "ROOM_CLOSED",
      message: "방이 닫혔습니다.",
      retryAfterMs: null,
      fatal: true,
    };

    // when
    const parsed = parseServerFrame(JSON.stringify(frame));

    // then
    expect(parsed).toEqual(frame);
    expect(isKnownErrorCode("ROOM_CLOSED")).toBe(false);
    expect(isKnownErrorCode("CHAT_UNAVAILABLE")).toBe(true);
  });

  it("오류 필드의 타입이 틀리면 거절한다", () => {
    const base = examples.serverFrames["error.rateLimited"];
    expect(parseServerFrame(JSON.stringify({ ...base, requestId: 7 }))).toBeNull();
    expect(parseServerFrame(JSON.stringify({ ...base, retryAfterMs: -1 }))).toBeNull();
    expect(parseServerFrame(JSON.stringify({ ...base, fatal: "false" }))).toBeNull();
    expect(parseServerFrame(JSON.stringify({ ...base, code: 429 }))).toBeNull();
  });

  it("깨진 JSON, 다른 버전, 모르는 타입과 불완전한 메시지를 거절한다", () => {
    expect(parseServerFrame("{")).toBeNull();
    expect(parseServerFrame(JSON.stringify({ v: 2, type: "history.end" }))).toBeNull();
    expect(parseServerFrame(JSON.stringify({ v: 1, type: "mystery" }))).toBeNull();
    expect(parseServerFrame(JSON.stringify({ v: 1, type: "message.created", message: { id: "only" } }))).toBeNull();
    expect(
      parseServerFrame(JSON.stringify({ v: 1, type: "message.created", message: { ...message, createdAt: "later" } })),
    ).toBeNull();
  });
});
