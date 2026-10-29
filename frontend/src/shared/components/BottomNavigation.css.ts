import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const navigation = style({
  position: "fixed",
  bottom: "calc(28px + env(safe-area-inset-bottom, 0px))",
  left: "50%",
  transform: "translateX(-50%)",
  display: "flex",
  gap: 4,
  width: "calc(100% - 64px)",
  maxWidth: 160,
  zIndex: 20,
  padding: 3,
  border: "1px solid #FFFFFF80",
  borderRadius: 999,
  background: "#FBFAF8",
  boxShadow:
    "0 8px 28px #3B2A1B1F, 0 2px 6px #3B2A1B0A, inset 0 1px 0 #FFFFFFB3, inset 0 -1px 0 #FFFFFF40, inset 0 0 0 1px #93857614",
  "@supports": {
    "(backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))": {
      background: "linear-gradient(180deg, #FFFFFF26, #FFFFFF08), #FFFFFF26",
      backdropFilter: "blur(24px) saturate(160%)",
      WebkitBackdropFilter: "blur(24px) saturate(160%)",
    },
  },
  "@media": {
    "(prefers-reduced-transparency: reduce)": {
      background: "#FBFAF8",
      backdropFilter: "none",
      WebkitBackdropFilter: "none",
    },
  },
});

const activeColor = "#B24B37";
const selectTab = keyframes({ from: { color: "#858079" }, to: { color: activeColor } });

export const indicator = style({
  position: "absolute",
  top: 3,
  bottom: 3,
  left: 3,
  width: "calc((100% - 10px) / 2)",
  borderRadius: 999,
  background: "linear-gradient(180deg, #FFFFFF66, #FFFFFF08), #F2825B40",
  boxShadow: "inset 0 1px 0 #FFFFFFCC, inset 0 0 0 1px #E99C7F66, 0 2px 6px #BA5A3714",
  pointerEvents: "none",
  transform: "translateX(0)",
  transition: "transform 320ms cubic-bezier(0.22, 1, 0.36, 1), opacity 160ms ease",
  selectors: {
    [`${navigation}[data-active="mypage"] &`]: { transform: "translateX(calc(100% + 4px))" },
    [`${navigation}[data-active="none"] &`]: { opacity: 0 },
  },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});

export const tab = style({
  position: "relative",
  display: "flex",
  flex: 1,
  alignItems: "center",
  justifyContent: "center",
  minHeight: 46,
  borderRadius: 999,
  color: "#716A64",
  textDecoration: "none",
  transition: "transform 120ms ease",
  selectors: {
    "&:active": { transform: "scale(0.96)" },
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
    '&[aria-current="page"]': {
      color: activeColor,
      animation: `${selectTab} 220ms ease both`,
    },
  },
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
      selectors: { '&[aria-current="page"]': { animation: "none" }, "&:active": { transform: "none" } },
    },
  },
});
