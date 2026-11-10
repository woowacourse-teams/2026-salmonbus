import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const disclosureMotion = `${vars.duration.spring} ${vars.easing.slide}`;

export const section = style({
  display: "flex",
  flexDirection: "column",
  marginTop: 32,
  color: "#292A2D",
});

export const heading = style({ margin: "0 0 4px", fontSize: 17, fontWeight: 700, lineHeight: "25px" });

export const row = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  minHeight: 48,
  fontSize: 14,
});

export const descriptionToggle = style({
  display: "inline-flex",
  alignItems: "center",
  alignSelf: "stretch",
  gap: 4,
  padding: 0,
  border: 0,
  background: "transparent",
  color: "inherit",
  font: "inherit",
  cursor: "pointer",
});

export const chevron = style({
  transition: `transform ${disclosureMotion}`,
  selectors: {
    [`${descriptionToggle}[aria-expanded="true"] &`]: { transform: "rotate(90deg)" },
  },
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
  },
});

export const descriptionPanel = style({
  display: "grid",
  gridTemplateRows: "1fr",
  opacity: 1,
  visibility: "visible",
  transition: `grid-template-rows ${disclosureMotion}, opacity ${disclosureMotion}, visibility ${disclosureMotion}`,
  selectors: {
    '&[aria-hidden="true"]': { gridTemplateRows: "0fr", opacity: 0, visibility: "hidden" },
  },
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
  },
});

export const description = style({
  margin: 0,
  minHeight: 0,
  overflow: "hidden",
  color: "#68645F",
  fontSize: 12,
  lineHeight: 1.7,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});

export const policyLink = style([row, { color: "inherit", textDecoration: "none" }]);
