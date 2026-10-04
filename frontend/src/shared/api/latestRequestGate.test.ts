import { describe, expect, it } from "@jest/globals";
import { createLatestRequestGate } from "./latestRequestGate";
import { cancellationReasonFrom } from "./cancellation";

describe("요청 취소 사유", () => {
  it("새 요청으로 교체와 화면 종료를 구분하고 이전 응답을 무효화한다", () => {
    const gate = createLatestRequestGate();
    const old = gate.issue();
    const current = gate.issue();
    expect(old.signal.aborted).toBe(true);
    expect(cancellationReasonFrom(old.signal.reason)).toBe("superseded");
    expect(old.isLatest()).toBe(false);
    gate.close();
    expect(cancellationReasonFrom(current.signal.reason)).toBe("disposed");
    expect(current.isLatest()).toBe(false);
  });

  it("호출자가 이유 없이 취소하면 정상 흐름으로 추측하지 않는다", () => {
    const ticket = createLatestRequestGate().issue();
    ticket.abort();
    expect(cancellationReasonFrom(ticket.signal.reason)).toBe("unknown");
    expect(cancellationReasonFrom("disposed")).toBe("unknown");
  });
});
