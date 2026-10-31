import { style } from "@vanilla-extract/css";
import { menuRow } from "./menuRow.css";

export const title = style({ margin: "0 0 28px", fontSize: 25, fontWeight: 700 });

export const nickname = style([menuRow, { minHeight: 32, fontSize: 22, fontWeight: 700, cursor: "auto" }]);

export const nicknameEdit = style({ display: "flex", flexShrink: 0, padding: 2, margin: -2 });

export const caption = style({ color: "#68645F", fontSize: 12, lineHeight: 1.7, margin: "8px 0 24px" });

export const error = style({ color: "#BA3529", fontSize: 13, lineHeight: 1.6 });

export const actions = style({ display: "flex", gap: 10, marginTop: 20 });
