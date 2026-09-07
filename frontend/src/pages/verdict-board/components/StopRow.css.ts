import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const ROW_PADDING_RIGHT = "18px";
const AXIS_TO_NAME = `calc(${vars.layout.nameX} - ${vars.layout.axisX})`;
const HEAD_GAP = "8px";
const WAYPOINT_GAP = "2px";
const HEAD_PADDING_TOP = "14px";
const CHEVRON_SIZE = "18px";
const NAME_TO_CHEVRON = "4px";
const CHIP_HEIGHT = "19px";
// 예보 카드는 이름 아래와 행 바닥 사이 정중앙에 온다. 위 여백은 openHeadHeight 안에 들어 있다.
const DETAIL_PADDING_BOTTOM = "13.5px";
const GROW = `${vars.duration.spring} ${vars.easing.slide}`;

const rowBase = style({
  position: "relative",
  display: "grid",
  gridTemplateColumns: `calc(${vars.layout.axisX} + ${vars.layout.ring} / 2) minmax(0, 1fr)`,
  gridTemplateRows: "auto auto",
  columnGap: `calc(${vars.layout.nameX} - ${vars.layout.axisX} - ${vars.layout.ring} / 2)`,
  paddingRight: ROW_PADDING_RIGHT,
  selectors: {
    // 시안의 구분선은 노선 축에서 시작한다. 왼쪽 끝까지 그으면 축 왼쪽에 네모 칸이 생긴다.
    "&:not(:last-child)::after": {
      content: '""',
      position: "absolute",
      right: 0,
      bottom: 0,
      left: vars.layout.axisX,
      height: "1px",
      backgroundColor: vars.color.rowDivider,
    },
  },
});

export const stopRow = style([rowBase, { backgroundColor: vars.color.surface }]);

export const waypointRow = style([
  rowBase,
  {
    selectors: {
      "&::before": {
        content: '""',
        gridColumn: 2,
        gridRow: "1 / -1",
        marginRight: `calc(-1 * ${ROW_PADDING_RIGHT})`,
        marginLeft: `calc(-1 * ${AXIS_TO_NAME})`,
        backgroundColor: vars.color.compactRowSurface,
      },
    },
  },
]);

const stack = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "flex-start",
  justifyContent: "center",
  minWidth: 0,
});

const headBase = style({
  position: "relative",
  gridColumn: 2,
  gridRow: 1,
  display: "flex",
  alignItems: "flex-start",
  width: "100%",
  padding: `${HEAD_PADDING_TOP} calc(${CHEVRON_SIZE} + ${NAME_TO_CHEVRON}) 0 0`,
  border: "none",
  background: "none",
  fontFamily: "inherit",
  textAlign: "left",
  cursor: "pointer",
  transition: `min-height ${GROW}`,
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },
  selectors: {
    "&:focus-visible": {
      outline: `2px solid ${vars.color.focusRing}`,
      outlineOffset: "-2px",
    },
  },
});

// 펼쳐도 정류장명이 제자리에 있도록 머리 영역을 위로 붙이고 높이만 바꾼다.
export const head = styleVariants({
  collapsed: [headBase, { minHeight: vars.layout.headHeight }],
  expanded: [headBase, { minHeight: vars.layout.openHeadHeight }],
});

export const headStack = style([stack, { flex: 1, gap: HEAD_GAP }]);

const chipSlotBase = style({
  display: "flex",
  overflow: "hidden",
  transition: `max-height ${GROW}, opacity ${vars.duration.fast} ease`,
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },
});

export const chipSlot = styleVariants({
  shown: [chipSlotBase, { maxHeight: CHIP_HEIGHT, opacity: 1 }],
  hidden: [chipSlotBase, { maxHeight: 0, opacity: 0 }],
});

export const waypointHead = style([
  stack,
  {
    gridColumn: 2,
    gridRow: 1,
    gap: WAYPOINT_GAP,
    minHeight: vars.layout.compactHeight,
  },
]);

export const caption = style({
  color: vars.color.faint,
  fontSize: vars.font.caption,
  fontWeight: 500,
  lineHeight: vars.leading.stopName,
});

// 흐름 안에 두면 펼쳤을 때 머리 높이를 셰브론이 밀어 올린다.
const chevronBase = style({
  position: "absolute",
  // 접힘 행 높이의 정중앙에 두고, 펼쳐도 그 자리를 지킨다.
  top: `calc((${vars.layout.headHeight} - ${CHEVRON_SIZE}) / 2)`,
  right: 0,
  color: vars.color.iconMuted,
  transition: "transform 160ms ease",
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },
});

// 기본 path 가 아래 방향이라 회전으로 방향을 만든다.
const chevronRotation = {
  right: "-90deg",
  up: "180deg",
};

export const chevron = styleVariants(chevronRotation, (rotation) => [
  chevronBase,
  { transform: `rotate(${rotation})` },
]);

// 0fr 에서 1fr 로 움직이면 내용 높이를 몰라도 늘어나는 과정을 그릴 수 있다.
const detailBase = style({
  gridColumn: 2,
  gridRow: 2,
  display: "grid",
  transition: `grid-template-rows ${GROW}, padding-bottom ${GROW}`,
  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },
});

// 아래 여백은 잘리는 쪽이 아니라 바깥에 둔다. border-box 는 자기 패딩보다 작아지지 못한다.
export const detail = styleVariants({
  closed: [detailBase, { gridTemplateRows: "0fr", paddingBottom: 0 }],
  open: [detailBase, { gridTemplateRows: "1fr", paddingBottom: DETAIL_PADDING_BOTTOM, cursor: "pointer" }],
});

export const detailInner = style({
  overflow: "hidden",
  minHeight: 0,
});
