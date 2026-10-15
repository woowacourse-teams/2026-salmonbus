import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { boardMock } from "@/shared/api/routeForecast.mock";
import { liveVehicleViewsFor } from "../components/liveVehiclePolicy";
import { liveVehiclesMock } from "@/shared/api/routeForecast.mock";
import { loadPreviewRouteMapData } from "./routeMapPreview";
import type { NaverCoordinate, NaverMap, NaverMaps, NaverMarker } from "./naverSdk";
import { createNaverRouteMap } from "./naverMapAdapter";

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
  addEventListener(name: string, listener: (event: Event) => void) {
    this.listeners.set(name, listener);
  }
  removeEventListener(name: string) {
    this.listeners.delete(name);
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
  const circles: ConstructorParameters<NaverMaps["Circle"]>[0][] = [];
  const events: { name: string; listener: () => void }[] = [];
  class MapStub implements NaverMap {
    zoom = 10;
    center: NaverCoordinate = new CoordinateStub(0, 0);
    size = new SizeStub(400, 350);
    fits = 0;
    fitMaxZoom = 0;
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
      }
      setOptions(options: Parameters<InstanceType<NaverMaps["Polyline"]>["setOptions"]>[0]) {
        checkSdk();
        Object.assign(this.options, options);
      }
      setMap() {}
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

function setup(glSupported = true, { initialized = true } = {}) {
  const pendingFrames = new Map<number, FrameRequestCallback>();
  const pendingTimers = new Map<number, { remaining: number; callback: () => void }>();
  let frameId = 0;
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
    },
  });
  const flushFrames = () => {
    const callbacks = [...pendingFrames.values()];
    pendingFrames.clear();
    callbacks.forEach((callback) => callback(16));
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
    value: { createElement: () => new ElementStub() },
  });
  const native = createRecordingSdk(glSupported);
  const data = loadPreviewRouteMapData(boardMock)!;
  const onSelect = jest.fn<(selection: { direction: "UP" | "DOWN"; sequence: number }) => void>();
  const onError = jest.fn();
  const onReady = jest.fn();
  const element = new ElementStub();
  const adapter = createNaverRouteMap(
    native.sdk,
    element as unknown as HTMLElement,
    boardMock,
    data,
    onSelect,
    onError,
    onReady,
  );
  const initialize = () => native.events.find((event) => event.name === "init")?.listener();
  if (initialized) initialize();
  return {
    native,
    data,
    onSelect,
    onError,
    onReady,
    adapter,
    element,
    initialize,
    pendingFrames,
    flushFrames,
    pendingTimers,
    advanceTimers,
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
  for (const interaction of ["focus", "location"] as const) {
    it(`GL 초기화 전에 ${interaction === "focus" ? "정류장을 선택하면" : "현재 위치를 찾으면"} 뒤늦은 전체 경로 맞춤으로 덮어쓰지 않는다`, () => {
      const { native, adapter, initialize } = setup(true, { initialized: false });
      if (interaction === "focus") adapter.focus({ direction: "UP", sequence: 15 });
      else adapter.showLocation({ latitude: 37.4, longitude: 127.1 }, 45);
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
    element.listeners.get("touchend")!({ timeStamp: 100 } as Event);
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
    const view = liveVehicleViewsFor(boardMock, liveVehiclesMock, "UP")[0]!;
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
  it("잔여석만 갱신해도 차량 마커와 핀·이미지 DOM을 그대로 사용한다", () => {
    const { native, adapter } = setup();
    const view = liveVehicleViewsFor(boardMock, liveVehiclesMock, "UP")[0]!;
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
    expect(count!.textContent).toBe("0석");
    adapter.update(null, "UP", [{ ...view, vehicle: { ...view.vehicle, seat: { kind: "UNKNOWN" } } }]);
    expect(count!.textContent).toBe("–");
    expect(content.children[0]).toBe(count);
    expect(content.children[1]).toBe(pin);
    expect(pin!.children[0]).toBe(image);
    expect(setMap).not.toHaveBeenCalled();
    expect(setIcon).not.toHaveBeenCalled();
    expect(native.markers.filter((marker) => marker.options.icon.content === bus.options.icon.content)).toEqual([bus]);
  });
  it("현재 위치는 정확도 원과 함께 표시하며 정리 시 SDK 리스너와 지도를 제거한다", () => {
    const { native, adapter } = setup();
    adapter.showLocation({ latitude: 37.4, longitude: 127.1 }, 45);
    expect(native.circles[0]?.radius).toBe(45);
    expect(native.map.zoom).toBe(15);
    adapter.destroy();
    expect(native.sdk.Event.removeListener).toHaveBeenCalledTimes(3);
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
    expect(native.sdk.Event.removeListener).toHaveBeenCalledTimes(3);
  });
});
