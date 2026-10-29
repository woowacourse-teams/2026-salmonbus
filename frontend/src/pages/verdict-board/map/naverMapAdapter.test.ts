import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { boardMock } from "@/shared/api/routeForecast.mock";
import { liveVehicleViewsFor } from "../components/liveVehiclePolicy";
import { routeWatchMock } from "@/shared/api/routeForecast.mock";
import { loadPreviewRouteMapData } from "./routeMapPreview";
import type { NaverCoordinate, NaverMap, NaverMaps, NaverMarker } from "./naverSdk";
import { createNaverRouteMap } from "./naverMapAdapter";
import type { RouteMapData } from "./routeMapModel";

jest.mock("./RouteMap.css", () => ({
  stop: "stop",
  stopName: "name",
  arrow: "arrow",
  bus: "bus",
  busCount: "busCount",
  busPin: "busPin",
  busIcon: "busIcon",
  userPosition: "user",
}));

class ElementStub {
  readonly tagName: string;
  type = "";
  clientWidth = 400;
  clientHeight = 350;
  className = "";
  textContent = "";
  innerHTML = "";
  hidden = false;
  dataset: Record<string, string> = {};
  style: Record<string, string> = {};
  children: ElementStub[] = [];
  attributes = new Map<string, string>();
  listeners = new Map<string, (event: Event) => void>();
  captureListeners = new Map<string, (event: Event) => void>();
  focus = jest.fn<(options?: FocusOptions) => void>();
  constructor(tagName = "div") {
    this.tagName = tagName.toUpperCase();
  }
  setAttribute(name: string, value: string) {
    this.attributes.set(name, value);
  }
  getAttribute(name: string) {
    return this.attributes.get(name) ?? null;
  }
  append(...children: ElementStub[]) {
    this.children.push(...children);
  }
  replaceChildren(...children: ElementStub[]) {
    this.children = children;
  }
  addEventListener(name: string, listener: (event: Event) => void, options?: AddEventListenerOptions | boolean) {
    const capture = typeof options === "boolean" ? options : options?.capture;
    (capture ? this.captureListeners : this.listeners).set(name, listener);
  }
  removeEventListener(name: string, _listener: unknown, capture = false) {
    (capture ? this.captureListeners : this.listeners).delete(name);
  }
  getBoundingClientRect() {
    return { left: 10, top: 20 };
  }
}

class CoordinateStub implements NaverCoordinate {
  constructor(
    private latitude: number,
    private longitude: number,
  ) {}
  lat() {
    return this.latitude;
  }
  lng() {
    return this.longitude;
  }
}
class PointStub {
  constructor(
    public x: number,
    public y: number,
  ) {}
}
class SizeStub {
  constructor(
    public width: number,
    public height: number,
  ) {}
}

function createRecordingSdk(glSupported = true) {
  let damaged = false;
  const checkSdk = () => {
    if (damaged) throw new TypeError("SDK 내부 구현이 제거됨");
  };
  const markers: MarkerStub[] = [];
  const lines: ConstructorParameters<NaverMaps["Polyline"]>[0][] = [];
  const lineMaps = new Map<ConstructorParameters<NaverMaps["Polyline"]>[0], NaverMap | null>();
  const circles: ConstructorParameters<NaverMaps["Circle"]>[0][] = [];
  const events: { name: string; listener: () => void }[] = [];
  class MapStub implements NaverMap {
    zoom = 10;
    center: NaverCoordinate = new CoordinateStub(0, 0);
    size = new SizeStub(400, 350);
    fits = 0;
    fitMaxZoom = 0;
    fitMargins: { top: number; right: number; bottom: number; left: number; maxZoom: number } | null = null;
    fittedCoordinates: NaverCoordinate[] = [];
    projectionOffsetX = 0;
    destroyed = false;
    constructor(
      _element: HTMLElement,
      public options: ConstructorParameters<NaverMaps["Map"]>[1],
    ) {
      this.center = options.center;
      this.zoom = options.zoom;
    }
    getZoom() {
      checkSdk();
      return this.zoom;
    }
    setZoom(zoom: number) {
      this.zoom = zoom;
    }
    zoomBy(delta: number) {
      checkSdk();
      this.zoom += delta;
    }
    getOptions() {
      return glSupported;
    }
    setOptions(options: Parameters<NaverMap["setOptions"]>[0]) {
      Object.assign(this.options, options);
    }
    getCenter() {
      return this.center;
    }
    setCenter(center: NaverCoordinate) {
      this.center = center;
    }
    fitBounds(
      coordinates: NaverCoordinate[],
      margins: { top: number; right: number; bottom: number; left: number; maxZoom: number },
    ) {
      this.fits += 1;
      this.fittedCoordinates = coordinates;
      this.fitMaxZoom = margins.maxZoom;
      this.fitMargins = margins;
      this.zoom = margins.maxZoom;
    }
    getSize() {
      return this.size;
    }
    setSize(size: SizeStub) {
      this.size = size;
    }
    getProjection() {
      return {
        fromCoordToOffset: (coordinate: NaverCoordinate) =>
          new PointStub((coordinate.lng() - 126.9) * 1000 + this.projectionOffsetX, (37.6 - coordinate.lat()) * 1000),
        fromOffsetToCoord: (point: PointStub) =>
          new CoordinateStub(37.6 - point.y / 1000, 126.9 + (point.x - this.projectionOffsetX) / 1000),
      };
    }
    destroy() {
      checkSdk();
      this.destroyed = true;
    }
  }
  class MarkerStub implements NaverMarker {
    visible = true;
    map: NaverMap | null;
    constructor(public options: ConstructorParameters<NaverMaps["Marker"]>[0]) {
      this.map = options.map;
      markers.push(this);
    }
    setMap(map: NaverMap | null) {
      checkSdk();
      this.map = map;
    }
    setPosition(position: NaverCoordinate) {
      this.options.position = position;
    }
    setVisible(visible: boolean) {
      this.visible = visible;
    }
    setIcon(icon: ConstructorParameters<NaverMaps["Marker"]>[0]["icon"]) {
      checkSdk();
      this.options.icon = icon;
    }
    setZIndex(zIndex: number) {
      this.options.zIndex = zIndex;
    }
  }
  const maps: MapStub[] = [];
  class RecordingMap extends MapStub {
    constructor(element: HTMLElement, options: ConstructorParameters<NaverMaps["Map"]>[1]) {
      super(element, options);
      maps.push(this);
    }
  }
  const sdk: NaverMaps = {
    glEnabled: true,
    LatLng: CoordinateStub,
    Point: PointStub,
    Size: SizeStub,
    Map: RecordingMap,
    Marker: MarkerStub,
    Polyline: class {
      constructor(private options: ConstructorParameters<NaverMaps["Polyline"]>[0]) {
        lines.push(options);
        lineMaps.set(options, options.map);
      }
      setOptions(options: Parameters<InstanceType<NaverMaps["Polyline"]>["setOptions"]>[0]) {
        checkSdk();
        Object.assign(this.options, options);
      }
      setMap(map: NaverMap | null) {
        checkSdk();
        lineMaps.set(this.options, map);
      }
    },
    Circle: class {
      constructor(options: ConstructorParameters<NaverMaps["Circle"]>[0]) {
        circles.push(options);
      }
      setMap() {}
    },
    Event: {
      addListener(_target, name, listener) {
        events.push({ name, listener });
        return listener;
      },
      removeListener: jest.fn(checkSdk),
    },
  };
  return {
    sdk,
    markers,
    lines,
    lineMaps,
    circles,
    events,
    damage: () => {
      damaged = true;
    },
    get map() {
      return maps.at(-1)!;
    },
  };
}

const previousDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
const previousWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
afterEach(() => {
  if (previousDocument !== undefined) Object.defineProperty(globalThis, "document", previousDocument);
  else Reflect.deleteProperty(globalThis, "document");
  if (previousWindow !== undefined) Object.defineProperty(globalThis, "window", previousWindow);
  else Reflect.deleteProperty(globalThis, "window");
});

function setup(
  glSupported = true,
  { initialized = true, reducedMotion = false, visibleHeight = 350 } = {},
  dataOverrides: Partial<RouteMapData> = {},
) {
  const pendingFrames = new Map<number, FrameRequestCallback>();
  const pendingTimers = new Map<number, { remaining: number; callback: () => void }>();
  const windowListeners = new Map<string, () => void>();
  let frameId = 0;
  let frameTime = 0;
  let timerId = 0;
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        const id = ++frameId;
        pendingFrames.set(id, callback);
        return id;
      },
      cancelAnimationFrame: (id: number) => pendingFrames.delete(id),
      setTimeout: (callback: () => void, delay: number) => {
        const id = ++timerId;
        pendingTimers.set(id, { remaining: delay, callback });
        return id;
      },
      clearTimeout: (id: number) => pendingTimers.delete(id),
      matchMedia: () => ({ matches: reducedMotion }),
      addEventListener: (name: string, listener: () => void) => windowListeners.set(name, listener),
      removeEventListener: (name: string) => windowListeners.delete(name),
    },
  });
  const flushFrames = (elapsed = 16) => {
    frameTime += elapsed;
    const callbacks = [...pendingFrames.values()];
    pendingFrames.clear();
    callbacks.forEach((callback) => callback(frameTime));
  };
  const advanceTimers = (milliseconds: number) => {
    const expired: (() => void)[] = [];
    pendingTimers.forEach((timer, id) => {
      timer.remaining -= milliseconds;
      if (timer.remaining <= 0) {
        pendingTimers.delete(id);
        expired.push(timer.callback);
      }
    });
    expired.forEach((callback) => callback());
  };
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { createElement: (tagName: string) => new ElementStub(tagName) },
  });
  const native = createRecordingSdk(glSupported);
  const data: RouteMapData = { ...loadPreviewRouteMapData(boardMock)!, ...dataOverrides };
  const onSelect = jest.fn<(selection: { direction: "UP" | "DOWN"; sequence: number }) => void>();
  const onError = jest.fn();
  const onReady = jest.fn();
  const onVehicleSelect = jest.fn<(selection: { direction: "UP" | "DOWN"; sequence: number }, key: string) => void>();
  const onDeselect = jest.fn();
  const element = new ElementStub();
  const adapter = createNaverRouteMap(
    native.sdk,
    element as unknown as HTMLElement,
    boardMock,
    data,
    visibleHeight,
    onSelect,
    onError,
    onReady,
    onVehicleSelect,
    onDeselect,
  );
  const initialize = () => native.events.find((event) => event.name === "init")?.listener();
  if (initialized) initialize();
  return {
    native,
    data,
    onSelect,
    onError,
    onReady,
    onVehicleSelect,
    onDeselect,
    adapter,
    element,
    initialize,
    pendingFrames,
    flushFrames,
    pendingTimers,
    advanceTimers,
    windowListeners,
  };
}

function visibleArrowDirections(native: ReturnType<typeof createRecordingSdk>) {
  return [
    ...new Set(
      native.markers
        .filter((marker) => {
          const content = marker.options.icon.content as unknown as ElementStub;
          return content.className === "arrow" && marker.visible;
        })
        .map((marker) => {
          const content = marker.options.icon.content as unknown as ElementStub;
          return content.dataset.mapArrowDirection ?? content.getAttribute("data-map-arrow-direction");
        }),
    ),
  ].sort();
}

