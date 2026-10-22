import { style } from "@vanilla-extract/css";

// display: none이나 visibility: hidden이면 iOS가 포커스를 주지 않는다. 16px보다 작으면 iOS가 화면을 확대한다.
export const holder = style({
  position: "fixed",
  top: 0,
  left: 0,
  width: "1px",
  height: "1px",
  padding: 0,
  border: 0,
  opacity: 0,
  fontSize: "16px",
  pointerEvents: "none",
});
