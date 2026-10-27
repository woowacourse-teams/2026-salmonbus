import { describe, expect, it } from "@jest/globals";
import { clientPropertiesFrom } from "./clientProperties";

describe("실행 환경 값", () => {
  it("브라우저면 web만 남긴다", () => {
    expect(clientPropertiesFrom(null)).toEqual({ client_platform: "web" });
  });

  it("앱 안이면 ios_app과 앱 버전을 남긴다", () => {
    expect(clientPropertiesFrom({ appVersion: "1.0.0", capabilities: [] })).toEqual({
      client_platform: "ios_app",
      native_app_version: "1.0.0",
    });
  });
});
