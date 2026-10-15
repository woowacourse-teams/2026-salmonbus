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

function createRecordingSdk() {
  let damaged = false;
  const checkSdk = () => {
    if (damaged) throw new TypeError("SDK 내부 구현이 제거됨");
  };
  const markers: MarkerStub[] = [];
  const lines: ConstructorParameters<NaverMaps["Polyline"]>[0][] = [];
  const circles: ConstructorParameters<NaverMaps["Circle"]>[0][] = [];
  const events: { name: string; listener: () => void }[] = [];
  class MapStub implements NaverMap {
    zoom = 11;
    center: NaverCoordinate = new CoordinateStub(0, 0);
    size = new SizeStub(400, 350);
    fits = 0;
    projectionOffsetX = 0;
    destroyed = false;
    constructor(_element: HTMLElement, options: ConstructorParameters<NaverMaps["Map"]>[1]) {
      this.center = options.center;
    }
    getZoom() {
      checkSdk();
      return this.zoom;
    }
    setZoom(zoom: number) {
      this.zoom = zoom;
    }
    getCenter() {
      return this.center;
    }
    setCenter(center: NaverCoordinate) {
      this.center = center;
    }
    fitBounds() {
      this.fits += 1;
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
afterEach(() => {
  if (previousDocument !== undefined) Object.defineProperty(globalThis, "document", previousDocument);
  else Reflect.deleteProperty(globalThis, "document");
});

function setup() {
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { createElement: () => new ElementStub() },
  });
  const native = createRecordingSdk();
  const data = loadPreviewRouteMapData(boardMock)!;
  const onSelect = jest.fn<(selection: { direction: "UP" | "DOWN"; sequence: number }) => void>();
  const onError = jest.fn();
  const adapter = createNaverRouteMap(
    native.sdk,
    { clientWidth: 400, clientHeight: 350 } as HTMLElement,
    boardMock,
    data,
    onSelect,
    onError,
  );
  return { native, data, onSelect, onError, adapter };
}

describe("네이버 지도 adapter", () => {
  it("확대와 이동에서 정류장 아이콘과 방향 화살표를 제거하거나 다시 만들지 않는다", () => {
    const { native, adapter } = setup();
    const idle = native.events.find((event) => event.name === "idle")!.listener;
    const zoomChanged = native.events.find((event) => event.name === "zoom_changed")!.listener;
    const markers = [...native.markers];
    const iconUpdates = markers.map((marker) => jest.spyOn(marker, "setIcon"));
    const mapUpdates = markers.map((marker) => jest.spyOn(marker, "setMap"));
    native.map.zoom = 15;
    zoomChanged();
    adapter.update(null, "DOWN", []);
    native.map.zoom = 16;
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
    expect(native.map.zoom).toBe(16);
  });
  it("축소에서는 정류장을 숨기고 선택 버튼은 확대와 단일 선택값을 전달한다", () => {
    const { native, adapter, onSelect } = setup();
    const marker = native.markers.find(
      (item) => (item.options.icon.content as unknown as ElementStub).dataset.mapStopSequence === "15",
    )!;
    expect(marker.visible).toBe(false);
    const button = marker.options.icon.content as unknown as ElementStub;
    button.listeners.get("click")?.(new Event("click"));
    expect(native.map.zoom).toBe(16);
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

    adapter.update(null, "UP", [
      {
        ...view,
        vehicle: { ...view.vehicle, seat: { kind: "EXACT", remaining: 0 } },
      },
    ]);
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
    expect(native.map.zoom).toBe(16);
    adapter.destroy();
    expect(native.sdk.Event.removeListener).toHaveBeenCalledTimes(2);
    expect(native.markers.every((marker) => marker.map === null)).toBe(true);
    expect(native.map.destroyed).toBe(true);
  });
  it("인증 실패 후 native 이벤트와 자원 정리의 예외를 지도 바깥으로 전파하지 않는다", () => {
    const { native, adapter, onError } = setup();
    native.damage();
    expect(() => native.events.find((event) => event.name === "zoom_changed")?.listener()).not.toThrow();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(() => adapter.destroy()).not.toThrow();
    // 첫 SDK 정리에 실패해도 두 번째 리스너까지 정리를 시도한다.
    expect(native.sdk.Event.removeListener).toHaveBeenCalledTimes(2);
  });
});
