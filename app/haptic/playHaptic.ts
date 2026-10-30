import * as Haptics from 'expo-haptics';
import type { HapticKind } from '../../frontend/src/shared/haptic/haptic.types';
import type { BridgeMessage } from '../bridge/bridge';

// 진동 종류 목록의 원본은 웹의 HapticKind다. 웹에서 종류가 늘거나 줄면 이 표에서 앱 tsc가 막는다.
const EXPO_HAPTIC_PLAYERS = {
  impact: () => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light),
  selection: () => Haptics.selectionAsync(),
  success: () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success),
  error: () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error),
} satisfies Record<HapticKind, () => Promise<void>>;

const HAPTIC_KINDS = Object.keys(EXPO_HAPTIC_PLAYERS) as HapticKind[];

export const HAPTIC_CAPABILITIES = HAPTIC_KINDS.map((kind) => `haptic.${kind}` as const);

/** 진동은 실패해도 화면에 영향이 없어서 실패를 버린다. */
export function playHaptic(message: BridgeMessage): void {
  const kind = HAPTIC_KINDS.find((kind) => message.type === `haptic.${kind}`);
  if (kind === undefined) return;
  EXPO_HAPTIC_PLAYERS[kind]().catch(() => undefined);
}
