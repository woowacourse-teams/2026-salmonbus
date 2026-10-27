import * as Haptics from 'expo-haptics';
import type { BridgeMessage } from '../bridge/bridge';
import { hapticImpactStyleFrom, type HapticImpactStyle } from './hapticMessages';

const HAPTIC_STYLES = {
  light: Haptics.ImpactFeedbackStyle.Light,
  medium: Haptics.ImpactFeedbackStyle.Medium,
} satisfies Record<HapticImpactStyle, Haptics.ImpactFeedbackStyle>;

/** 진동은 실패해도 화면에 영향이 없어서 실패를 버린다. */
export function playHaptic(message: BridgeMessage): void {
  const style = hapticImpactStyleFrom(message.payload);
  if (style === null) return;
  Haptics.impactAsync(HAPTIC_STYLES[style]).catch(() => undefined);
}
