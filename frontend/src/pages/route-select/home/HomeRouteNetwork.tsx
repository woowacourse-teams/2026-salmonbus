import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import type { NaverCoordinate, NaverMap, NaverMaps } from "@/shared/map/naverSdk";
import { activeRouteColor } from "@/shared/map/routeColors";
import { DEFAULT_LOCATION, type Point } from "./homeMapData";
import { homeRouteRuns } from "./homeRouteRuns";
import * as styles from "./HomeScreen.css";

export function HomeRouteNetwork({ map, maps, paths }: { map: NaverMap; maps: NaverMaps; paths: Point[][] }) {
  const svg = useRef<SVGSVGElement>(null);
  const path = useRef<SVGPathElement>(null);
  const strokes = useRef<SVGGElement>(null);
  const revealStarted = useRef(false);
  const id = useId();

  useEffect(() => {
    const layer = svg.current;
    const geometry = path.current;
    const lines = strokes.current;
    if (!layer || !geometry || !lines) return;
    const coordinatesFor = (points: Point[]) =>
      points.map(([longitude, latitude]) => new maps.LatLng(latitude, longitude));
    const runs = homeRouteRuns(paths).map(coordinatesFor);
    const [longitude, latitude] = DEFAULT_LOCATION;
    const pangyo = new maps.LatLng(latitude, longitude);
    let frame = 0;
    const draw = () => {
      frame = 0;
      if (window.naver?.maps !== maps) return;
      const projection = map.getProjection();
      if (!projection) return;
      const { width, height } = map.getSize();
      // 지도 SDK가 이동 중 레이어를 옮기므로 현재 화면의 원점을 레이어 좌표로 계산한다.
      const center = projection.fromCoordToOffset(map.getCenter());
      const left = center.x - width / 2;
      const top = center.y - height / 2;
      layer.style.left = `${left}px`;
      layer.style.top = `${top}px`;
      layer.setAttribute("width", String(width));
      layer.setAttribute("height", String(height));
      const pathFor = (coordinates: NaverCoordinate[]) =>
        coordinates
          .map((coordinate, index) => {
            const { x, y } = projection.fromCoordToOffset(coordinate);
            return `${index === 0 ? "M" : "L"}${(x - left).toFixed(1)} ${(y - top).toFixed(1)}`;
          })
          .join("");
      geometry.setAttribute("d", runs.map(pathFor).join(""));
      if (!revealStarted.current && runs.length > 0 && width > 0 && height > 0) {
        revealStarted.current = true;
        if (window.matchMedia("(prefers-reduced-motion: no-preference)").matches)
          lines.classList.add(styles.routeReveal);
      }
      if (lines.classList.contains(styles.routeReveal)) {
        const origin = projection.fromCoordToOffset(pangyo);
        const x = origin.x - left;
        const y = origin.y - top;
        const bounds = geometry.getBBox();
        const radius = Math.hypot(
          Math.max(Math.abs(bounds.x - x), Math.abs(bounds.x + bounds.width - x)),
          Math.max(Math.abs(bounds.y - y), Math.abs(bounds.y + bounds.height - y)),
        );
        lines.style.setProperty(styles.routeRevealCenter, `${x}px ${y}px`);
        lines.style.setProperty(styles.routeRevealRadius, `${radius + 4}px`);
      }
    };
    const redraw = () => {
      if (frame === 0) frame = requestAnimationFrame(draw);
    };
    const listeners = ["bounds_changed", "projection_changed", "size_changed", "idle"].map((event) =>
      maps.Event.addListener(map, event, redraw),
    );
    draw();
    return () => {
      cancelAnimationFrame(frame);
      if (window.naver?.maps === maps) listeners.forEach((listener) => maps.Event.removeListener(listener));
    };
  }, [map, maps, paths]);

  return createPortal(
    <svg
      ref={svg}
      className={styles.routeNetwork}
      data-testid="home-route-network"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <path id={`${id}-roads`} ref={path} />
        <mask id={`${id}-mask`} maskUnits="userSpaceOnUse" x="0" y="0" width="100%" height="100%">
          <use href={`#${id}-roads`} fill="none" stroke="white" strokeWidth="1.5" />
        </mask>
        <linearGradient id={`${id}-glow`} x2="100%" y2="18%">
          <stop offset="0" stopColor="#FFF0DE" stopOpacity="0" />
          <stop offset=".26" stopColor="#FFF0DE" stopOpacity=".18" />
          <stop offset=".5" stopColor="#FFF9EF" stopOpacity=".58" />
          <stop offset=".74" stopColor="#FFF0DE" stopOpacity=".18" />
          <stop offset="1" stopColor="#FFF0DE" stopOpacity="0" />
        </linearGradient>
      </defs>
      <g
        ref={strokes}
        onAnimationEnd={(event) => {
          if (event.target === event.currentTarget) event.currentTarget.classList.remove(styles.routeReveal);
        }}
      >
        <use href={`#${id}-roads`} fill="none" stroke="#FFFDFB" strokeWidth="6.6" opacity=".72" />
        <use href={`#${id}-roads`} fill="none" stroke={activeRouteColor} strokeWidth="4.8" opacity=".95" />
        <g mask={`url(#${id}-mask)`}>
          <rect className={styles.routeNetworkGlow} width="20%" height="100%" fill={`url(#${id}-glow)`} />
        </g>
      </g>
    </svg>,
    map.getPanes().overlayLayer,
  );
}
