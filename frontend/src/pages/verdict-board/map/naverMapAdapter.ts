import type { Board, Direction } from "@/shared/api/routeForecast.types";
import busFrontIcon from "../assets/bus-front.svg";
import type { StopSelection } from "../components/stopSelection";
import type { LiveVehicleView } from "../components/liveVehiclePolicy";
import type { GeoCoordinate, RouteMapData } from "./routeMapModel";
import { routeArrowsFor, routeMapCoordinateForSequence, type RouteArrow } from "./routeMapGeometry";
import type { NaverMaps, NaverMarker } from "./naverSdk";
import * as styles from "./RouteMap.css";

const DETAIL_ZOOM = 14;
const INITIALIZATION_TIMEOUT_MS = 10_000;
const CAMERA_FOCUS_DURATION_MS = 560;

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
  selection: StopSelection;
  coordinate: GeoCoordinate;
}
interface ArrowMarker extends RouteArrow {
  direction: Direction;
  marker: NaverMarker;
  element: HTMLElement;
  visible: boolean;
}

export interface RouteMapAdapter {
  update: (
    selected: StopSelection | null,
    activeDirection: Direction,
    vehicles: readonly LiveVehicleView[],
    selectedVehicleKey?: string | null,
  ) => void;
  moveVehicles: (frames: readonly { key: string; sequencePosition: number }[]) => void;
  resize: (width: number, height: number) => void;
  focus: (selection: StopSelection, options?: { animate?: boolean }) => void;
  focusVehicle: (key: string | null, options?: { focusMarker?: boolean }) => void;
  showLocation: (coordinate: GeoCoordinate) => void;
  destroy: () => void;
}

