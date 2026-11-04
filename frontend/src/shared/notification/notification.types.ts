/**
 * 웹과 앱이 함께 쓰는 알림 메시지 모양. 앱의 app/notification이 이 파일을 import type으로 읽으므로
 * 다른 모듈을 import하지 않는다. 메시지가 바뀌면 앱 tsc가 막는다.
 */
export type NotificationPermissionStatus = "granted" | "denied" | "undetermined";

/** 개발 서명과 시뮬레이터 빌드는 SANDBOX, TestFlight와 App Store 빌드는 PRODUCTION이다. */
export type ApnsEnvironment = "SANDBOX" | "PRODUCTION";

/** 즐겨찾기 대상과 같은 세 값. 알림 payload의 target과 notification.opened의 target이 같은 모양이다. */
export interface NotificationTarget {
  routeId: string;
  stopId: string;
  direction: "UP" | "DOWN";
}

/** token은 허용했고 APNs 등록과 환경 판단까지 끝났을 때만 있다. 하나라도 안 되면 둘 다 null이다. */
export type NotificationDevice = { token: string; environment: ApnsEnvironment } | { token: null; environment: null };

export type NotificationRegistration = { requestId: string; status: NotificationPermissionStatus } & NotificationDevice;

/**
 * requestId는 notification.takeOpened의 답이면 그 id, 앱이 먼저 보내면 null이다.
 * 앱은 웹 문서가 다 뜬 뒤 누른 탭은 바로, 뜨는 동안 누른 탭이나 꺼진 앱을 연 탭은 문서가 다 뜬 순간 먼저 보낸다.
 */
export interface OpenedNotification {
  requestId: string | null;
  target: NotificationTarget | null;
}

export interface NotificationCommands {
  "notification.requestPermission": { requestId: string };
  /** 권한 창을 띄우지 않고 지금 상태만 읽는다. */
  "notification.getRegistration": { requestId: string };
  /** 앱이 쌓아 둔 알림 탭 대상을 꺼낸다. 꺼내면 앱에서 지워진다. */
  "notification.takeOpened": { requestId: string };
}

export type NotificationCommandType = keyof NotificationCommands;

export interface NotificationEvents {
  "notification.registration": NotificationRegistration;
  "notification.opened": OpenedNotification;
}
