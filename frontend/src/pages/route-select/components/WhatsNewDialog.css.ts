import { style } from "@vanilla-extract/css";
import { POINTER_INPUT } from "@/shared/input-method/inputMethod";
import { vars } from "@/shared/styles/tokens.css";

export const dialog = style({
  selectors: {
    "&&": {
      maxWidth: 356,
      maxHeight: "min(560px, calc(100% - 64px))",
      padding: "22px 20px 14px",
      background: "white",
      boxShadow: "0 12px 36px #19151226",
      fontFamily: "'Noto Sans KR', sans-serif",
    },
    // 닫힌 dialog에 display를 주면 열기 전에 화면에 보이므로 열렸을 때만 세로로 쌓는다.
    "&&[open]": { display: "flex", flexDirection: "column" },
  },
});

export const title = style({
  selectors: {
    "&&": { margin: "0 0 14px", fontSize: 20, fontWeight: 700, lineHeight: 1.4, letterSpacing: "-0.5px" },
  },
});

export const notes = style({
  display: "grid",
  alignContent: "start",
  gap: 20,
  flex: "1 1 auto",
  minHeight: 0,
  overflowY: "auto",
  selectors: { [`${POINTER_INPUT} &:focus`]: { outline: "none" } },
});

export const noteDate = style({ margin: 0, color: vars.color.textSubtle, fontSize: 12, fontWeight: 600 });

export const sectionTitle = style({ margin: "8px 0 6px", color: vars.color.textBody, fontSize: 13, fontWeight: 700 });

export const features = style({
  display: "grid",
  gap: 6,
  margin: 0,
  paddingLeft: 18,
  color: vars.color.textStrong,
  fontSize: 14,
  lineHeight: 1.5,
  wordBreak: "keep-all",
});

export const actions = style({ display: "grid", gap: 4, flex: "none", marginTop: 18 });

export const stopButton = style({ justifySelf: "center" });
