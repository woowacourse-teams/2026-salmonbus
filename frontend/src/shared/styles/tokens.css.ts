import { createGlobalTheme } from "@vanilla-extract/css";

export const vars = createGlobalTheme(":root", {
  color: {
    background: "#F7F7F5",
    surface: "#FFFFFF",
    cardBorder: "#D8D5CF",
    ink: "#111111",
    onInk: "#FFFFFF",
    textStrong: "#151515",
    textBody: "#454545",
    textSubtle: "#6B6A6A",
    textMuted: "#5B6472",
    iconMuted: "#8A8781",
    faint: "#AAB2BC",
    focusRing: "#E53D4D",

    axisLine: "#B8B4AC",
    rowDivider: "#E2DFD8",
    compactRowSurface: "#F7F8FA",
    mutedChipSurface: "#EDEDEB",
    forecastCardSurface: "#F7F7F5",
    forecastCardBorder: "#E3E2DE",
    noForecastRing: "#000000",
    liveBus: "#C93C32",

    remainingSeat: {
      surface: "#FFFFFF",
      border: "#000000",
      label: "#6B6A6A",
      value: "#000000",
    },

    seat: {
      high: { ring: "#6F9276", surface: "#DCE8DE", text: "#31573A" },
      low: { ring: "#C3A34C", surface: "#EEE6C9", text: "#6D5B1D" },
      veryLow: { ring: "#B9655E", surface: "#E9D7D5", text: "#7D352F" },
    },
  },
  font: {
    routeName: "21px",
    stopName: "15.5px",
    stopNameCompact: "13px",
    distance: "14px",
    seat: "13.5px",
    tab: "14px",
    verdict: "11.5px",
    caption: "10.5px",
    cardNotice: "13.5px",
    boardNotice: "14px",
    remainingSeatLabel: "7px",
    remainingSeatValue: "11.5px",
  },
  leading: {
    stopName: "1.35",
  },
  tracking: {
    stopName: "-0.25px",
  },
  radius: {
    badge: "4px",
    control: "10px",
    forecastCard: "10px",
    seatBadge: "12px",
    remainingSeat: "6px",
    card: "10px",
    pill: "16px",
  },
  easing: {
    slide: "cubic-bezier(0.32, 0.72, 0, 1)",
  },
  duration: {
    fast: "150ms",
    base: "200ms",
    spring: "260ms",
    elastic: "340ms",
  },
  layout: {
    axisX: "61px",
    nameX: "83px",
    headHeight: "76px",
    openHeadHeight: "48.5px",
    compactHeight: "48px",
    ring: "12px",
    ringCompact: "10px",
  },
});
