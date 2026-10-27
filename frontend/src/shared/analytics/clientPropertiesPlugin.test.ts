import { describe, expect, it } from "@jest/globals";
import { clientPropertiesPluginFor } from "./clientPropertiesPlugin";

const appProperties = { client_platform: "ios_app", native_app_version: "1.0.0" } as const;

describe("실행 환경 플러그인", () => {
  it("일반 이벤트에 실행 환경 값을 사용자 속성으로 싣는다", async () => {
    const plugin = clientPropertiesPluginFor(appProperties);
    await expect(plugin.execute?.({ event_type: "[Amplitude] Page Viewed" })).resolves.toEqual({
      event_type: "[Amplitude] Page Viewed",
      user_properties: appProperties,
    });
  });

  it("이벤트에 있던 사용자 속성은 남긴다", async () => {
    const plugin = clientPropertiesPluginFor({ client_platform: "web" });
    const event = await plugin.execute?.({ event_type: "board_viewed", user_properties: { existing: "kept" } });
    expect(event?.user_properties).toEqual({ existing: "kept", client_platform: "web" });
  });

  it("identify 이벤트는 바꾸지 않는다", async () => {
    const plugin = clientPropertiesPluginFor(appProperties);
    const identify = { event_type: "$identify", user_properties: { $set: { other: "value" } } };
    await expect(plugin.execute?.(identify)).resolves.toBe(identify);
  });
});
