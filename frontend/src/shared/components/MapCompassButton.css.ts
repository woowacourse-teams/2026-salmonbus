import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const compassDial = style({
  position: "relative",
  width: 32,
  height: 32,
  transform: "scale(0.9375)",
});

export const compassNeedle = style({
  position: "absolute",
  inset: 0,
  selectors: {
    "&::before": {
      content: '""',
      position: "absolute",
      inset: 0,
      background: `linear-gradient(90deg, ${vars.color.brand} 50%, #D84433 50%)`,
      clipPath:
        'path("M15.55 6.35 Q16 5.65 16.45 6.35 C18.8 8.9 20.05 12.08 19.5 15.11 C19.13 17.25 18.08 19.15 16.96 20.48 L16 21.47 L15.04 20.48 C13.92 19.15 12.87 17.25 12.5 15.11 C11.95 12.08 13.2 8.9 15.55 6.35 Z")',
    },
    "&::after": {
      content: '""',
      position: "absolute",
      inset: 0,
      background: "#B8392D",
      clipPath:
        'path("M15.04 19.9 L16 20.58 L16.96 19.9 C18.64 20.49 20.17 21.75 21.08 23.5 Q21.51 24.35 20.56 24.13 Q17.79 23.69 16 22.45 Q14.21 23.69 11.44 24.13 Q10.49 24.35 10.92 23.5 C11.83 21.75 13.36 20.49 15.04 19.9 Z")',
    },
  },
});

export const compassNorth = style({
  position: "absolute",
  color: "#202823",
  fontFamily: "-apple-system, Arial, sans-serif",
  fontSize: 6.6,
  fontWeight: 900,
  lineHeight: 1,
  WebkitTextStroke: "0.54px currentColor",
  transform: "translate(-50%, -50%) scaleX(1.08)",
});
