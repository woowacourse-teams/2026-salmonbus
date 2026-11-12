import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { TRACK_PADDING } from "./DirectionTabs.css";

const disclosureMotion = `${vars.duration.spring} ${vars.easing.slide}`;

export const summary = style({
  display: "grid",
  justifyItems: "start",
  minWidth: 0,
  paddingInlineStart: TRACK_PADDING,
});

export const toggle = style({
  display: "inline-flex",
  alignItems: "center",
  gap: "6px",
  minHeight: "28px",
  padding: "0 8px 0 0",
  border: 0,
  background: "none",
  color: vars.color.textSubtle,
  fontSize: "12px",
  fontWeight: 600,
  cursor: "pointer",
});

export const chevron = style({
  width: "6px",
  height: "6px",
  marginInlineStart: "2px",
  borderRight: "1.5px solid currentColor",
  borderBottom: "1.5px solid currentColor",
  transform: "translateY(-2px) rotate(45deg)",
  transition: `transform ${disclosureMotion}`,
  selectors: {
    [`${toggle}[aria-expanded='true'] &`]: { transform: "translateY(1px) rotate(225deg)" },
  },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});

export const detailsPanel = style({
  display: "grid",
  gridTemplateRows: "1fr",
  opacity: 1,
  visibility: "visible",
  transition: `grid-template-rows ${disclosureMotion}, opacity ${disclosureMotion}, visibility ${disclosureMotion}`,
  selectors: {
    '&[aria-hidden="true"]': { gridTemplateRows: "0fr", opacity: 0, visibility: "hidden" },
  },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});

export const detailsClip = style({ minHeight: 0, overflow: "hidden" });

export const details = style({
  display: "flex",
  margin: 0,
  gap: "8px",
  paddingBlockStart: "2px",
  paddingBlockEnd: "6px",
  "@media": { "(max-width: 359px)": { gap: "6px" } },
});

export const detail = style({
  display: "flex",
  alignItems: "center",
  minHeight: "16px",
  selectors: {
    "& + &::before": {
      content: '""',
      flexShrink: 0,
      width: "2px",
      height: "2px",
      marginRight: "8px",
      borderRadius: "1px",
      backgroundColor: vars.color.routeSummary.separator,
    },
  },
});

export const value = style({
  margin: 0,
  color: vars.color.routeSummary.text,
  fontSize: "11px",
  fontWeight: 500,
  lineHeight: 1,
  whiteSpace: "nowrap",
  fontVariantNumeric: "tabular-nums",
  "@media": { "(max-width: 359px)": { fontSize: "10px" } },
});

export const label = style([value, { marginInlineEnd: "4px", fontWeight: 700 }]);
