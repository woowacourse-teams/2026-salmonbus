// 웹의 frontend/src/shared/haptic/haptic.types.ts와 같은 메시지를 받는다.
export const HAPTIC_CAPABILITIES = ['haptic.impact'] as const;

export type HapticImpactStyle = 'light' | 'medium';

export function hapticImpactStyleFrom(payload: Record<string, unknown>): HapticImpactStyle | null {
  return payload.style === 'light' || payload.style === 'medium' ? payload.style : null;
}
