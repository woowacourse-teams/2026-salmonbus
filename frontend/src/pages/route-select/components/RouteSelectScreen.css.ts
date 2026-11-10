import { globalStyle, style } from "@vanilla-extract/css";
import { POINTER_INPUT } from "@/shared/input-method/inputMethod";
import { PRESS_IN, PRESS_OUT, PRESSED_SCALE } from "@/shared/styles/press";
import { vars } from "@/shared/styles/tokens.css";
import { tabSheetBody } from "../home/HomeScreen.css";

export const searchEntry = style({
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr) auto",
  flex: 1,
  minWidth: 0,
  alignItems: "center",
  gap: 5,
  height: "100%",
  cursor: "text",
  // 평소에도 scale을 두어야 누르는 순간 stacking context가 새로 생기지 않는다.
  scale: "1",
  selectors: {
    "&:active": { scale: `${PRESSED_SCALE}` },
  },
  // 전환은 도착하는 상태의 값을 쓴다. 누를 때는 :active 쪽, 뗄 때는 기본 쪽 곡선이 돈다.
  "@media": {
    "(prefers-reduced-motion: no-preference)": {
      transition: PRESS_OUT,
      selectors: {
        "&:active": { transition: PRESS_IN },
      },
    },
  },
});

export const searchEntryText = style({
  gridArea: "1 / 1",
  width: "100%",
  minWidth: 0,
  height: "100%",
  padding: 0,
  border: "none",
  outline: "none",
  background: "transparent",
  // 16px보다 작으면 iOS가 포커스할 때 화면을 확대한다.
  fontSize: "16px",
  color: vars.color.textStrong,
  "::placeholder": { opacity: 0 },
  selectors: {
    "&:focus-visible": { outline: "2px solid #8C3C30", outlineOffset: -2, borderRadius: 8 },
    [`${POINTER_INPUT} &:focus`]: { outline: "none" },
  },
});

export const searchEntryPrompt = style({
  gridArea: "1 / 1",
  fontSize: 12.5,
  lineHeight: 1.25,
  letterSpacing: "-0.25px",
  color: vars.color.textSubtle,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
  pointerEvents: "none",
  selectors: { [`${searchEntryText}:not(:placeholder-shown) + &`]: { display: "none" } },
  "@media": {
    "(min-width: 420px)": { fontSize: 13 },
    "(max-width: 389px)": { fontSize: 12 },
    "(max-width: 374px)": { fontSize: 11.25, letterSpacing: "-0.15px" },
  },
});

export const searchIcon = style({
  gridArea: "1 / 2",
  flexShrink: 0,
  width: 18,
  height: 18,
  fill: "none",
  stroke: "#000000",
  strokeWidth: 1.9,
  strokeLinecap: "round",
});
export const centeredGuestFavorites = style({
  display: "grid",
  minHeight: "100%",
  paddingBlockEnd: 36,
  alignItems: "center",
});
export const favoritesSheetPage = style({
  display: "flex",
  flexDirection: "column",
  minHeight: "100%",
});
globalStyle(`${tabSheetBody}:has(> ${favoritesSheetPage})`, { scrollbarGutter: "stable both-edges" });
