export interface NotificationTap {
  key: string;
  target: Record<string, unknown> | null;
}

export type TapDecision = 'send' | 'held' | 'duplicate';

const TAP_RETENTION_MS = 10 * 60 * 1_000;

export function createTapInbox(now: () => number = Date.now) {
  let held: { tap: NotificationTap; receivedAt: number } | null = null;
  let lastKey: string | null = null;

  function receive(tap: NotificationTap, documentReady: boolean): TapDecision {
    if (tap.key === lastKey) return 'duplicate';
    lastKey = tap.key;
    if (documentReady) {
      held = null;
      return 'send';
    }
    held = { tap, receivedAt: now() };
    return 'held';
  }

  function holding(): boolean {
    return held !== null && now() - held.receivedAt < TAP_RETENTION_MS;
  }

  function take(): NotificationTap | null {
    const pending = held;
    held = null;
    if (pending === null || now() - pending.receivedAt >= TAP_RETENTION_MS) return null;
    return pending.tap;
  }

  return { receive, holding, take };
}