export function createNaverRouteMap(
  sdk: NaverMaps,
  element: HTMLElement,
  board: Board,
  data: RouteMapData,
  visibleHeight: number,
  onSelect: (selection: StopSelection) => void,
  onError: () => void,
  onReady: () => void,
  onVehicleSelect: (selection: StopSelection, key: string) => void,
  onDeselect: () => void,
  initialSelection: StopSelection | null = null,
): RouteMapAdapter {
  const createNativeEventHandler = (action: () => void) => () => {
    try {
      action();
    } catch {
      onError();
    }
  };
  const mappedDirections = new Set(data.paths.map((path) => path.direction));
  const fullPath = data.fullPath ?? [];
  const initialStop = data.stops.find(
    (stop) => stop.direction === initialSelection?.direction && stop.sequence === initialSelection.sequence,
  );
  const first = initialStop?.coordinate ?? data.stops[0]?.coordinate ?? fullPath[0] ?? data.paths[0]?.coordinates[0];
  if (first === undefined) throw new Error("지도 좌표가 없어요");
  const toNaverCoordinate = (coordinate: GeoCoordinate) => new sdk.LatLng(coordinate.latitude, coordinate.longitude);
  const map = new sdk.Map(element, {
    center: toNaverCoordinate(first),
    zoom: initialStop === undefined ? 10 : 13,
    size: new sdk.Size(element.clientWidth, element.clientHeight),
    zoomControl: false,
    scaleControl: false,
    mapDataControl: false,
    // 벡터 지도의 연속 줌에서 노선·마커도 같은 카메라를 따라 움직인다.
    gl: true,
    // GL은 마커 좌표를 직접 갱신하므로 이미지 타일용 확대 효과를 중복 적용하지 않는다.
    overlayZoomEffect: null,
    scrollWheel: true,
    disableDoubleClickZoom: false,
  });
  let selected: StopSelection | null = initialSelection;
  let activeDirection: Direction = initialStop?.direction ?? board.route.directions[0].id;
  let initialFitPending = true;
  let initialized = false;
  let initialViewIdle = false;
  let initializationFailed = false;
  let routeRevealed = false;
  let readyNotified = false;
  let readyFrame: number | null = null;
  let destroyed = false;
  // GL 스타일 요청 실패는 SDK 인증 실패 콜백 없이 초기화를 중단할 수 있다.
  const initializationTimeout = window.setTimeout(() => {
    if (destroyed || readyNotified || initializationFailed) return;
    initializationFailed = true;
    cancelReadyFrame();
    onError();
  }, INITIALIZATION_TIMEOUT_MS);

  function cancelReadyFrame() {
    if (readyFrame === null) return;
    window.cancelAnimationFrame(readyFrame);
    readyFrame = null;
  }

  function notifyReadyAfterFrame() {
    if (
      destroyed ||
      initializationFailed ||
      readyNotified ||
      !initialized ||
      !initialViewIdle ||
      initialFitPending ||
      readyFrame !== null
    )
      return;
    readyFrame = window.requestAnimationFrame(
      createNativeEventHandler(() => {
        readyFrame = null;
        if (destroyed || initializationFailed || readyNotified) return;
        if (!routeRevealed) {
          routeRevealed = true;
          paths.forEach(({ direction, line }) => line.setOptions(routePathStyleFor(direction)));
          fullRouteLine?.setOptions({ strokeColor: "#5B6472", strokeOpacity: 0.95, zIndex: 1 });
          // 노선의 GPU 그리기를 먼저 예약한 뒤 HTML 마커와 버튼을 함께 공개한다.
          notifyReadyAfterFrame();
          return;
        }
        readyNotified = true;
        window.clearTimeout(initializationTimeout);
        onReady();
      }),
    );
  }
  function routePathStyleFor(direction: Direction) {
    const active = direction === activeDirection;
    return {
      strokeColor: active ? "#ED7768" : "#5B6472",
      strokeOpacity: routeRevealed ? 0.95 : 0,
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
  // 전체 형상만 있거나 일부 방향이 미분리된 경우에도 API가 제공한 도로는 그대로 표시한다.
  // 방향을 알 수 없는 전체 선에는 방향별 색상과 화살표를 붙이지 않는다.
  const fullRouteLine =
    fullPath.length >= 2 && board.route.directions.some(({ id }) => !mappedDirections.has(id))
      ? new sdk.Polyline({
          map,
          path: fullPath.map(toNaverCoordinate),
          strokeWeight: 6,
          strokeColor: "#5B6472",
          strokeOpacity: 0,
          zIndex: 1,
        })
      : null;
  const arrows: ArrowMarker[] = [];
  let arrowsShowBothDirections: boolean | null = null;
  let userMarker: NaverMarker | null = null;
  const vehicles = new Map<string, VehicleMarker>();
  let cameraFocusFrame: number | null = null;
  let vehicleFocusKey: string | null = null;
  let cameraFocusRevision = 0;
  const cameraFocusInterruptEvents = ["wheel", "touchstart", "pointerdown", "keydown"] as const;

  function cancelCameraFocus() {
    cameraFocusRevision += 1;
    if (cameraFocusFrame !== null) window.cancelAnimationFrame(cameraFocusFrame);
    cameraFocusFrame = null;
    vehicleFocusKey = null;
    cameraFocusInterruptEvents.forEach((event) => element.removeEventListener(event, cancelCameraFocus, true));
  }

  function focusVehicle(key: string | null, { focusMarker = true } = {}) {
    cancelCameraFocus();
    if (destroyed || initializationFailed || key === null) return;
    const item = vehicles.get(key);
    if (item === undefined) return;
    vehicleFocusKey = key;
    animateCamera(
      () => vehicles.get(key)?.coordinate,
      16,
      () => {
        if (focusMarker) vehicles.get(key)?.element.focus({ preventScroll: true });
      },
    );
  }

  function animateCamera(target: () => GeoCoordinate | undefined, minimumZoom: number, onComplete?: () => void) {
    const revision = cameraFocusRevision;
    const center = map.getCenter();
    const from = { latitude: center.lat(), longitude: center.lng() };
    const startZoom = map.getZoom();
    const targetZoom = Math.max(startZoom, minimumZoom);
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let startedAt: number | null = null;
    cameraFocusInterruptEvents.forEach((event) =>
      element.addEventListener(event, cancelCameraFocus, { passive: true, capture: true }),
    );
    const animate = (now: number) => {
      cameraFocusFrame = null;
      if (cameraFocusRevision !== revision) return;
      createNativeEventHandler(() => {
        const coordinate = target();
        if (coordinate === undefined) {
          cancelCameraFocus();
          return;
        }
        startedAt ??= now;
        const progress = reducedMotion ? 1 : Math.min(1, Math.max(0, (now - startedAt) / CAMERA_FOCUS_DURATION_MS));
        const eased = 1 - (1 - progress) ** 3;
        const nextZoom = startZoom + (targetZoom - startZoom) * eased;
        const zoom = useInstantZoom ? Math.round(nextZoom) : nextZoom;
        if (map.getZoom() !== zoom) map.setZoom(zoom, false);
        // 확대 배율을 보정해 선택한 대상이 화면 밖으로 밀리지 않고 중앙으로 다가오게 한다.
        const remaining = (1 - eased) * 2 ** (startZoom - map.getZoom());
        map.setCenter(
          toNaverCoordinate({
            latitude: coordinate.latitude + (from.latitude - coordinate.latitude) * remaining,
            longitude: coordinate.longitude + (from.longitude - coordinate.longitude) * remaining,
          }),
        );
        if (progress < 1) cameraFocusFrame = window.requestAnimationFrame(animate);
        else {
          if (vehicleFocusKey === null) cancelCameraFocus();
          onComplete?.();
        }
      })();
    };
    if (reducedMotion) animate(0);
    else cameraFocusFrame = window.requestAnimationFrame(animate);
  }
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
    button.dataset.boardingAllowed = String(stopState.boardingAllowed);
    button.setAttribute("aria-label", `${stopState.name} ${terminalStopName} 방면 정류장 지도에서 선택`);
    button.setAttribute("aria-pressed", "false");
    const symbol = document.createElement("span");
    symbol.className = styles.stopIcon;
    symbol.setAttribute("aria-hidden", "true");
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
    const hidden = map.getZoom() < DETAIL_ZOOM;
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
      const selectionChanged = button.getAttribute("aria-pressed") !== String(isSelected);
      const directionChanged = button.dataset.active !== String(isActive);
      if (selectionChanged) button.setAttribute("aria-pressed", String(isSelected));
      if (directionChanged) button.dataset.active = String(isActive);
      const labelAbove = String(offsetX !== 0 && !isActive);
      if (button.dataset.labelAbove !== labelAbove) button.dataset.labelAbove = labelAbove;
      if (label.hidden !== hidden) {
        label.hidden = hidden;
        marker.setVisible(!hidden || !mappedDirections.has(selection.direction));
      }
      if (selectionChanged || directionChanged) marker.setZIndex(isSelected ? 15 : isActive ? 6 : 5);
      // 아이콘을 다시 설정하면 SDK가 DOM을 재구성하므로 표시 위치만 조정한다.
      const transform = `translate(${offsetX - (size - 24) / 2}px, ${-(size - 24) / 2}px)`;
      if (button.style.transform !== transform) button.style.transform = transform;
    });
  }

  function refreshVehicleCounts() {
    const hidden = map.getZoom() < DETAIL_ZOOM;
    vehicles.forEach(({ count }) => {
      if (count.hidden !== hidden) count.hidden = hidden;
    });
  }

  function refreshArrows() {
    const showBothDirections = map.getZoom() >= DETAIL_ZOOM;
    const viewport = map.getSize();
    const projection = map.getProjection();
    // 축소 화면에서는 겹치는 두 방향 화살표 대신 현재 선택한 방향만 표시한다.
    const nextArrows = data.paths
      .filter((path) => showBothDirections || path.direction === activeDirection)
      .flatMap((path) =>
        routeArrowsFor(
          path.coordinates,
          (coordinate) => projection.fromCoordToOffset(toNaverCoordinate(coordinate)),
          viewport,
        ).map((arrow) => ({ ...arrow, direction: path.direction })),
      );
    nextArrows.forEach((arrow, index) => {
      const item = arrows[index];
      if (item === undefined) {
        const content = document.createElement("span");
        content.className = styles.arrow;
        content.dataset.mapArrowDirection = arrow.direction;
        content.setAttribute("aria-hidden", "true");
        content.innerHTML =
          '<svg width="14" height="14" viewBox="0 0 14 14"><path d="m4 3 5 4-5 4" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        content.style.transform = `rotate(${arrow.rotation}deg)`;
        const marker = new sdk.Marker({
          map,
          position: toNaverCoordinate(arrow.coordinate),
          icon: { content, anchor: new sdk.Point(8, 8) },
          zIndex: 3,
          clickable: false,
        });
        arrows.push({ ...arrow, marker, element: content, visible: true });
        return;
      }
      if (item.direction !== arrow.direction) {
        item.direction = arrow.direction;
        item.element.dataset.mapArrowDirection = arrow.direction;
      }
      if (
        item.coordinate.latitude !== arrow.coordinate.latitude ||
        item.coordinate.longitude !== arrow.coordinate.longitude
      ) {
        item.marker.setPosition(toNaverCoordinate(arrow.coordinate));
        item.coordinate = arrow.coordinate;
      }
      if (item.rotation !== arrow.rotation) {
        item.element.style.transform = `rotate(${arrow.rotation}deg)`;
        item.rotation = arrow.rotation;
      }
      if (!item.visible) {
        item.marker.setVisible(true);
        item.visible = true;
      }
    });
    // 필요한 수가 줄어도 제거하지 않고 다음 확대·이동에서 재사용한다.
    arrows.slice(nextArrows.length).forEach((item) => {
      if (!item.visible) return;
      item.marker.setVisible(false);
      item.visible = false;
    });
    arrowsShowBothDirections = showBothDirections;
  }

  function focus(selection: StopSelection, { animate = false } = {}) {
    cancelCameraFocus();
    const stop = data.stops.find(
      (item) => item.sequence === selection.sequence && item.direction === selection.direction,
    );
    if (stop === undefined) return;
    initialFitPending = false;
    initialViewIdle = false;
    cancelReadyFrame();
    if (animate) animateCamera(() => stop.coordinate, 15);
    else {
      map.setCenter(toNaverCoordinate(stop.coordinate));
      map.setZoom(Math.max(15, map.getZoom()), false);
    }
  }

  function zoomAt(delta: number, clientX: number, clientY: number) {
    const bounds = element.getBoundingClientRect();
    const origin = map.getProjection().fromOffsetToCoord(new sdk.Point(clientX - bounds.left, clientY - bounds.top));
    map.zoomBy(delta, origin, false);
  }

  let wheelDelta = 0;
  let lastWheelAt = 0;
  let lastTouchAt = -Infinity;
  const onTouchEnd = (event: TouchEvent) => {
    lastTouchAt = event.timeStamp;
  };
  const onWheel = (event: WheelEvent) => {
    event.preventDefault();
    if (!Number.isFinite(event.deltaY) || event.deltaY === 0) return;
    if (event.timeStamp - lastWheelAt > 200 || Math.sign(wheelDelta) !== Math.sign(event.deltaY)) wheelDelta = 0;
    const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? element.clientHeight : 1;
    wheelDelta += event.deltaY * unit;
    lastWheelAt = event.timeStamp;
    // 작은 트랙패드 입력을 모아 한 번의 입력으로 여러 줌 단계를 건너뛰지 않게 한다.
    if (Math.abs(wheelDelta) < (event.ctrlKey ? 12 : 60)) return;
    const delta = -Math.sign(wheelDelta);
    wheelDelta = 0;
    createNativeEventHandler(() => zoomAt(delta, event.clientX, event.clientY))();
  };
  const onDoubleClick = (event: MouseEvent) => {
    // 터치 더블탭은 SDK가 처리하므로 뒤따르는 합성 dblclick은 중복 처리하지 않는다.
    if (event.timeStamp - lastTouchAt < 500) return;
    if ((event.target as Element | null)?.closest?.("button")) return;
    event.preventDefault();
    event.stopPropagation();
    createNativeEventHandler(() => zoomAt(1, event.clientX, event.clientY))();
  };
  // 하드웨어 가속을 사용할 수 없는 환경은 SDK가 이미지 타일 지도로 전환하므로 확대를 정수 단계로 즉시 적용한다.
  // 이때도 마커는 GL 모듈이 transform으로 배치해서, 오버레이 확대 효과를 켜면 그 위치를 덮어써 지도 왼쪽 위로 모인다.
  const useInstantZoom = !map.getOptions("gl");
  if (useInstantZoom) {
    map.setOptions({ scrollWheel: false, disableDoubleClickZoom: true });
    element.addEventListener("wheel", onWheel, { passive: false });
    element.addEventListener("dblclick", onDoubleClick);
    element.addEventListener("touchend", onTouchEnd, { passive: true, capture: true });
  }

  const routeCoordinates = fullPath.length > 0 ? fullPath : data.paths.flatMap((path) => path.coordinates);
  function fitInitialRoute() {
    if (!initialFitPending) return;
    if (initialStop !== undefined) {
      initialFitPending = false;
      initialViewIdle = true;
      return;
    }
    initialFitPending = false;
    initialViewIdle = false;
    // 고정 캔버스의 위아래에서 잘리는 만큼 여백을 더해 보이는 영역 안에 경로를 맞춘다.
    const verticalPadding = 28 + Math.max(0, (map.getSize().height - visibleHeight) / 2);
    map.fitBounds(
      (routeCoordinates.length > 0 ? routeCoordinates : data.stops.map((stop) => stop.coordinate)).map(
        toNaverCoordinate,
      ),
      { top: verticalPadding, right: 12, bottom: verticalPadding, left: 12, maxZoom: 13 },
    );
    refreshStops();
    refreshArrows();
  }

  const listeners = [
    sdk.Event.addListener(map, "click", createNativeEventHandler(onDeselect)),
    sdk.Event.addListener(map, "tap", createNativeEventHandler(onDeselect)),
    // GL 초기화 전의 이미지 타일 배율로 맞추면 전환 후 노선 양 끝이 잘린다.
    sdk.Event.addListener(
      map,
      "init",
      createNativeEventHandler(() => {
        initialized = true;
        fitInitialRoute();
        notifyReadyAfterFrame();
      }),
    ),
    sdk.Event.addListener(
      map,
      "zoom_changed",
      createNativeEventHandler(() => {
        refreshStops();
        refreshVehicleCounts();
        const showBothDirections = map.getZoom() >= DETAIL_ZOOM;
        if (arrowsShowBothDirections !== showBothDirections) refreshArrows();
      }),
    ),
    sdk.Event.addListener(
      map,
      "idle",
      createNativeEventHandler(() => {
        refreshStops();
        refreshVehicleCounts();
        refreshArrows();
        initialViewIdle = true;
        notifyReadyAfterFrame();
      }),
    ),
  ];
  if (useInstantZoom) fitInitialRoute();
  else {
    refreshStops();
    refreshArrows();
  }

  return {
    update(nextSelected, nextDirection, nextVehicles, selectedVehicleKey: string | null = null) {
      selected = nextSelected;
      const directionChanged = activeDirection !== nextDirection;
      activeDirection = nextDirection;
      if (directionChanged) {
        cancelCameraFocus();
        paths.forEach(({ direction, line }) => line.setOptions(routePathStyleFor(direction)));
        refreshArrows();
      }
      refreshStops();
      const visibleKeys = new Set(nextVehicles.map((view) => view.key));
      for (const [key, item] of vehicles) {
        if (visibleKeys.has(key)) continue;
        if (vehicleFocusKey === key) cancelCameraFocus();
        item.marker.setMap(null);
        vehicles.delete(key);
      }
      nextVehicles.forEach((view) => {
        const coordinate = routeMapCoordinateForSequence(
          data,
          view.vehicle.currentStopSequence,
          view.vehicle.direction,
        );
        if (coordinate === null) {
          if (vehicleFocusKey === view.key) cancelCameraFocus();
          vehicles.get(view.key)?.marker.setMap(null);
          vehicles.delete(view.key);
          return;
        }
        let item = vehicles.get(view.key);
        if (item === undefined) {
          const content = document.createElement("button");
          content.type = "button";
          content.className = styles.bus;
          content.dataset.mapVehicleKey = view.key;
          content.setAttribute("aria-pressed", "false");
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
            clickable: true,
          });
          const vehicleMarker: VehicleMarker = {
            marker,
            element: content,
            count,
            selection: { direction: view.vehicle.direction, sequence: view.vehicle.currentStopSequence },
            coordinate,
          };
          content.addEventListener("click", (event) => {
            event.stopPropagation();
            onVehicleSelect(vehicleMarker.selection, view.key);
          });
          item = vehicleMarker;
          vehicles.set(view.key, item);
        }
        item.selection = { direction: view.vehicle.direction, sequence: view.vehicle.currentStopSequence };
        const pressed = String(view.key === selectedVehicleKey);
        if (item.element.getAttribute("aria-pressed") !== pressed) {
          item.element.setAttribute("aria-pressed", pressed);
          item.marker.setZIndex(pressed === "true" ? 30 : 20);
        }
        const seat = view.vehicle.seat;
        const count = seat.kind === "EXACT" ? `현재 ${seat.remaining}석` : "–";
        if (item.count.textContent !== count) item.count.textContent = count;
        const label = `${view.vehicle.stopName} 부근 버스, ${seat.kind === "EXACT" ? `현재 잔여 좌석 ${seat.remaining}석` : "현재 잔여 좌석 정보 없음"}`;
        if (item.element.getAttribute("aria-label") !== label) item.element.setAttribute("aria-label", label);
      });
      refreshVehicleCounts();
    },
    moveVehicles(frames) {
      const groups = new Map<string, { item: VehicleMarker; coordinate: GeoCoordinate }[]>();
      frames.forEach((frame) => {
        const item = vehicles.get(frame.key);
        if (item === undefined) return;
        // 방향 경로가 없을 때는 보간 프레임 대신 최근 응답의 정류장 위치에 머문다.
        const sequencePosition = mappedDirections.has(item.selection.direction)
          ? frame.sequencePosition
          : item.selection.sequence;
        const coordinate = routeMapCoordinateForSequence(data, sequencePosition, item.selection.direction);
        if (coordinate === null) return;
        const key = `${coordinate.latitude}:${coordinate.longitude}`;
        const group = groups.get(key) ?? [];
        group.push({ item, coordinate });
        groups.set(key, group);
      });
      groups.forEach((group) =>
        group.forEach(({ item, coordinate }, index) => {
          item.marker.setPosition(toNaverCoordinate(coordinate));
          item.coordinate = coordinate;
          // 핀 끝은 경로 위에 두고, 같은 위치의 차량은 잔여석만 위로 쌓아 구분한다.
          item.count.style.transform = `translateY(${-index * 20}px)`;
        }),
      );
      // 최초 이동이 끝나면 핀과 같은 보간 좌표를 사용해 카메라만 따라간다.
      if (vehicleFocusKey !== null && cameraFocusFrame === null) {
        const coordinate = vehicles.get(vehicleFocusKey)?.coordinate;
        if (coordinate !== undefined) map.setCenter(toNaverCoordinate(coordinate));
      }
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
    focusVehicle,
    showLocation(coordinate) {
      cancelCameraFocus();
      initialFitPending = false;
      initialViewIdle = false;
      cancelReadyFrame();
      userMarker?.setMap(null);
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
      map.setCenter(position);
      map.setZoom(Math.max(15, map.getZoom()), false);
    },
    destroy() {
      destroyed = true;
      cancelCameraFocus();
      window.clearTimeout(initializationTimeout);
      initialFitPending = false;
      cancelReadyFrame();
      const cleanup = (action: () => void) => {
        try {
          action();
        } catch {
          // 인증 실패로 SDK 내부가 비워진 경우에도 나머지 자원을 정리한다.
        }
      };
      listeners.forEach((listener) => cleanup(() => sdk.Event.removeListener(listener)));
      if (useInstantZoom) {
        cleanup(() => element.removeEventListener("wheel", onWheel));
        cleanup(() => element.removeEventListener("dblclick", onDoubleClick));
        cleanup(() => element.removeEventListener("touchend", onTouchEnd, true));
      }
      stops.forEach(({ marker }) => cleanup(() => marker.setMap(null)));
      vehicles.forEach(({ marker }) => cleanup(() => marker.setMap(null)));
      arrows.forEach(({ marker }) => cleanup(() => marker.setMap(null)));
      paths.forEach(({ line }) => cleanup(() => line.setMap(null)));
      cleanup(() => fullRouteLine?.setMap(null));
      cleanup(() => userMarker?.setMap(null));
      cleanup(() => map.destroy());
    },
  };
}
