export interface NotificationTap {
  key: string;
  target: Record<string, unknown> | null;
}

export type TapDecision = 'send' | 'held' | 'duplicate';

export function createTapInbox() {
  let held: NotificationTap | null = null;
  let lastKey: string | null = null;

  function receive(tap: NotificationTap, documentLoaded: boolean): TapDecision {
    if (tap.key === lastKey) return 'duplicate';
    lastKey = tap.key;
    if (documentLoaded) {
      held = null;
      return 'send';
    }
    held = tap;
    return 'held';
  }

  function take(): NotificationTap | null {
    const tap = held;
    held = null;
    return tap;
  }

  return { receive, take };
}