describe("네이버 지도 adapter", () => {
  it.each(["click", "tap"])("지도 배경의 %s는 카메라 이동 없이 선택 해제를 요청한다", (eventName) => {
    const { native, adapter, onDeselect, onSelect, onVehicleSelect } = setup();
    const selection = { direction: "UP" as const, sequence: 15 };
    adapter.update(selection, "UP", []);
    adapter.focus(selection);
    const center = native.map.center;
    const zoom = native.map.zoom;
    const listener = native.events.find((event) => event.name === eventName)!.listener;

    listener();

    expect(onDeselect).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
    expect(onVehicleSelect).not.toHaveBeenCalled();
    expect(native.map.center).toBe(center);
    expect(native.map.zoom).toBe(zoom);
    adapter.destroy();
    expect(native.sdk.Event.removeListener).toHaveBeenCalledWith(listener);
  });

  it("GL 지도에서 SDK의 부드러운 확대와 기본 마커 배치를 유지한다", () => {
    const { native, element } = setup();
    expect(native.map.options).toMatchObject({
      gl: true,
      zoom: 10,
      overlayZoomEffect: null,
      scrollWheel: true,
      disableDoubleClickZoom: false,
    });
    expect(element.listeners.size).toBe(0);
  });
  it("GL 초기화와 최초 idle 뒤 노선을 먼저 그리고 다음 프레임에 마커 표시 준비를 알린다", () => {
    const { native, initialize, onReady, flushFrames, pendingFrames } = setup(true, { initialized: false });
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    expect(native.lines.every((line) => line.strokeOpacity === 0)).toBe(true);
    idle();
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    expect(native.map.fits).toBe(0);
    expect(native.lines.every((line) => line.strokeOpacity === 0)).toBe(true);

    initialize();
    expect(native.map.fits).toBe(1);
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    expect(native.lines.every((line) => line.strokeOpacity === 0)).toBe(true);

    idle();
    idle();
    expect(pendingFrames.size).toBe(1);
    expect(onReady).not.toHaveBeenCalled();
    expect(native.lines.every((line) => line.strokeOpacity === 0)).toBe(true);
    flushFrames();
    expect(native.lines.every((line) => line.strokeOpacity === 0.95)).toBe(true);
    expect(onReady).not.toHaveBeenCalled();
    expect(pendingFrames.size).toBe(1);
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
  });
  it("준비 중 방향을 바꿔도 노선을 먼저 표시하지 않는다", () => {
    const { native, adapter } = setup();
    adapter.update(null, "DOWN", []);
    expect(native.lines.every((line) => line.strokeOpacity === 0)).toBe(true);
  });
  it("한 번 표시한 지도는 확대·방향 전환·추가 idle에서 노선을 숨기거나 준비 콜백을 다시 호출하지 않는다", () => {
    const { native, adapter, initialize, onReady, flushFrames, pendingFrames } = setup();
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    idle();
    flushFrames();
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);

    native.map.zoom = 14;
    native.events.find((event) => event.name === "zoom_changed")!.listener();
    adapter.update(null, "DOWN", []);
    initialize();
    idle();
    idle();
    expect(native.lines.every((line) => line.strokeOpacity === 0.95)).toBe(true);
    expect(pendingFrames.size).toBe(0);
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
  });
  for (const routeRevealed of [false, true]) {
    it(`${routeRevealed ? "노선을 그린 뒤" : "노선을 그리기 전"} 대기 중인 지도를 제거하면 준비 콜백을 취소한다`, () => {
      const { native, adapter, onReady, flushFrames, pendingFrames } = setup();
      native.events.find((event) => event.name === "idle")!.listener();
      if (routeRevealed) flushFrames();
      expect(pendingFrames.size).toBe(1);
      adapter.destroy();
      expect(pendingFrames.size).toBe(0);
      flushFrames();
      expect(onReady).not.toHaveBeenCalled();
    });
  }
  it("초기화 전에 정류장을 선택해도 GL 초기화·idle·두 프레임까지 마커 표시를 기다린다", () => {
    const { native, adapter, initialize, onReady, flushFrames } = setup(true, { initialized: false });
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    adapter.focus({ direction: "UP", sequence: 15 });
    idle();
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    initialize();
    expect(native.map.fits).toBe(0);
    idle();
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
  });
  it("GL fallback도 native 초기화와 전체 경로 idle 뒤 두 프레임에 걸쳐 표시한다", () => {
    const { native, initialize, onReady, flushFrames } = setup(false, { initialized: false });
    expect(native.map.fits).toBe(1);
    native.events.find((event) => event.name === "idle")!.listener();
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    initialize();
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
  });
  it("노선을 그리기 전에 정류장을 선택하면 이동을 마친 idle과 두 프레임을 다시 기다린다", () => {
    const { native, adapter, onReady, flushFrames, pendingFrames } = setup();
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    idle();
    expect(pendingFrames.size).toBe(1);
    adapter.focus({ direction: "UP", sequence: 15 });
    expect(pendingFrames.size).toBe(0);
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    idle();
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
  });
  it("노선을 그린 뒤 정류장을 선택하면 다음 idle까지 마커 표시를 기다리며 노선은 유지한다", () => {
    const { native, adapter, onReady, flushFrames, pendingFrames } = setup();
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    idle();
    flushFrames();
    expect(native.lines.every((line) => line.strokeOpacity === 0.95)).toBe(true);
    adapter.focus({ direction: "UP", sequence: 15 });
    expect(pendingFrames.size).toBe(0);
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
    expect(native.lines.every((line) => line.strokeOpacity === 0.95)).toBe(true);
    idle();
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
  });
  for (const initialized of [false, true]) {
    it(`${initialized ? "GL 초기화 뒤 idle이 오지 않으면" : "GL 초기화가 끝나지 않으면"} 10초 후 한 번 실패하고 늦은 이벤트로 표시하지 않는다`, () => {
      const { native, initialize, onError, onReady, advanceTimers, flushFrames, pendingFrames } = setup(true, {
        initialized,
      });
      advanceTimers(9_999);
      expect(onError).not.toHaveBeenCalled();
      advanceTimers(1);
      expect(onError).toHaveBeenCalledTimes(1);

      initialize();
      native.events.find((event) => event.name === "idle")!.listener();
      flushFrames();
      flushFrames();
      advanceTimers(10_000);
      expect(onError).toHaveBeenCalledTimes(1);
      expect(onReady).not.toHaveBeenCalled();
      expect(pendingFrames.size).toBe(0);
    });
  }
  it("노선을 그린 뒤에도 표시 제한 시간이 지나면 남은 준비 프레임을 취소한다", () => {
    const { native, onError, onReady, advanceTimers, flushFrames, pendingFrames } = setup();
    native.events.find((event) => event.name === "idle")!.listener();
    flushFrames();
    expect(pendingFrames.size).toBe(1);
    advanceTimers(10_000);
    expect(onError).toHaveBeenCalledTimes(1);
    expect(pendingFrames.size).toBe(0);
    flushFrames();
    expect(onReady).not.toHaveBeenCalled();
  });
  for (const outcome of ["ready", "destroy"] as const) {
    it(`지도를 ${outcome === "ready" ? "표시하면" : "제거하면"} 초기 표시 제한 시간을 해제한다`, () => {
      const { native, adapter, onError, onReady, advanceTimers, flushFrames, pendingTimers } = setup();
      if (outcome === "ready") {
        native.events.find((event) => event.name === "idle")!.listener();
        flushFrames();
        flushFrames();
        expect(onReady).toHaveBeenCalledTimes(1);
      } else adapter.destroy();
      expect(pendingTimers.size).toBe(0);
      advanceTimers(10_000);
      expect(onError).not.toHaveBeenCalled();
    });
  }
  it("GL 초기화 뒤 두 방향의 모든 경로 좌표를 한 번만 화면에 맞춘다", () => {
    const { native, data, adapter, initialize } = setup(true, { initialized: false });
    expect(native.lines).toHaveLength(2);
    expect(native.map.fits).toBe(0);

    initialize();
    expect(native.map.fits).toBe(1);
    expect(native.map.fitMaxZoom).toBe(13);
    expect(
      native.map.fittedCoordinates.map((coordinate) => ({ latitude: coordinate.lat(), longitude: coordinate.lng() })),
    ).toEqual(data.paths.flatMap((path) => path.coordinates));

    initialize();
    adapter.resize(400, 600);
    adapter.update(null, "UP", []);
    initialize();
    expect(native.map.fits).toBe(1);
  });
  it("GL을 사용할 수 없는 지도는 초기화 이벤트를 기다리지 않고 전체 경로를 맞춘다", () => {
    const { native, initialize } = setup(false, { initialized: false });
    expect(native.map.fits).toBe(1);
    expect(native.map.fittedCoordinates.length).toBeGreaterThan(0);
    initialize();
    expect(native.map.fits).toBe(1);
  });
  for (const glSupported of [true, false]) {
    it(`${glSupported ? "GL" : "타일"} 지도의 첫 경로를 캔버스에서 잘린 위아래 영역을 제외하고 맞춘다`, () => {
      const { native } = setup(glSupported, { visibleHeight: 150 });
      expect(native.map.fitMargins).toEqual({ top: 128, right: 12, bottom: 128, left: 12, maxZoom: 13 });
    });
  }
  for (const interaction of ["focus", "location"] as const) {
    it(`GL 초기화 전에 ${interaction === "focus" ? "정류장을 선택하면" : "현재 위치를 찾으면"} 뒤늦은 전체 경로 맞춤으로 덮어쓰지 않는다`, () => {
      const { native, adapter, initialize } = setup(true, { initialized: false });
      if (interaction === "focus") adapter.focus({ direction: "UP", sequence: 15 });
      else adapter.showLocation({ latitude: 37.4, longitude: 127.1 });
      const center = native.map.center;
      const zoom = native.map.zoom;

      initialize();
      expect(native.map.fits).toBe(0);
      expect(native.map.center).toBe(center);
      expect(native.map.zoom).toBe(zoom);
    });
  }
  it("GL 초기화를 기다리던 지도를 제거하면 늦게 도착한 초기화 이벤트로 경로를 맞추지 않는다", () => {
    const { native, adapter, initialize } = setup(true, { initialized: false });
    adapter.destroy();
    expect(initialize).not.toThrow();
    expect(native.map.fits).toBe(0);
    expect(native.map.destroyed).toBe(true);
  });
  it("GL을 사용할 수 없으면 작은 휠 입력을 모아 포인터 위치를 기준으로 즉시 확대한다", () => {
    const { native, element } = setup(false);
    expect(native.map.options).toMatchObject({
      overlayZoomEffect: "all",
      scrollWheel: false,
      disableDoubleClickZoom: true,
    });
    const zoomBy = jest.spyOn(native.map as NaverMap, "zoomBy");
    const wheel = element.listeners.get("wheel")!;
    const preventDefault = jest.fn();
    const input = (deltaY: number, timeStamp: number) =>
      wheel({ deltaY, deltaMode: 0, clientX: 110, clientY: 120, timeStamp, preventDefault } as unknown as Event);
    input(-20, 100);
    input(-20, 110);
    expect(zoomBy).not.toHaveBeenCalled();
    input(-20, 120);
    expect(zoomBy).toHaveBeenCalledTimes(1);
    const [delta, origin, animate] = zoomBy.mock.calls[0]!;
    expect(delta).toBe(1);
    expect(origin.lat()).toBeCloseTo(37.5);
    expect(origin.lng()).toBeCloseTo(127);
    expect(animate).toBe(false);
    expect(preventDefault).toHaveBeenCalledTimes(3);

    input(-40, 130);
    input(30, 140);
    input(30, 400);
    expect(zoomBy).toHaveBeenCalledTimes(1);
    input(30, 410);
    expect(zoomBy.mock.calls[1]?.[0]).toBe(-1);
  });
  it("GL을 사용할 수 없는 지도의 더블클릭은 즉시 확대하고 제거 시 입력 리스너를 정리한다", () => {
    const { native, element, adapter } = setup(false);
    const zoomBy = jest.spyOn(native.map as NaverMap, "zoomBy");
    element.listeners.get("dblclick")!({
      clientX: 110,
      clientY: 120,
      target: null,
      preventDefault: jest.fn(),
      stopPropagation: jest.fn(),
    } as unknown as Event);
    expect(zoomBy.mock.calls[0]?.[0]).toBe(1);
    expect(zoomBy.mock.calls[0]?.[2]).toBe(false);
    adapter.destroy();
    expect(element.listeners.size).toBe(0);
  });
  it("GL fallback의 휠 확대 중 SDK 오류도 지도 오류 처리로 전달한다", () => {
    const { native, element, onError } = setup(false);
    native.damage();
    expect(() =>
      element.listeners.get("wheel")!({
        deltaY: -120,
        deltaMode: 0,
        clientX: 110,
        clientY: 120,
        timeStamp: 100,
        preventDefault: jest.fn(),
      } as unknown as Event),
    ).not.toThrow();
    expect(onError).toHaveBeenCalledTimes(1);
  });
  it("GL fallback에서도 터치 더블탭 뒤의 합성 더블클릭은 확대를 중복 실행하지 않는다", () => {
    const { native, element } = setup(false);
    const zoomBy = jest.spyOn(native.map as NaverMap, "zoomBy");
    element.captureListeners.get("touchend")!({ timeStamp: 100 } as Event);
    const doubleClick = element.listeners.get("dblclick")!;
    const input = { clientX: 110, clientY: 120, target: null, preventDefault: jest.fn(), stopPropagation: jest.fn() };
    doubleClick({ ...input, timeStamp: 200 } as unknown as Event);
    expect(zoomBy).not.toHaveBeenCalled();
    doubleClick({ ...input, timeStamp: 700 } as unknown as Event);
    expect(zoomBy).toHaveBeenCalledTimes(1);
  });
  it("축소 상태에서 선택한 방향의 화살표만 표시하고 방향을 바꿔도 지도 시점을 유지한다", () => {
    const { native, adapter } = setup();
    expect(visibleArrowDirections(native)).toEqual(["UP"]);
    const map = native.map;
    const center = map.center;
    const zoom = map.zoom;
    const setCenter = jest.spyOn(map, "setCenter");
    const setZoom = jest.spyOn(map, "setZoom");

    adapter.update(null, "DOWN", []);
    expect(visibleArrowDirections(native)).toEqual(["DOWN"]);
    adapter.update(null, "UP", []);
    expect(visibleArrowDirections(native)).toEqual(["UP"]);
    expect(native.map).toBe(map);
    expect(map.center).toBe(center);
    expect(map.zoom).toBe(zoom);
    expect(map.fits).toBe(1);
    expect(setCenter).not.toHaveBeenCalled();
    expect(setZoom).not.toHaveBeenCalled();
  });
  it("줌 14 경계를 넘으면 idle을 기다리지 않고 한 방향과 두 방향 화살표 표시를 전환한다", () => {
    const { native, adapter } = setup();
    const zoomChanged = native.events.find((event) => event.name === "zoom_changed")!.listener;
    native.map.zoom = 13.9;
    zoomChanged();
    expect(visibleArrowDirections(native)).toEqual(["UP"]);

    native.map.zoom = 14;
    zoomChanged();
    expect(visibleArrowDirections(native)).toEqual(["DOWN", "UP"]);
    adapter.update(null, "DOWN", []);
    expect(visibleArrowDirections(native)).toEqual(["DOWN", "UP"]);

    native.map.zoom = 13.9;
    zoomChanged();
    expect(visibleArrowDirections(native)).toEqual(["DOWN"]);
  });
  it("확대와 이동에서 정류장 아이콘과 방향 화살표를 제거하거나 다시 만들지 않는다", () => {
    const { native, adapter } = setup();
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    const zoomChanged = native.events.find((event) => event.name === "zoom_changed")!.listener;
    // 처음 두 방향을 표시할 때 필요한 마커까지 만든 뒤 같은 풀을 재사용하는지 확인한다.
    native.map.zoom = 14;
    zoomChanged();
    idle();
    const markers = [...native.markers];
    const iconUpdates = markers.map((marker) => jest.spyOn(marker, "setIcon"));
    const mapUpdates = markers.map((marker) => jest.spyOn(marker, "setMap"));
    native.map.zoom = 13.9;
    zoomChanged();
    adapter.update(null, "DOWN", []);
    native.map.zoom = 14;
    zoomChanged();
    idle();
    expect(iconUpdates.every((update) => update.mock.calls.length === 0)).toBe(true);
    expect(native.markers).toEqual(markers);
    expect(markers.every((marker) => marker.map === native.map)).toBe(true);
    expect(mapUpdates.every((update) => update.mock.calls.length === 0)).toBe(true);

    // 화면 밖으로 나갔다 돌아온 화살표도 같은 마커를 다시 사용한다.
    const arrows = markers.filter(
      (marker) => (marker.options.icon.content as unknown as ElementStub).className === "arrow",
    );
    expect(arrows.length).toBeGreaterThan(0);
    native.map.projectionOffsetX = -10_000;
    idle();
    expect(arrows.every((marker) => !marker.visible && marker.map === native.map)).toBe(true);
    native.map.projectionOffsetX = 0;
    idle();
    expect(arrows.every((marker) => marker.visible)).toBe(true);
    expect(native.markers).toEqual(markers);
  });
  it("화면이 그대로인 idle 이벤트는 마커를 다시 갱신하지 않는다", () => {
    const { native } = setup();
    const updates = native.markers.flatMap((marker) => [
      jest.spyOn(marker, "setIcon"),
      jest.spyOn(marker, "setPosition"),
      jest.spyOn(marker, "setVisible"),
      jest.spyOn(marker, "setZIndex"),
    ]);
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    idle();
    idle();
    expect(updates.every((update) => update.mock.calls.length === 0)).toBe(true);
  });
  it("크기가 같으면 지도 크기와 중심·줌을 다시 설정하지 않는다", () => {
    const { native, adapter } = setup();
    const setSize = jest.spyOn(native.map, "setSize");
    const setCenter = jest.spyOn(native.map, "setCenter");
    const setZoom = jest.spyOn(native.map, "setZoom");
    adapter.resize(400, 350);
    expect(setSize).not.toHaveBeenCalled();
    expect(setCenter).not.toHaveBeenCalled();
    expect(setZoom).not.toHaveBeenCalled();
  });
  it("두 방향 경로를 그리고 최초에만 전체를 맞춘 뒤 크기 변경에도 중심과 줌을 보존한다", () => {
    const { native, adapter } = setup();
    expect(native.lines).toHaveLength(2);
    expect(native.map.fits).toBe(1);
    expect(native.map.fitMaxZoom).toBe(13);
    adapter.focus({ direction: "UP", sequence: 15 });
    const center = native.map.center;
    const setCenter = jest.spyOn(native.map, "setCenter");
    const setZoom = jest.spyOn(native.map, "setZoom");
    adapter.resize(400, 600);
    expect(native.map.size).toEqual(new SizeStub(400, 600));
    expect(setCenter).not.toHaveBeenCalled();
    expect(setZoom).not.toHaveBeenCalled();
    expect(native.map.fits).toBe(1);
    expect(native.map.center).toBe(center);
    expect(native.map.zoom).toBe(15);
  });
  it("정류장이나 분리 경로가 없어도 전체 도로를 중립색으로 그리고 원본 형상에 맞춘 뒤 자원을 정리한다", () => {
    const fullPath = [
      { latitude: 37.1, longitude: 127.1 },
      { latitude: 37.4, longitude: 127.3 },
      { latitude: 37.2, longitude: 127.4 },
      { latitude: 37.1, longitude: 127.1 },
    ];
    const { native, adapter, initialize, onReady, flushFrames } = setup(
      true,
      { initialized: false },
      {
        stops: [],
        paths: [],
        fullPath,
      },
    );
    expect([native.map.center.lat(), native.map.center.lng()]).toEqual([37.1, 127.1]);
    expect(native.lines).toHaveLength(1);
    const fullLine = native.lines[0]!;
    expect(fullLine).toMatchObject({ strokeColor: "#5B6472", strokeOpacity: 0, zIndex: 1 });
    expect(fullLine.path.map((point) => ({ latitude: point.lat(), longitude: point.lng() }))).toEqual(fullPath);
    initialize();
    expect(native.map.fittedCoordinates.map((point) => ({ latitude: point.lat(), longitude: point.lng() }))).toEqual(
      fullPath,
    );
    native.events.find((event) => event.name === "idle")!.listener();
    flushFrames();
    expect(fullLine.strokeOpacity).toBe(0.95);
    expect(onReady).not.toHaveBeenCalled();
    flushFrames();
    expect(onReady).toHaveBeenCalledTimes(1);
    adapter.update(null, "DOWN", []);
    native.map.zoom = 15;
    native.events.find((event) => event.name === "zoom_changed")!.listener();
    expect(fullLine.strokeColor).toBe("#5B6472");
    expect(visibleArrowDirections(native)).toEqual([]);
    adapter.destroy();
    expect(native.lineMaps.get(fullLine)).toBeNull();
    expect(native.map.destroyed).toBe(true);
  });

  it("한 방향만 분리됐으면 전체 도로를 중립색으로 보완하되 분리된 방향에만 화살표를 붙인다", () => {
    const original = loadPreviewRouteMapData(boardMock)!;
    const up = original.paths.find((path) => path.direction === "UP")!;
    const fullPath = original.paths.flatMap((path) => path.coordinates);
    const { native, adapter, flushFrames } = setup(true, {}, { paths: [up], fullPath });
    expect(native.lines).toHaveLength(2);
    native.events.find((event) => event.name === "idle")!.listener();
    flushFrames();
    flushFrames();
    native.map.zoom = 15;
    native.events.find((event) => event.name === "zoom_changed")!.listener();
    expect(visibleArrowDirections(native)).toEqual(["UP"]);
    const fullLine = native.lines[1]!;
    expect(fullLine.strokeColor).toBe("#5B6472");
    adapter.update(null, "DOWN", []);
    expect(fullLine).toMatchObject({ strokeColor: "#5B6472", strokeOpacity: 0.95 });
    adapter.destroy();
    expect(native.lines.every((line) => native.lineMaps.get(line) === null)).toBe(true);
  });

  it("두 방향 경로가 모두 있으면 전체 형상을 중복해서 그리지 않는다", () => {
    const original = loadPreviewRouteMapData(boardMock)!;
    const { native } = setup(true, {}, { fullPath: original.paths.flatMap((path) => path.coordinates) });
    expect(native.lines).toHaveLength(2);
  });

  it("방향별 도로가 없으면 차량을 최신 보고 정류장에 두고 진행률과 방향 응답이 바뀌어도 임의로 보간하지 않는다", () => {
    const { native, data, adapter, onVehicleSelect } = setup(true, { reducedMotion: true }, { paths: [] });
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    const firstStop = data.stops.find(
      (stop) => stop.direction === "UP" && stop.sequence === view.vehicle.currentStopSequence,
    )!;
    adapter.update(null, "UP", [view]);
    const bus = native.markers.find((marker) => marker.options.icon.content.dataset.mapVehicleKey === view.key)!;
    const button = bus.options.icon.content as unknown as ElementStub;
    const center = native.map.center;
    const zoom = native.map.zoom;
    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.5 }]);
    expect([bus.options.position.lat(), bus.options.position.lng()]).toEqual([
      firstStop.coordinate.latitude,
      firstStop.coordinate.longitude,
    ]);

    const nextStop = data.stops.find((stop) => stop.direction === "UP" && stop.sequence !== firstStop.sequence)!;
    adapter.update(null, "UP", [
      {
        ...view,
        vehicle: {
          ...view.vehicle,
          currentStopSequence: nextStop.sequence,
          stopName: "최신 정류장",
          seat: { kind: "EXACT", remaining: 7 },
        },
      },
    ]);
    // 공유 motion에 이전 응답의 프레임이 남아 있어도 최근 관측 위치를 사용한다.
    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.75 }]);
    expect([bus.options.position.lat(), bus.options.position.lng()]).toEqual([
      nextStop.coordinate.latitude,
      nextStop.coordinate.longitude,
    ]);
    expect(button.getAttribute("aria-label")).toBe("최신 정류장 부근 버스, 현재 잔여 좌석 7석");
    button.listeners.get("click")!(new Event("click"));
    expect(onVehicleSelect).toHaveBeenLastCalledWith({ direction: "UP", sequence: nextStop.sequence }, view.key);

    const opposite = data.stops.find((stop) => stop.direction === "DOWN")!;
    adapter.update(null, "DOWN", [
      { ...view, vehicle: { ...view.vehicle, direction: "DOWN", currentStopSequence: opposite.sequence } },
    ]);
    adapter.moveVehicles([{ key: view.key, sequencePosition: opposite.sequence + 0.5 }]);
    expect([bus.options.position.lat(), bus.options.position.lng()]).toEqual([
      opposite.coordinate.latitude,
      opposite.coordinate.longitude,
    ]);
    expect(native.map.center).toBe(center);
    expect(native.map.zoom).toBe(zoom);
    expect(bus.options.icon.content).toBe(button);
    adapter.focusVehicle(view.key);
    expect([native.map.center.lat(), native.map.center.lng()]).toEqual([
      opposite.coordinate.latitude,
      opposite.coordinate.longitude,
    ]);
  });

  it("방향 도로가 없는 차량의 새 정류장 좌표를 모르면 이전 핀과 대기 포커스를 제거하고 좌표가 돌아오면 다시 표시한다", () => {
    const original = loadPreviewRouteMapData(boardMock)!;
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    const missingStop = original.stops.find(
      (stop) => stop.direction === "UP" && stop.sequence !== view.vehicle.currentStopSequence,
    )!;
    const { native, adapter, pendingFrames, windowListeners } = setup(
      true,
      {},
      {
        paths: [],
        stops: original.stops.filter((stop) => stop !== missingStop),
      },
    );
    adapter.update(null, "UP", [view]);
    const bus = native.markers.find((marker) => marker.options.icon.content.dataset.mapVehicleKey === view.key)!;
    adapter.focusVehicle(view.key);
    expect(pendingFrames.size).toBe(1);
    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, currentStopSequence: missingStop.sequence } }]);
    expect(bus.map).toBeNull();
    expect(pendingFrames.size).toBe(0);
    expect(windowListeners.size).toBe(0);
    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence }]);
    expect(bus.map).toBeNull();
    adapter.update(null, "UP", [view]);
    const replacement = native.markers.filter(
      (marker) => marker.options.icon.content.dataset.mapVehicleKey === view.key,
    );
    expect(replacement).toHaveLength(2);
    expect(replacement[0]?.map).toBeNull();
    expect(replacement[1]?.map).toBe(native.map);
  });

  it("축소에서는 정류장을 숨기고 선택 버튼은 확대와 단일 선택값을 전달한다", () => {
    const { native, adapter, onSelect } = setup();
    const marker = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).dataset.mapStopSequence === "15",
    )!;
    expect(marker.visible).toBe(false);
    const button = marker.options.icon.content as unknown as ElementStub;
    button.listeners.get("click")?.(new Event("click"));
    expect(native.map.zoom).toBe(15);
    expect(onSelect).toHaveBeenCalledWith({ direction: "UP", sequence: 15 });
    adapter.update({ direction: "UP", sequence: 15 }, "UP", []);
    expect(marker.visible).toBe(true);
    expect(button.attributes.get("aria-pressed")).toBe("true");
  });
  it("목록과 같은 진행률로 핀을 옮기고 잔여석 0과 모름을 구분한다", () => {
    const { native, data, adapter } = setup();
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, seat: { kind: "EXACT", remaining: 0 } } }]);
    const bus = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).className === "bus",
    )!;
    const content = bus.options.icon.content as unknown as ElementStub;
    expect(content.attributes.get("aria-label")).toContain("0석");
    adapter.moveVehicles([{ key: view.key, sequencePosition: data.stops[0]!.sequence }]);
    expect(bus.options.position.lat()).toBe(data.stops[0]?.coordinate.latitude);
    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, seat: { kind: "UNKNOWN" } } }]);
    expect(content.attributes.get("aria-label")).toContain("정보 없음");
    adapter.update(null, "DOWN", []);
    expect(bus.map).toBeNull();
  });
  it("차량 버튼은 재사용하며 폴링으로 갱신된 방향과 정류장을 카메라 이동 없이 전달한다", () => {
    const { native, data, adapter, onSelect, onVehicleSelect } = setup();
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [view]);
    const bus = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).dataset.mapVehicleKey === view.key,
    )!;
    const button = bus.options.icon.content as unknown as ElementStub;
    const [count, pin] = button.children;
    const image = pin!.children[0];
    const click = button.listeners.get("click")!;
    const event = new Event("click");
    const stopPropagation = jest.spyOn(event, "stopPropagation");
    const center = native.map.center;
    const zoom = native.map.zoom;
    const setCenter = jest.spyOn(native.map, "setCenter");
    const setZoom = jest.spyOn(native.map, "setZoom");
    const fitBounds = jest.spyOn(native.map, "fitBounds");

    expect(button.tagName).toBe("BUTTON");
    expect(button.type).toBe("button");
    expect(button.attributes.has("role")).toBe(false);
    expect(button.attributes.get("aria-label")).toContain("부근 버스");
    expect(bus.options.clickable).toBe(true);
    click(event);
    expect(stopPropagation).toHaveBeenCalledTimes(1);
    expect(onVehicleSelect).toHaveBeenLastCalledWith(
      { direction: "UP", sequence: view.vehicle.currentStopSequence },
      view.key,
    );

    const nextStop = data.stops.find(
      (stop) => stop.direction === "UP" && stop.sequence !== view.vehicle.currentStopSequence,
    )!;
    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, currentStopSequence: nextStop.sequence } }]);
    adapter.moveVehicles([{ key: view.key, sequencePosition: nextStop.sequence + 0.5 }]);
    click(new Event("click"));
    expect(onVehicleSelect).toHaveBeenLastCalledWith({ direction: "UP", sequence: nextStop.sequence }, view.key);

    const oppositeStop = data.stops.find((stop) => stop.direction === "DOWN")!;
    adapter.update(null, "DOWN", [
      { ...view, vehicle: { ...view.vehicle, direction: "DOWN", currentStopSequence: oppositeStop.sequence } },
    ]);
    click(new Event("click"));
    expect(onVehicleSelect).toHaveBeenCalledTimes(3);
    expect(onVehicleSelect).toHaveBeenLastCalledWith({ direction: "DOWN", sequence: oppositeStop.sequence }, view.key);
    expect(onSelect).not.toHaveBeenCalled();
    expect(native.map.center).toBe(center);
    expect(native.map.zoom).toBe(zoom);
    expect(setCenter).not.toHaveBeenCalled();
    expect(setZoom).not.toHaveBeenCalled();
    expect(fitBounds).not.toHaveBeenCalled();
    expect(bus.options.icon.content).toBe(button);
    expect(button.children[0]).toBe(count);
    expect(button.children[1]).toBe(pin);
    expect(pin!.children[0]).toBe(image);
    expect(button.listeners.get("click")).toBe(click);
  });
  it("최초 이동이 끝나도 핀의 보간 좌표를 계속 추적하고 줌과 마커 DOM을 유지한다", () => {
    const { native, adapter, onSelect, onVehicleSelect, flushFrames, windowListeners } = setup();
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [view]);
    const bus = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).dataset.mapVehicleKey === view.key,
    )!;
    const button = bus.options.icon.content as unknown as ElementStub;
    const [count, pin] = button.children;
    const image = pin!.children[0];
    const setIcon = jest.spyOn(bus, "setIcon");
    const setMap = jest.spyOn(bus, "setMap");
    native.map.center = new CoordinateStub(37.9, 126.7);
    native.map.zoom = 17.25;
    const from = native.map.center;
    const setZoom = jest.spyOn(native.map, "setZoom");
    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.25 }]);
    const initialTarget = bus.options.position;

    adapter.focusVehicle(view.key);
    expect(native.map.center).toBe(from);
    flushFrames();
    flushFrames(280);
    expect(native.map.center.lat()).toBeLessThan(from.lat());
    expect(native.map.center.lat()).toBeGreaterThan(initialTarget.lat());
    expect(button.focus).not.toHaveBeenCalled();

    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.75 }]);
    const latestTarget = bus.options.position;
    expect([latestTarget.lat(), latestTarget.lng()]).not.toEqual([initialTarget.lat(), initialTarget.lng()]);
    adapter.resize(400, 500);
    flushFrames(280);
    expect(native.map.center.lat()).toBeCloseTo(latestTarget.lat(), 12);
    expect(native.map.center.lng()).toBeCloseTo(latestTarget.lng(), 12);
    expect(native.map.zoom).toBe(17.25);
    expect(setZoom).not.toHaveBeenCalled();
    expect(button.focus).toHaveBeenCalledTimes(1);
    expect(button.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(onSelect).not.toHaveBeenCalled();
    expect(onVehicleSelect).not.toHaveBeenCalled();
    expect(bus.options.icon.content).toBe(button);
    expect(button.children).toEqual([count, pin]);
    expect(pin!.children[0]).toBe(image);
    expect(setIcon).not.toHaveBeenCalled();
    expect(setMap).not.toHaveBeenCalled();
    expect(windowListeners.size).toBe(0);

    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.9 }]);
    expect(native.map.center.lat()).toBeCloseTo(bus.options.position.lat(), 12);
    expect(native.map.center.lng()).toBeCloseTo(bus.options.position.lng(), 12);
    expect(native.map.zoom).toBe(17.25);
    expect(button.focus).toHaveBeenCalledTimes(1);
  });
  it("동작 줄이기를 사용하면 현재 표시된 차량 위치로 즉시 이동하고 포커스한다", () => {
    const { native, adapter, pendingFrames, windowListeners } = setup(true, { reducedMotion: true });
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [view]);
    const bus = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).dataset.mapVehicleKey === view.key,
    )!;
    const button = bus.options.icon.content as unknown as ElementStub;
    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.5 }]);
    adapter.focusVehicle(view.key);
    expect(native.map.center.lat()).toBeCloseTo(bus.options.position.lat(), 12);
    expect(native.map.center.lng()).toBeCloseTo(bus.options.position.lng(), 12);
    expect(native.map.zoom).toBe(16);
    expect(button.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(pendingFrames.size).toBe(0);
    expect(windowListeners.size).toBe(0);
    adapter.moveVehicles([{ key: view.key, sequencePosition: view.vehicle.currentStopSequence + 0.75 }]);
    expect(native.map.center.lat()).toBeCloseTo(bus.options.position.lat(), 12);
    expect(native.map.center.lng()).toBeCloseTo(bus.options.position.lng(), 12);
  });

  it("다른 버스로 추적을 전환하고 이동이 끝난 뒤에도 지도 입력과 차량 제거로 추적을 멈춘다", () => {
    for (const interruption of [
      "wheel",
      "touchstart",
      "pointerdown",
      "keydown",
      "null",
      "remove",
      "direction",
      "destroy",
    ]) {
      const { native, adapter, element, pendingFrames } = setup(true, { reducedMotion: true });
      const views = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP").slice(0, 2);
      const [first, second] = views;
      if (first === undefined || second === undefined) throw new Error("추적할 차량 두 대가 필요합니다");
      adapter.update(null, "UP", views);
      adapter.focusVehicle(first.key);
      adapter.focusVehicle(second.key);
      const center = native.map.center;
      adapter.moveVehicles([{ key: first.key, sequencePosition: first.vehicle.currentStopSequence + 0.5 }]);
      expect(native.map.center.lat()).toBe(center.lat());
      expect(native.map.center.lng()).toBe(center.lng());
      adapter.moveVehicles([{ key: second.key, sequencePosition: second.vehicle.currentStopSequence + 0.5 }]);
      expect([native.map.center.lat(), native.map.center.lng()]).not.toEqual([center.lat(), center.lng()]);

      if (interruption === "null") adapter.focusVehicle(null);
      else if (interruption === "remove") adapter.update(null, "UP", [first]);
      else if (interruption === "direction") adapter.update(null, "DOWN", views);
      else if (interruption === "destroy") adapter.destroy();
      else element.captureListeners.get(interruption)?.(new Event(interruption));
      const stopped = native.map.center;
      adapter.moveVehicles([{ key: second.key, sequencePosition: second.vehicle.currentStopSequence + 0.75 }]);
      expect(native.map.center).toBe(stopped);
      expect(element.captureListeners.size).toBe(0);
      if (interruption !== "destroy") adapter.destroy();
      expect(pendingFrames.size).toBe(0);
    }
  });
  it("차량 선택은 포커스와 별개로 유지하고 폴링·선택 변경·해제에도 같은 마커를 사용한다", () => {
    const { native, adapter, onVehicleSelect } = setup(true, { reducedMotion: true });
    const views = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP").slice(0, 2);
    const [first, second] = views;
    expect(first).toBeDefined();
    expect(second).toBeDefined();
    adapter.update(null, "UP", views, first!.key);
    const buses = views.map((view) =>
      native.markers.find(
        (marker) => (marker.options.icon.content as unknown as ElementStub).dataset.mapVehicleKey === view.key,
      )!,
    );
    const [firstBus, secondBus] = buses;
    const button = firstBus!.options.icon.content as unknown as ElementStub;
    const [count, pin] = button.children;
    const image = pin!.children[0];
    const iconUpdates = buses.map((bus) => jest.spyOn(bus, "setIcon"));
    const mapUpdates = buses.map((bus) => jest.spyOn(bus, "setMap"));
    expect(button.attributes.get("aria-pressed")).toBe("true");
    expect(firstBus!.options.zIndex).toBe(30);
    expect(secondBus!.options.zIndex).toBe(20);

    adapter.update(
      null,
      "UP",
      views.map((view) => ({ ...view, vehicle: { ...view.vehicle, seat: { kind: "EXACT", remaining: 0 } } })),
      first!.key,
    );
    adapter.focusVehicle(first!.key, { focusMarker: false });
    expect(native.map.zoom).toBe(16);
    expect(button.focus).not.toHaveBeenCalled();
    expect(button.attributes.get("aria-pressed")).toBe("true");
    expect(count!.textContent).toBe("현재 0석");
    adapter.focusVehicle(null);
    expect(button.attributes.get("aria-pressed")).toBe("true");

    adapter.update(null, "UP", views, second!.key);
    expect(button.attributes.get("aria-pressed")).toBe("false");
    expect(firstBus!.options.zIndex).toBe(20);
    expect(secondBus!.options.icon.content.getAttribute("aria-pressed")).toBe("true");
    expect(secondBus!.options.zIndex).toBe(30);
    adapter.update(null, "UP", views);
    expect(secondBus!.options.icon.content.getAttribute("aria-pressed")).toBe("false");
    expect(secondBus!.options.zIndex).toBe(20);
    expect(onVehicleSelect).not.toHaveBeenCalled();
    expect(button.children).toEqual([count, pin]);
    expect(pin!.children[0]).toBe(image);
    expect(iconUpdates.every((update) => update.mock.calls.length === 0)).toBe(true);
    expect(mapUpdates.every((update) => update.mock.calls.length === 0)).toBe(true);
  });
  it("차량을 확대하면서 화면상 거리는 계속 줄이고 같은 버스도 다시 확대할 수 있다", () => {
    const { native, adapter, flushFrames } = setup();
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [view], view.key);
    const bus = native.markers.find(
      (marker) => (marker.options.icon.content as unknown as ElementStub).dataset.mapVehicleKey === view.key,
    )!;
    const button = bus.options.icon.content as unknown as ElementStub;
    const target = bus.options.position;
    native.map.center = new CoordinateStub(37.5, 127);
    native.map.zoom = 10;
    const setZoom = jest.spyOn(native.map, "setZoom");
    // 같은 지리적 거리라도 줌 한 단계마다 화면상 거리는 두 배가 된다.
    const screenDistance = () =>
      Math.hypot(native.map.center.lat() - target.lat(), native.map.center.lng() - target.lng()) * 2 ** native.map.zoom;
    const initialDistance = screenDistance();
    adapter.focusVehicle(view.key);
    flushFrames();
    expect(setZoom).not.toHaveBeenCalled();
    flushFrames(140);
    const firstDistance = screenDistance();
    expect(native.map.zoom).toBeGreaterThan(10);
    expect(native.map.zoom).toBeLessThan(16);
    expect(firstDistance).toBeGreaterThan(0);
    expect(firstDistance).toBeLessThan(initialDistance);
    flushFrames(140);
    expect(screenDistance()).toBeLessThan(firstDistance);
    flushFrames(280);
    expect(native.map.zoom).toBe(16);
    expect(native.map.center.lat()).toBeCloseTo(target.lat(), 12);
    expect(native.map.center.lng()).toBeCloseTo(target.lng(), 12);
    expect(button.focus).toHaveBeenCalledTimes(1);
    const zooms = setZoom.mock.calls.map(([zoom]) => zoom);
    expect(zooms.some((zoom) => !Number.isInteger(zoom))).toBe(true);
    expect(new Set(zooms).size).toBe(zooms.length);
    expect(setZoom.mock.calls.at(-1)).toEqual([16, false]);

    native.map.zoom = 14;
    adapter.focusVehicle(view.key);
    flushFrames();
    flushFrames(560);
    expect(native.map.zoom).toBe(16);
    expect(button.focus).toHaveBeenCalledTimes(2);
  });
  it("이미지 타일 fallback에서는 같은 정수 줌을 반복 설정하지 않는다", () => {
    const { native, adapter, flushFrames } = setup(false);
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [view]);
    native.map.zoom = 10;
    const setZoom = jest.spyOn(native.map, "setZoom");
    adapter.focusVehicle(view.key);
    flushFrames();
    flushFrames(1);
    expect(setZoom).not.toHaveBeenCalled();
    flushFrames(139);
    flushFrames(140);
    flushFrames(280);
    const zooms = setZoom.mock.calls.map(([zoom]) => zoom);
    expect(zooms.every(Number.isInteger)).toBe(true);
    expect(new Set(zooms).size).toBe(zooms.length);
    expect(native.map.zoom).toBe(16);
  });
  it("사용자 조작이나 다른 지도 동작·차량 제거는 대기 중인 차량 이동과 포커스를 취소한다", () => {
    const interruptions = [
      "wheel",
      "touchstart",
      "pointerdown",
      "keydown",
      "null",
      "focus",
      "location",
      "remove",
      "destroy",
    ];
    for (const interruption of interruptions) {
      const { native, adapter, element, flushFrames, windowListeners } = setup();
      const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
      adapter.update(null, "UP", [view]);
      const bus = native.markers.find(
        (item) => (item.options.icon.content as unknown as ElementStub).dataset.mapVehicleKey === view.key,
      )!;
      const button = bus.options.icon.content as unknown as ElementStub;
      const setCenter = jest.spyOn(native.map, "setCenter");
      const setZoom = jest.spyOn(native.map, "setZoom");
      adapter.focusVehicle(view.key);
      flushFrames();
      flushFrames(160);
      if (interruption === "null") adapter.focusVehicle(null);
      else if (interruption === "focus") adapter.focus({ direction: "UP", sequence: 15 });
      else if (interruption === "location") adapter.showLocation({ latitude: 37.4, longitude: 127.1 });
      else if (interruption === "remove") adapter.update(null, "UP", []);
      else if (interruption === "destroy") adapter.destroy();
      else element.captureListeners.get(interruption)?.(new Event(interruption));
      const center = native.map.center;
      const zoom = native.map.zoom;
      const centerUpdates = setCenter.mock.calls.length;
      const zoomUpdates = setZoom.mock.calls.length;
      flushFrames(400);
      expect(native.map.center).toBe(center);
      expect(setCenter).toHaveBeenCalledTimes(centerUpdates);
      expect(native.map.zoom).toBe(zoom);
      expect(setZoom).toHaveBeenCalledTimes(zoomUpdates);
      expect(button.focus).not.toHaveBeenCalled();
      expect(windowListeners.size).toBe(0);
      if (interruption !== "destroy") adapter.destroy();
    }
  });
  it("잔여석만 갱신해도 차량 마커와 핀·이미지 DOM을 그대로 사용한다", () => {
    const { native, adapter } = setup();
    const view = liveVehicleViewsFor(routeWatchMock, routeWatchMock.vehicles, "UP")[0]!;
    adapter.update(null, "UP", [view]);
    const bus = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).className === "bus",
    )!;
    const content = bus.options.icon.content as unknown as ElementStub;
    const [count, pin] = content.children;
    const image = pin!.children[0];
    expect(image).toBeDefined();
    const setMap = jest.spyOn(bus, "setMap");
    const setIcon = jest.spyOn(bus, "setIcon");

    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, seat: { kind: "EXACT", remaining: 0 } } }]);
    expect(count!.textContent).toBe("현재 0석");
    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, seat: { kind: "UNKNOWN" } } }]);
    expect(count!.textContent).toBe("–");
    expect(content.children[0]).toBe(count);
    expect(content.children[1]).toBe(pin);
    expect(pin!.children[0]).toBe(image);
    expect(setMap).not.toHaveBeenCalled();
    expect(setIcon).not.toHaveBeenCalled();
    expect(native.markers.filter((marker) => marker.options.icon.content === bus.options.icon.content)).toEqual([bus]);
  });
  it("현재 위치는 마커 하나만 표시하며 정리 시 SDK 리스너와 지도를 제거한다", () => {
    const { native, adapter } = setup();
    adapter.showLocation({ latitude: 37.4, longitude: 127.1 });
    expect(native.circles).toHaveLength(0);
    expect(native.map.zoom).toBe(15);
    adapter.destroy();
    expect(native.sdk.Event.removeListener).toHaveBeenCalledTimes(native.events.length);
    expect(native.markers.every((marker) => marker.map === null)).toBe(true);
    expect(native.map.destroyed).toBe(true);
  });
  it("인증 실패 후 native 이벤트와 자원 정리의 예외를 지도 바깥으로 전파하지 않는다", () => {
    const { native, adapter, onError } = setup();
    native.damage();
    expect(() => native.events.find((event) => event.name === "zoom_changed")?.listener()).not.toThrow();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(() => adapter.destroy()).not.toThrow();
    // 첫 SDK 정리에 실패해도 초기화 리스너까지 모두 정리를 시도한다.
    expect(native.sdk.Event.removeListener).toHaveBeenCalledTimes(native.events.length);
  });
});
