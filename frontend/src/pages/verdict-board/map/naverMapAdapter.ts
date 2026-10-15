import type { Board, Direction } from "@/shared/api/routeForecast.types";
import busFrontIcon from "./bus-front.svg";
import type { StopSelection } from "../components/stopSelection";
import type { LiveVehicleView } from "../components/liveVehiclePolicy";
import type { GeoCoordinate, RouteMapData } from "./routeMapModel";
import { routeArrowsFor, routeMapCoordinateForSequence } from "./routeMapGeometry";
import type { NaverMap, NaverMaps, NaverMarker } from "./naverSdk";
import * as styles from "./RouteMap.css";

interface StopMarker {
  selection: StopSelection;
  coordinate: GeoCoordinate;
  marker: NaverMarker;
  button: HTMLButtonElement;
  label: HTMLSpanElement;
}
interface VehicleMarker {
  marker: NaverMarker;
  element: HTMLElement;
  count: HTMLElement;
  direction: Direction;
}

export interface RouteMapAdapter {
  update: (selected: StopSelection | null, activeDirection: Direction, vehicles: readonly LiveVehicleView[]) => void;
  moveVehicles: (frames: readonly { key: string; sequencePosition: number }[]) => void;
  resize: (width: number, height: number) => void;
  focus: (selection: StopSelection) => void;
  showLocation: (coordinate: GeoCoordinate, accuracy: number) => void;
  destroy: () => void;
}

