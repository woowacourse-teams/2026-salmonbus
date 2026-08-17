import { style } from "@vanilla-extract/css";

export const layout = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: "16px",
  paddingTop: "80px",
});

export const counterButton = style({
  padding: "8px 20px",
  border: "1px solid #d0d0d0",
  borderRadius: "8px",
  backgroundColor: "#fff",
  fontSize: "16px",
  cursor: "pointer",
  ":hover": {
    backgroundColor: "#f5f5f5",
  },
});
