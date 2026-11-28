import { style } from "@vanilla-extract/css";
import { text } from "@/shared/styles/button.css";
import { menuRow } from "./menuRow.css";

export const nickname = style([menuRow, { minHeight: 32, fontSize: 22, fontWeight: 700, cursor: "auto" }]);

export const nicknameEdit = style([
  text,
  {
    display: "grid",
    placeItems: "center",
    justifyItems: "end",
    flexShrink: 0,
    width: 36,
    height: 32,
    marginBlock: -6,
    padding: "6px 0 6px 8px",
  },
]);

export const caption = style({ color: "#68645F", fontSize: 12, lineHeight: 1.7, margin: "8px 0 24px" });

export const error = style({ color: "#BA3529", fontSize: 13, lineHeight: 1.6 });

export const actions = style({ display: "flex", gap: 10, marginTop: 20 });
