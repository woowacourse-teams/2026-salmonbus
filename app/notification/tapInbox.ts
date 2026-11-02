import type { NotificationTarget } from '../../frontend/src/shared/notification/notification.types';

export interface NotificationTap {
  identifier: string;
  target: NotificationTarget | null;
}

/**
 * 알림 탭을 담는 칸 하나. 알림 하나는 판정 보드 하나를 여니 마지막 탭만 남긴다.
 * 꺼진 앱을 연 탭은 마지막 응답과 응답 이벤트 두 길로 올 수 있어서 identifier로 한 번만 받는다.
 */
export function createTapInbox() {
  let held: NotificationTap | null = null;
  let lastIdentifier: string | null = null;

  /** 웹 문서가 다 떴으면 true를 돌려주고, 부른 쪽이 바로 보낸다. 뜨는 중이면 칸에 두고 false다. */
  function accept(tap: NotificationTap, documentLoaded: boolean): boolean {
    if (tap.identifier === lastIdentifier) return false;
    lastIdentifier = tap.identifier;
    if (documentLoaded) {
      held = null;
      return true;
    }
    held = tap;
    return false;
  }

  function take(): NotificationTarget | null {
    const target = held?.target ?? null;
    held = null;
    return target;
  }

  return { accept, take };
}
