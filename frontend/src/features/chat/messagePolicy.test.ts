import { describe, expect, it } from "@jest/globals";
import type { ChatMessage } from "./chatProtocol";
import { chatDataReducer, initialChatDataState } from "./messagePolicy";

const saved: ChatMessage = {
  id: "message-1",
  authorId: "author-1",
  nickname: "느긋한 야탑역",
  body: "서현역에서 많이 내려요",
  createdAt: "2026-09-29T13:21:34.127Z",
};

describe("chatDataReducer", () => {
  it("ACK와 broadcast 순서와 관계없이 저장 메시지는 하나만 남긴다", () => {
    const pending = chatDataReducer(initialChatDataState, {
      type: "message.pending",
      message: { clientMessageId: "client-1", body: saved.body, createdAt: saved.createdAt, delivery: "sending" },
    });
    const broadcast = chatDataReducer(pending, { type: "message.created", message: saved });
    const acknowledged = chatDataReducer(broadcast, {
      type: "message.ack",
      clientMessageId: "client-1",
      message: saved,
    });
    expect(acknowledged.messages).toEqual([saved]);
    expect(acknowledged.pending).toEqual([]);

    const duplicateAck = chatDataReducer(acknowledged, {
      type: "message.ack",
      clientMessageId: "client-1",
      message: saved,
    });
    expect(duplicateAck.messages).toEqual([saved]);
  });

  it("연결이 끊기면 전송 중인 메시지만 실패로 바꾼다", () => {
    const state = {
      messages: [],
      pending: [
        { clientMessageId: "a", body: "a", createdAt: saved.createdAt, delivery: "sending" as const },
        { clientMessageId: "b", body: "b", createdAt: saved.createdAt, delivery: "failed" as const },
      ],
    };
    const lost = chatDataReducer(state, { type: "connection.lost", failureMessage: "연결이 끊겨 보내지 못했어요." });
    expect(lost.pending.map((item) => item.delivery)).toEqual(["failed", "failed"]);
    expect(lost.pending[0]?.failureMessage).toBe("연결이 끊겨 보내지 못했어요.");
  });

  it("ID 충돌 메시지는 새 clientMessageId로 다시 보낼 수 있게 바꾼다", () => {
    const pending = chatDataReducer(initialChatDataState, {
      type: "message.pending",
      message: { clientMessageId: "conflicted", body: saved.body, createdAt: saved.createdAt, delivery: "sending" },
    });
    const failed = chatDataReducer(pending, {
      type: "message.failed",
      clientMessageId: "conflicted",
      failureMessage: "메시지를 다시 작성해 보내 주세요.",
      retryWithNewId: true,
    });
    const rekeyed = chatDataReducer(failed, {
      type: "message.rekey",
      clientMessageId: "conflicted",
      nextClientMessageId: "fresh",
    });
    expect(rekeyed.pending).toEqual([
      { clientMessageId: "fresh", body: saved.body, createdAt: saved.createdAt, delivery: "sending" },
    ]);
  });
});
