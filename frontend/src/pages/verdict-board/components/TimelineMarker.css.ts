import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const track = style({
  gridColumn: 1,
  gridRow: "1 / -1",
  justifySelf: "end",
  alignSelf: "stretch",
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  width: vars.layout.ring,
});

const line = style({
  width: "1px",
  backgroundColor: vars.color.axisLine,
});

const hiddenLine = style([line, { backgroundColor: "transparent" }]);

const headHeight = {
  stop: `calc((${vars.layout.headHeight} - ${vars.layout.ring}) / 2)`,
  waypoint: `calc((${vars.layout.compactHeight} - ${vars.layout.ringCompact}) / 2)`,
};

export const headSegment = styleVariants(headHeight, (height) => [line, { flex: "none", height }]);

export const hiddenHeadSegment = styleVariants(headHeight, (height) => [hiddenLine, { flex: "none", height }]);

export const tailSegment = style([line, { flex: 1 }]);

export const hiddenTailSegment = style([hiddenLine, { flex: 1 }]);

const ringBase = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  flex: "none",
  boxSizing: "border-box",
  borderStyle: "solid",
  borderWidth: "2px",
  borderRadius: "50%",
});

const stopRing = style([ringBase, { width: vars.layout.ring, height: vars.layout.ring }]);

const seatRing = styleVariants(vars.color.seat, (tone) => [
  stopRing,
  {
    borderColor: tone.ring,
    backgroundColor: tone.surface,
  },
]);

const noForecastRing = style([
  stopRing,
  {
    borderColor: vars.color.noForecastRing,
    backgroundColor: vars.color.surface,
  },
]);

const waypointRing = style([
  ringBase,
  {
    width: vars.layout.ringCompact,
    height: vars.layout.ringCompact,
    borderColor: vars.color.faint,
    backgroundColor: vars.color.surface,
  },
]);

export const ring = { ...seatRing, noForecast: noForecastRing, passThrough: waypointRing };

const headingBase = style({
  display: "block",
  flex: "none",
});

const seatHeading = styleVariants(vars.color.seat, (tone) => [headingBase, { color: tone.ring }]);

export const heading = { ...seatHeading, noForecast: style([headingBase, { color: vars.color.noForecastRing }]) };
