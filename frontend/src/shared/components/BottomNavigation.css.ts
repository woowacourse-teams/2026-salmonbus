import { keyframes, style } from "@vanilla-extract/css";
import { PHONE_MEDIA } from "@/shared/styles/media";
import { vars } from "@/shared/styles/tokens.css";

export const navigation = style({
  position: "fixed",
  bottom: 0,
  left: "50%",
  transform: "translateX(-50%)",
  display: "flex",
  width: "100%",
  maxWidth: 425,
  "@media": { [PHONE_MEDIA]: { maxWidth: "none" } },
  zIndex: 20,
  paddingBottom: "env(safe-area-inset-bottom, 0px)",
  borderTop: "1px solid #EEEAE6",
  background: "white",
});

const selectTab = keyframes({ from: { color: "#858079" }, to: { color: vars.color.brand } });

export const tab = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 4,
  minHeight: 66,
  color: "#858079",
  fontSize: 10,
  textDecoration: "none",
  selectors: {
    '&[aria-current="page"]': { color: vars.color.brand, animation: `${selectTab} 220ms ease both` },
  },
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      selectors: { '&[aria-current="page"]': { animation: "none" } },
    },
  },
});
