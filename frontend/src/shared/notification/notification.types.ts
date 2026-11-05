export type NotificationPermissionStatus = "granted" | "denied" | "undetermined";

export type ApnsEnvironment = "SANDBOX" | "PRODUCTION";

export interface NotificationTarget {
  routeId: string;
  stopId: string;
  direction: "UP" | "DOWN";
}

export type NotificationDevice = { token: string; environment: ApnsEnvironment } | { token: null; environment: null };

export type NotificationRegistration = { requestId: string; status: NotificationPermissionStatus } & NotificationDevice;

export interface OpenedNotification {
  requestId: string | null;
  target: NotificationTarget | null;
}

export interface NotificationCommands {
  "notification.requestPermission": { requestId: string };
  "notification.getRegistration": { requestId: string };
  "notification.takeOpened": { requestId: string };
}

export type NotificationCommandType = keyof NotificationCommands;

export interface NotificationEvents {
  "notification.registration": NotificationRegistration;
  "notification.opened": OpenedNotification;
}