export function createNaverRouteMap(
  sdk: NaverMaps,
  element: HTMLElement,
  board: Board,
  data: RouteMapData,
  onSelect: (selection: StopSelection) => void,
  onError: () => void,
): RouteMapAdapter {
  const createNativeEventHandler = (action: () => void) => () => {
    try {
      action();
    } catch {
      onError();
    }
  };
  const first = data.stops[0]?.coordinate;
  if (first === undefined) throw new Error("지도 좌표가 없어요");
  const toNaverCoordinate = (coordinate: GeoCoordinate) => new sdk.LatLng(coordinate.latitude, coordinate.longitude);
  const map = new sdk.Map(element, {
    center: toNaverCoordinate(first),
    zoom: 11,
    size: new sdk.Size(element.clientWidth, element.clientHeight),
    zoomControl: false,
    scaleControl: false,
    mapDataControl: false,
  });
  let selected: StopSelection | null = null;
  let activeDirection: Direction = board.route.directions[0].id;
  function routePathStyleFor(direction: Direction) {
    const active = direction === activeDirection;
    return {
      strokeColor: active ? "#ED7768" : "#5B6472",
      strokeOpacity: 0.95,
      zIndex: active ? 2 : 1,
    };
  }
  const paths = data.paths.map((path) => ({
    direction: path.direction,
    line: new sdk.Polyline({
      map,
      path: path.coordinates.map(toNaverCoordinate),
      strokeWeight: 6,
      ...routePathStyleFor(path.direction),
    }),
  }));
  let arrows: NaverMarker[] = [];
  let userMarker: NaverMarker | null = null;
  let accuracyCircle: { setMap(map: NaverMap | null): void } | null = null;
  const vehicles = new Map<string, VehicleMarker>();
  const stops: StopMarker[] = data.stops.flatMap((stop) => {
    const stopState = board.stops.find((item) => item.sequence === stop.sequence && item.direction === stop.direction);
    if (stopState === undefined) return [];
    const selection = { direction: stop.direction, sequence: stop.sequence };
    const terminalStopName =
      board.route.directions.find((direction) => direction.id === stop.direction)?.terminalStopName ??
      (stop.direction === "UP" ? board.route.endStopName : board.route.startStopName);
    const button = document.createElement("button");
    button.type = "button";
    button.className = styles.stop;
    button.dataset.mapStopSequence = String(stop.sequence);
    button.setAttribute("aria-label", `${stopState.name} ${terminalStopName} 방면 정류장 지도에서 선택`);
    button.setAttribute("aria-pressed", "false");
    const symbol = document.createElement("span");
    symbol.className = styles.stopIcon;
    symbol.setAttribute("aria-hidden", "true");
    symbol.innerHTML = stopState.boardingAllowed
      ? '<svg width="100%" height="100%" viewBox="0 0 14 14"><path d="M0 0l0 6.1985" transform="translate(2.42578 3.63132) scale(1 1.4)" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" vector-effect="non-scaling-stroke"/><path d="M0 0l0 6.1985" transform="translate(11.56932 3.63132) scale(1 1.4)" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" vector-effect="non-scaling-stroke"/><path d="M0.30955 0.091l-0.26775 0.4025c-0.10675 0.16625 0.00175 0.36225 0.203 0.36225l7.938 0c0.203 0 0.30975-0.196 0.20125-0.36225l-0.2695-0.4025c-0.0385-0.05775-0.11375-0.091-0.18725-0.091l-7.42875 0c-0.07525 0-0.1505 0.03325-0.189 0.091z" transform="translate(1.09326 2.4332) scale(1.4)" fill="currentColor"/><rect x="3.31016" y="9.68254" width="7.37142" height="1.2" rx=".6" fill="currentColor"/></svg>'
      : '<svg width="100%" height="100%" viewBox="0 0 16 16"><circle cx="8" cy="8" r="3" fill="currentColor"/></svg>';
    const label = document.createElement("span");
    label.className = styles.stopName;
    label.textContent = stopState.name;
    label.setAttribute("aria-hidden", "true");
    button.append(symbol, label);
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      createNativeEventHandler(() => focus(selection))();
      onSelect(selection);
    });
    const marker = new sdk.Marker({
      map,
      position: toNaverCoordinate(stop.coordinate),
      icon: { content: button, anchor: new sdk.Point(12, 12) },
      zIndex: 5,
      clickable: true,
    });
    return [{ selection, coordinate: stop.coordinate, marker, button, label }];
  });

  function refreshStops() {
    const zoom = map.getZoom();
    const projection = map.getProjection();
    const display = stops.map((stop) => ({
      ...stop,
      point: projection.fromCoordToOffset(toNaverCoordinate(stop.coordinate)),
      size: selected?.sequence === stop.selection.sequence && selected.direction === stop.selection.direction ? 32 : 24,
      offsetX: 0,
    }));
    // 마주 보는 정류장이 겹치면 화면상 선택 영역만 벌린다. 실제 정류장 좌표는 유지한다.
    for (const up of display.filter((stop) => stop.selection.direction === "UP")) {
      for (const down of display.filter((stop) => stop.selection.direction === "DOWN")) {
        const separation = (up.size + down.size) / 2 + 4;
        const difference = up.point.x - down.point.x;
        if (Math.abs(difference) >= separation || Math.abs(up.point.y - down.point.y) >= separation) continue;
        const offset = (separation - difference) / 2;
        up.offsetX = Math.max(up.offsetX, offset);
        down.offsetX = Math.min(down.offsetX, -offset);
      }
    }
    display.forEach(({ selection, marker, button, label, size, offsetX }) => {
      const isSelected = selected?.sequence === selection.sequence && selected.direction === selection.direction;
      const isActive = selection.direction === activeDirection;
      button.setAttribute("aria-pressed", String(isSelected));
      button.dataset.active = String(isActive);
      button.dataset.labelAbove = String(offsetX !== 0 && !isActive);
      label.hidden = zoom < 15;
      marker.setVisible(zoom >= 15);
      marker.setZIndex(isSelected ? 15 : selection.direction === activeDirection ? 6 : 5);
      marker.setIcon({
        content: button,
        anchor: new sdk.Point(size / 2 - offsetX, size / 2),
      });
    });
  }

  function refreshArrows() {
    arrows.forEach((marker) => marker.setMap(null));
    const viewport = map.getSize();
    arrows = data.paths.flatMap((path) =>
      routeArrowsFor(
        path.coordinates,
        (coordinate) => map.getProjection().fromCoordToOffset(toNaverCoordinate(coordinate)),
        viewport,
      ).map((arrow) => {
        const content = document.createElement("span");
        content.className = styles.arrow;
        content.setAttribute("aria-hidden", "true");
        content.innerHTML =
          '<svg width="14" height="14" viewBox="0 0 14 14"><path d="m4 3 5 4-5 4" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        content.style.transform = `rotate(${arrow.rotation}deg)`;
        return new sdk.Marker({
          map,
          position: toNaverCoordinate(arrow.coordinate),
          icon: { content, anchor: new sdk.Point(8, 8) },
          zIndex: 3,
          clickable: false,
        });
      }),
    );
  }

  function focus(selection: StopSelection) {
    const stop = data.stops.find(
      (item) => item.sequence === selection.sequence && item.direction === selection.direction,
    );
    if (stop === undefined) return;
    map.setCenter(toNaverCoordinate(stop.coordinate));
    map.setZoom(Math.max(16, map.getZoom()), false);
  }

  const listeners = [
    sdk.Event.addListener(map, "zoom_changed", createNativeEventHandler(refreshStops)),
    sdk.Event.addListener(
      map,
      "idle",
      createNativeEventHandler(() => {
        refreshStops();
        refreshArrows();
      }),
    ),
  ];
  const bounds = data.paths.flatMap((path) => path.coordinates);
  map.fitBounds((bounds.length > 0 ? bounds : data.stops.map((stop) => stop.coordinate)).map(toNaverCoordinate), {
    top: 28,
    right: 12,
    bottom: 28,
    left: 12,
    maxZoom: 14,
  });
  refreshStops();
  refreshArrows();

  return {
    update(nextSelected, nextDirection, nextVehicles) {
      selected = nextSelected;
      const directionChanged = activeDirection !== nextDirection;
      activeDirection = nextDirection;
      if (directionChanged) {
        paths.forEach(({ direction, line }) => line.setOptions(routePathStyleFor(direction)));
      }
      refreshStops();
      const visibleKeys = new Set(nextVehicles.map((view) => view.key));
      for (const [key, item] of vehicles) {
        if (visibleKeys.has(key)) continue;
        item.marker.setMap(null);
        vehicles.delete(key);
      }
      nextVehicles.forEach((view) => {
        const coordinate = routeMapCoordinateForSequence(
          data,
          view.vehicle.currentStopSequence,
          view.vehicle.direction,
        );
        if (coordinate === null) return;
        let item = vehicles.get(view.key);
        if (item === undefined) {
          const content = document.createElement("span");
          content.className = styles.bus;
          content.setAttribute("role", "img");
          const count = document.createElement("span");
          count.className = styles.busCount;
          count.setAttribute("aria-hidden", "true");
          const pin = document.createElement("span");
          pin.className = styles.busPin;
          pin.setAttribute("aria-hidden", "true");
          pin.innerHTML =
            '<svg width="26" height="32" viewBox="0 0 40 44" preserveAspectRatio="none" overflow="visible" aria-hidden="true"><path d="M20 0c-10 0-17 8-17 18 0 11 17 26 17 26 0 0 17-15 17-26 0-10-7-18-17-18z" fill="currentColor" stroke="white" stroke-width="1" vector-effect="non-scaling-stroke"/></svg>';
          const icon = document.createElement("img");
          icon.className = styles.busIcon;
          icon.src = busFrontIcon;
          icon.alt = "";
          pin.append(icon);
          content.append(count, pin);
          const marker = new sdk.Marker({
            map,
            position: toNaverCoordinate(coordinate),
            icon: { content, anchor: new sdk.Point(20, 52) },
            zIndex: 20,
            clickable: false,
          });
          item = {
            marker,
            element: content,
            count,
            direction: view.vehicle.direction,
          };
          vehicles.set(view.key, item);
        }
        item.direction = view.vehicle.direction;
        const seat = view.vehicle.seat;
        const count = seat.kind === "EXACT" ? `${seat.remaining}석` : "–";
        if (item.count.textContent !== count) item.count.textContent = count;
        const label = `${view.vehicle.stopName} 부근 버스, ${seat.kind === "EXACT" ? `현재 잔여 좌석 ${seat.remaining}석` : "현재 잔여 좌석 정보 없음"}`;
        if (item.element.getAttribute("aria-label") !== label) item.element.setAttribute("aria-label", label);
      });
    },
    moveVehicles(frames) {
      const groups = new Map<string, { item: VehicleMarker; coordinate: GeoCoordinate }[]>();
      frames.forEach((frame) => {
        const item = vehicles.get(frame.key);
        if (item === undefined) return;
        const coordinate = routeMapCoordinateForSequence(data, frame.sequencePosition, item.direction);
        if (coordinate === null) return;
        const key = `${coordinate.latitude}:${coordinate.longitude}`;
        const group = groups.get(key) ?? [];
        group.push({ item, coordinate });
        groups.set(key, group);
      });
      groups.forEach((group) =>
        group.forEach(({ item, coordinate }, index) => {
          item.marker.setPosition(toNaverCoordinate(coordinate));
          // 핀 끝은 경로 위에 두고, 같은 위치의 차량은 잔여석만 위로 쌓아 구분한다.
          item.count.style.transform = `translateY(${-index * 20}px)`;
        }),
      );
    },
    resize(width, height) {
      if (width <= 0 || height <= 0) return;
      const size = map.getSize();
      if (size.width === width && size.height === height) return;
      map.setSize(new sdk.Size(width, height));
      refreshStops();
      refreshArrows();
    },
    focus,
    showLocation(coordinate, accuracy) {
      userMarker?.setMap(null);
      accuracyCircle?.setMap(null);
      const position = toNaverCoordinate(coordinate);
      const content = document.createElement("span");
      content.className = styles.userPosition;
      content.setAttribute("role", "img");
      content.setAttribute("aria-label", "내 현재 위치");
      userMarker = new sdk.Marker({
        map,
        position,
        icon: { content, anchor: new sdk.Point(25, 25) },
        zIndex: 25,
        clickable: false,
      });
      accuracyCircle = new sdk.Circle({
        map,
        center: position,
        radius: Math.max(0, accuracy),
        fillColor: "#3686F5",
        fillOpacity: 0.15,
        strokeColor: "#3686F5",
        strokeOpacity: 0.25,
        strokeWeight: 1,
        zIndex: 4,
      });
      map.setCenter(position);
      map.setZoom(Math.max(16, map.getZoom()), false);
    },
    destroy() {
      const cleanup = (action: () => void) => {
        try {
          action();
        } catch {
          // 인증 실패로 SDK 내부가 비워진 경우에도 나머지 자원을 정리한다.
        }
      };
      listeners.forEach((listener) => cleanup(() => sdk.Event.removeListener(listener)));
      stops.forEach(({ marker }) => cleanup(() => marker.setMap(null)));
      vehicles.forEach(({ marker }) => cleanup(() => marker.setMap(null)));
      arrows.forEach((marker) => cleanup(() => marker.setMap(null)));
      paths.forEach(({ line }) => cleanup(() => line.setMap(null)));
      cleanup(() => userMarker?.setMap(null));
      cleanup(() => accuracyCircle?.setMap(null));
      cleanup(() => map.destroy());
    },
  };
}
