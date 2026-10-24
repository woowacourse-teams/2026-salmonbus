import type { AppHost } from "./bridge.types";

/**
 * Amplitude 사용자 속성과 Sentry 태그에 같은 이름으로 붙이는 실행 환경 값.
 * Amplitude 기본 속성 Platform은 WebView 안에서도 Web이라서 이름을 client_platform으로 나눈다.
 */
export type ClientProperties = { client_platform: "web" } | { client_platform: "ios_app"; native_app_version: string };

export function clientPropertiesFrom(appHost: AppHost | null): ClientProperties {
  return appHost === null
    ? { client_platform: "web" }
    : { client_platform: "ios_app", native_app_version: appHost.appVersion };
}
