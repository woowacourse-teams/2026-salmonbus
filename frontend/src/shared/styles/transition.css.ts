import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "./tokens.css";

const enterMode = keyframes({ from: { opacity: 0.65 }, to: { opacity: 1 } });

export const modeTransition = style({
  animation: `${enterMode} 220ms ${vars.easing.slide}`,
  "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
});
