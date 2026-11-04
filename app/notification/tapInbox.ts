export interface NotificationTap {
  /** 같은 탭이 두 길로 와도 같은 값이다. 알림 identifier와 받은 시각을 합친다. */
  key: string;
  target: Record<string, unknown> | null;
}

/** send는 부른 쪽이 바로 웹에 보낸다. held는 칸에 두고, 웹 문서가 다 뜨거나 takeOpened가 오면 내준다. */
export type TapDecision = 'send' | 'held' | 'duplicate';

/**
 * 알림 탭을 담는 칸 하나. 알림 하나는 판정 보드 하나를 여니 마지막 탭만 남긴다.
 * 꺼진 앱을 연 탭은 마지막 응답과 응답 이벤트 두 길로 올 수 있어서 key로 한 번만 받는다.
 */
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

  /** takeOpened의 답. 꺼낸 탭은 칸에서 지워져서 문서가 다 떠도 다시 가지 않는다. */
  function take(): Record<string, unknown> | null {
    return releaseHeld()?.target ?? null;
  }

  /** 웹 문서가 다 떴을 때 칸에 남은 탭. 꺼낸 탭은 칸에서 지워져서 takeOpened에도 다시 가지 않는다. */
  function releaseHeld(): NotificationTap | null {
    const tap = held;
    held = null;
    return tap;
  }

  return { receive, take, releaseHeld };
}
