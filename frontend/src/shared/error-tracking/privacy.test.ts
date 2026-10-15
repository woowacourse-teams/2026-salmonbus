import { describe, expect, it } from "@jest/globals";
import { redactedBodyFrom, redactedTextFrom, redactedValueFrom, safeUrlFrom } from "./privacy";

describe("센트리로 나가는 인증 정보 제거", () => {
  it("중첩된 JSON의 쿠키/토큰/인증 헤더를 가리고 오류 세 항목은 보존한다", () => {
    const body = {
      code: "INTERNAL_ERROR",
      message: "서버 오류",
      requestId: "request-1",
      nested: [{ access_token: "secret-access", Cookie: "session=secret-cookie", authorization: "Bearer secret-auth" }],
    };
    const result = JSON.parse(redactedBodyFrom(JSON.stringify(body)));
    expect(result).toMatchObject({ code: body.code, message: body.message, requestId: body.requestId });
    const serialized = JSON.stringify(redactedValueFrom({ extra: { response_body: JSON.stringify(result) } }));
    expect(serialized).not.toContain("secret-");
    expect(result.nested[0].access_token).toBe("[Filtered]");
  });

  it.each([
    'oops {"accessToken":"secret-access"',
    "Cookie: a=secret-cookie; b=another-value\ncontent",
    "Authorization: Bearer secret-auth\ncontent",
    '<input value="secret-input" name="csrf-token">',
    '{"accessToken":&quot;secret-encoded&quot;} trailing',
    "failed https://example.com/path?access_token=secret-query#secret-fragment",
  ])("깨진 JSON·HTML·헤더·URL의 알려진 인증 표현을 가린다: %s", (body) => {
    expect(redactedBodyFrom(body)).not.toContain("secret-");
  });

  it("URL 인증 정보와 쿼리·fragment는 제외하되 API 경로는 남긴다", () => {
    expect(safeUrlFrom("https://user:password@example.com/api/routes/123?token=abc#def")).toBe(
      "https://example.com/api/routes/123",
    );
    expect(safeUrlFrom("/api/routes/123?token=abc")).toBe("/api/routes/123");
    expect(redactedTextFrom("Bearer abc.def.ghi")).toBe("Bearer [Filtered]");
  });
});
