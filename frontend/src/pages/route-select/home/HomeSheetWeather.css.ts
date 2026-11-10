import { globalStyle, keyframes, style } from "@vanilla-extract/css";
import { spinning } from "@/shared/components/RetryButton.css";
import { vars } from "@/shared/styles/tokens.css";
import { sheetSurface } from "./HomeScreen.css";

const shortViewport = "(max-height: 600px)";

export const overview = style({
  display: "flex",
  flexDirection: "column",
  gap: 22,
  paddingBlockStart: 3,
  "@media": { "(max-height: 720px)": { gap: 12 } },
});

export const panel = style({ minWidth: 0 });

export const weatherHeader = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 8,
});

export const weatherLocation = style({ display: "flex", alignItems: "center", gap: 6, minWidth: 0 });

export const refresh = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 24,
  height: 24,
  padding: 0,
  border: 0,
  borderRadius: "50%",
  background: "transparent",
  cursor: "pointer",
  selectors: {
    "&:disabled": { opacity: 0.5, cursor: "default" },
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: 2 },
  },
});

export const refreshIcon = style({ opacity: 0.65 });

export const refreshSpinning = style([
  refreshIcon,
  spinning,
  { selectors: { [`${refresh}:not(:disabled) &`]: { animationIterationCount: 1 } } },
]);

export const hourly = style({
  display: "grid",
  gridTemplateColumns: "repeat(6, minmax(0, 1fr))",
  gap: 3,
  margin: "6px 0 0",
  padding: 0,
  listStyle: "none",
  "@media": { [shortViewport]: { marginBlockStart: 4 } },
});

export const forecastHour = style({
  display: "grid",
  justifyItems: "center",
  alignItems: "center",
  gap: 2,
  padding: "5px 0",
  borderRadius: 10,
  color: vars.color.textSubtle,
  fontSize: 11,
  lineHeight: "13px",
  fontVariantNumeric: "tabular-nums",
  whiteSpace: "nowrap",
  selectors: {
    '&[aria-current="time"]': { background: "#FFFFFF99", boxShadow: "inset 0 0 0 1px #FFFFFFB3" },
  },
  "@media": {
    "(max-height: 720px)": { gap: 1, padding: "3px 0", lineHeight: "11px" },
    "(max-width: 359px)": { fontSize: 10 },
  },
});

export const greeting = style({
  margin: 0,
  color: vars.color.textStrong,
  fontSize: 18,
  lineHeight: "22px",
  fontWeight: 700,
  overflowWrap: "anywhere",
  wordBreak: "keep-all",
  "@media": {
    "(max-height: 720px)": { fontSize: 17, lineHeight: "20px" },
    [shortViewport]: { fontSize: 16, lineHeight: "18px" },
  },
});

export const greetingName = style({ display: "block" });

const skyColors = {
  sun: "#FFEBCE",
  night: "#E9E4FA",
  cloud: "#E9EFF6",
  fog: "#E5EFEC",
  rain: "#DFEDFE",
  snow: "#E1F2FA",
  thunder: "#E4E7F5",
};

for (const [weather, color] of Object.entries(skyColors)) {
  globalStyle(`${sheetSurface}:has(${panel}[data-weather="${weather}"])`, {
    backgroundImage: `linear-gradient(180deg, ${color}, ${vars.color.surface} 190px)`,
  });
}

export const reading = style({
  position: "relative",
  display: "flex",
  alignItems: "center",
  gap: 7,
  minHeight: 82,
  color: vars.color.textSubtle,
  fontSize: 11,
  lineHeight: 1.35,
  wordBreak: "keep-all",
  selectors: {
    '&[aria-busy="true"]::after': {
      content: '""',
      position: "absolute",
      left: 0,
      top: "50%",
      transform: "translateY(-50%)",
      width: 110,
      height: 8,
      borderRadius: 999,
      background: vars.color.rowDivider,
    },
  },
});

export const temperature = style({ color: vars.color.textStrong, fontSize: 13, fontWeight: 700 });
export const rainProbability = style({
  display: "inline-flex",
  alignItems: "center",
  gap: 2,
  color: "#245279",
  fontSize: 10,
  "@media": { "(max-width: 359px)": { fontSize: 9, gap: 1 } },
});

export const retry = style({
  flexShrink: 0,
  border: 0,
  background: "none",
  color: vars.color.textStrong,
  textDecoration: "underline",
  fontSize: 11,
  cursor: "pointer",
});

const turn = keyframes({ to: { transform: "rotate(32deg)" } });
const drift = keyframes({ to: { transform: "translateX(3px)" } });
const twinkle = keyframes({ to: { opacity: 0.48 } });
const fall = keyframes({
  from: { opacity: 0, transform: "translateY(-2px)" },
  "32%": { opacity: 1 },
  to: { opacity: 0, transform: "translateY(7px)" },
});
export const forecastArt = style({
  width: 24,
  height: 24,
  textAlign: "center",
  "@media": {
    "(max-height: 720px)": { width: 20, height: 20 },
    [shortViewport]: { width: 17, height: 17 },
  },
});
export const sunRays = style({ transformOrigin: "40px 33px", animation: `${turn} 9s ease-in-out infinite alternate` });
export const clouds = style({ animation: `${drift} 4.5s ease-in-out infinite alternate` });
export const stars = style({ animation: `${twinkle} 2.8s ease-in-out infinite alternate` });
export const precipitation = style({ animation: `${fall} 2s ease-in-out infinite` });
globalStyle(`${forecastHour}:not([aria-current="time"]) ${forecastArt} *`, { animation: "none" });
globalStyle(`${forecastArt} *`, {
  "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
});

export const areaLabel = style({
  display: "block",
  margin: 0,
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
  fontSize: 12,
  lineHeight: 1.35,
  fontWeight: 650,
  color: vars.color.textSubtle,
});

export const credit = style({
  flexShrink: 0,
  color: vars.color.textSubtle,
  fontSize: 9,
  lineHeight: 1.3,
});
