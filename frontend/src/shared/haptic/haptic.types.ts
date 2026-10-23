export type HapticImpactStyle = "light" | "medium";

export interface HapticCommands {
  "haptic.impact": { style: HapticImpactStyle };
}
