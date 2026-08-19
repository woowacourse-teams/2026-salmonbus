import { createGlobalTheme } from "@vanilla-extract/css";

export const vars = createGlobalTheme(":root", {
  color: {
    background: "#F8FAFC",
    surface: "#FFFFFF",
    cardBorder: "#DFE3E8",
    segmentTrack: "#EDF0F3",
    textStrong: "#1B1E23",
    textMuted: "#8A919B",
    line: "#DFE3E8",
    accent: "#E53D4D",
    seatHigh: "#237B55",
    seatHighSoft: "#E7F4ED",
    seatHighTint: "#F1F9F4",
    seatLow: "#A85A00",
    seatLowSoft: "#FBF0DC",
    seatLowTint: "#FCF6EA",
    seatVeryLow: "#C62C40",
    seatVeryLowSoft: "#FBE9EA",
    seatVeryLowTint: "#FDF2F3",
    passThroughDot: "#C9CFD6",
  },
  font: {
    label: "15px",
    control: "10px",
    controlTight: "8.75px",
    caption: "9px",
  },
  radius: {
    control: "11px",
    card: "15px",
    screen: "26px",
  },
});
