import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "./tokens.css";

const enterPage = keyframes({ from: { opacity: 0.6 }, to: { opacity: 1 } });

export const pageEnter = style({
  animation: `${enterPage} 220ms ${vars.easing.slide} both`,
  "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
});
