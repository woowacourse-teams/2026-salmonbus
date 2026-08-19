import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const track = style({
  display: "flex",
  gap: "2px",
  height: "41px",
  padding: "3px",
  borderRadius: "13px",
  backgroundColor: vars.color.segmentTrack,
});

export const tab = style({
  flex: 1,
  minWidth: 0,
  overflow: "hidden",
  border: "1px solid transparent",
  borderRadius: vars.radius.control,
  padding: "0 8px",
  backgroundColor: "transparent",
  color: vars.color.textMuted,
  fontFamily: "inherit",
  fontSize: "13px",
  fontWeight: 600,
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
  cursor: "pointer",
});

export const selectedTab = style([
  tab,
  {
    borderColor: vars.color.cardBorder,
    backgroundColor: vars.color.surface,
    color: vars.color.textStrong,
    cursor: "default",
  },
]);
