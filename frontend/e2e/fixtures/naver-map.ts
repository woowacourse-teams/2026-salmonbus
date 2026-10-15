import type { NaverCoordinate, NaverMap, NaverMaps } from "../../src/pages/verdict-board/map/naverSdk";
import { test as apiTest, expect } from "./api";

interface MapSnapshot {
  zoom: number;
  latitude: number;
  longitude: number;
}

type NativeFailure = "all" | "update" | "resize" | "frame";

interface BrowserMapControls {
  initialize: () => void;
  finishIdle: () => void;
  setZoom: (zoom: number) => void;
  panToStop: (sequence: number) => void;
  snapshot: () => MapSnapshot;
  failNative: (operation: NativeFailure) => void;
}

interface MapFixture {
  initialize: () => Promise<void>;
  finishIdle: () => Promise<void>;
  setZoom: (zoom: number) => Promise<void>;
  panToStop: (sequence: number) => Promise<void>;
  snapshot: () => Promise<MapSnapshot>;
  failNative: (operation: NativeFailure) => Promise<void>;
}

type TestMapWindow = { __naverMapTest: BrowserMapControls };

export const test = apiTest.extend<{
  naverMap: MapFixture;
  holdMapInitialization: boolean;
  holdMapIdle: boolean;
}>({
  holdMapInitialization: [false, { option: true }],
  holdMapIdle: [false, { option: true }],
  naverMap: [
    async ({ page, holdMapInitialization, holdMapIdle }, use) => {
      await page.addInitScript(
        ({ holdMapInitialization, holdMapIdle }) => {
          type Size = InstanceType<NaverMaps["Size"]>;
          type Point = InstanceType<NaverMaps["Point"]>;
          interface Drawable {
            draw: () => void;
          }
          interface Listener {
            target: FakeMap;
            name: string;
            callback: () => void;
          }

          class LatLng implements NaverCoordinate {
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
          class MapSize {
            constructor(
              public width: number,
              public height: number,
            ) {}
          }
          class MapPoint {
            constructor(
              public x: number,
              public y: number,
            ) {}
          }

          class FakeMap implements NaverMap {
            readonly markers = new Set<FakeMarker>();
            readonly drawings = new Set<Drawable>();
            readonly listeners = new Set<Listener>();
            readonly layer: HTMLDivElement;
            private zoom: number;
            private center: NaverCoordinate;
            private size: Size;
            private scale = 1;
            private glInitialized = false;
            private holdIdle = holdMapIdle;
            private zoomFrame = 0;
            private setSizeCount = 0;
            private removeInputListeners: () => void;
            constructor(
              private element: HTMLElement,
              private options: ConstructorParameters<NaverMaps["Map"]>[1],
            ) {
              this.zoom = options.zoom;
              this.center = options.center;
              this.size = options.size;
              this.layer = document.createElement("div");
              this.layer.dataset.testid = "naver-map-test-double";
              this.layer.style.cssText =
                "position:relative;width:100%;height:100%;overflow:hidden;background:#F7F8F6;background-image:linear-gradient(25deg,transparent 47%,#FFF0C9 47%,#FFF0C9 49%,transparent 49%),repeating-linear-gradient(90deg,transparent 0,transparent 79px,#E5EBE3 80px),repeating-linear-gradient(0deg,transparent 0,transparent 79px,#E5EBE3 80px)";
              const caption = document.createElement("span");
              caption.textContent = "지도 SDK 테스트 대역";
              caption.style.cssText = "position:absolute;left:8px;bottom:8px;color:#68736D;font-size:10px";
              this.layer.append(caption);
              element.append(this.layer);
              const onWheel = (event: WheelEvent) => {
                if (!this.options.scrollWheel || event.deltaY === 0) return;
                event.preventDefault();
                const bounds = this.element.getBoundingClientRect();
                this.animateZoom(
                  -Math.sign(event.deltaY),
                  new MapPoint(event.clientX - bounds.left, event.clientY - bounds.top),
                );
              };
              const onDoubleClick = (event: MouseEvent) => {
                if (this.options.disableDoubleClickZoom) return;
                event.preventDefault();
                const bounds = this.element.getBoundingClientRect();
                this.animateZoom(1, new MapPoint(event.clientX - bounds.left, event.clientY - bounds.top));
              };
              element.addEventListener("wheel", onWheel, { passive: false });
              element.addEventListener("dblclick", onDoubleClick);
              this.removeInputListeners = () => {
                element.removeEventListener("wheel", onWheel);
                element.removeEventListener("dblclick", onDoubleClick);
              };
              this.setSize(this.size);
              (window as unknown as TestMapWindow).__naverMapTest = {
                initialize: () => this.initialize(),
                finishIdle: () => {
                  this.holdIdle = false;
                  this.emit("idle");
                },
                setZoom: (zoom) => this.setZoom(zoom),
                panToStop: (sequence) => {
                  const marker = [...this.markers].find(
                    (item) => item.content.dataset.mapStopSequence === String(sequence),
                  );
                  if (!marker) throw new Error(`정류장 ${sequence}가 지도에 없습니다`);
                  this.setCenter(marker.position);
                },
                snapshot: () => ({ zoom: this.zoom, latitude: this.center.lat(), longitude: this.center.lng() }),
                failNative: (operation) => {
                  const fail = () => {
                    throw new Error("지도 SDK 내부 구현을 사용할 수 없습니다");
                  };
                  if (operation === "all") this.getZoom = fail;
                  if (operation === "all" || operation === "resize") this.setSize = fail;
                  this.destroy = fail;
                  this.markers.forEach((marker) => {
                    if (operation === "all" || operation === "update") marker.setZIndex = fail;
                    if (operation === "all" || operation === "frame") marker.setPosition = fail;
                    marker.setMap = fail;
                  });
                  const sdk = window.naver?.maps;
                  if (sdk === undefined) throw new Error("지도 SDK 테스트 대역이 없습니다");
                  sdk.Event.removeListener = fail;
                },
              };
              queueMicrotask(() => {
                if (!holdMapInitialization) this.initialize();
              });
            }
            private initialize() {
              if (this.glInitialized || !this.layer.isConnected) return;
              this.glInitialized = true;
              this.draw();
              this.emit("init");
            }
            getZoom() {
              return this.zoom;
            }
            setZoom(zoom: number) {
              cancelAnimationFrame(this.zoomFrame);
              this.zoomFrame = 0;
              delete this.layer.dataset.zoomAnimating;
              this.zoom = zoom;
              this.draw();
              this.emit("zoom_changed");
              this.emit("idle");
            }
            zoomBy(delta: number, origin: NaverCoordinate) {
              const scale = 2 ** delta;
              this.center = new LatLng(
                origin.lat() + (this.center.lat() - origin.lat()) / scale,
                origin.lng() + (this.center.lng() - origin.lng()) / scale,
              );
              this.setZoom(this.zoom + delta);
            }
            getOptions() {
              return this.options.gl;
            }
            setOptions(options: Parameters<NaverMap["setOptions"]>[0]) {
              Object.assign(this.options, options);
            }
            private animateZoom(delta: number, point: Point) {
              cancelAnimationFrame(this.zoomFrame);
              const origin = this.getProjection().fromOffsetToCoord(point);
              const startZoom = this.zoom;
              const startCenter = this.center;
              const startedAt = performance.now();
              this.layer.dataset.zoomAnimating = "true";
              const frame = (time: number) => {
                const progress = Math.min(1, Math.max(0, (time - startedAt) / 240));
                this.zoom = startZoom + delta * progress;
                const scale = 2 ** (this.zoom - startZoom);
                this.center = new LatLng(
                  origin.lat() + (startCenter.lat() - origin.lat()) / scale,
                  origin.lng() + (startCenter.lng() - origin.lng()) / scale,
                );
                this.draw();
                this.emit("zoom_changed");
                if (progress < 1) this.zoomFrame = requestAnimationFrame(frame);
                else {
                  this.zoomFrame = 0;
                  delete this.layer.dataset.zoomAnimating;
                  this.emit("idle");
                }
              };
              this.zoomFrame = requestAnimationFrame(frame);
            }
            getCenter() {
              return this.center;
            }
            setCenter(center: NaverCoordinate) {
              this.center = center;
              this.draw();
              this.emit("idle");
            }
            getSize() {
              return this.size;
            }
            setSize(size: Size) {
              this.layer.dataset.setSizeCount = String(++this.setSizeCount);
              this.size = size;
              // 실제 SDK도 지도 요소의 크기를 픽셀 값으로 고정한다.
              this.element.style.width = `${size.width}px`;
              this.element.style.height = `${size.height}px`;
              this.draw();
            }
            fitBounds(
              coordinates: NaverCoordinate[],
              margins: { top: number; right: number; bottom: number; left: number; maxZoom: number },
            ) {
              const latitudes = coordinates.map((item) => item.lat());
              const longitudes = coordinates.map((item) => item.lng());
              const minLat = Math.min(...latitudes),
                maxLat = Math.max(...latitudes);
              const minLng = Math.min(...longitudes),
                maxLng = Math.max(...longitudes);
              this.scale =
                Math.min(
                  (this.size.width - margins.left - margins.right) / Math.max(0.001, maxLng - minLng),
                  (this.size.height - margins.top - margins.bottom) / Math.max(0.001, maxLat - minLat),
                ) / this.rendererScale();
              this.center = new LatLng((minLat + maxLat) / 2, (minLng + maxLng) / 2);
              this.setZoom(margins.maxZoom);
            }
            private rendererScale() {
              // GL 초기화 전후에는 같은 줌 값에서도 화면에 투영하는 배율이 달라진다.
              return this.options.gl && this.glInitialized ? 2 : 1;
            }
            getProjection() {
              return {
                fromCoordToOffset: (coordinate: NaverCoordinate) => {
                  const scale = this.scale * this.rendererScale() * 2 ** (this.zoom - 13);
                  return new MapPoint(
                    this.size.width / 2 + (coordinate.lng() - this.center.lng()) * scale,
                    this.size.height / 2 - (coordinate.lat() - this.center.lat()) * scale,
                  );
                },
                fromOffsetToCoord: (point: Point) => {
                  const scale = this.scale * this.rendererScale() * 2 ** (this.zoom - 13);
                  return new LatLng(
                    this.center.lat() - (point.y - this.size.height / 2) / scale,
                    this.center.lng() + (point.x - this.size.width / 2) / scale,
                  );
                },
              };
            }
            destroy() {
              cancelAnimationFrame(this.zoomFrame);
              this.removeInputListeners();
              this.layer.remove();
              this.listeners.clear();
              this.markers.clear();
              this.drawings.clear();
            }
            draw() {
              this.markers.forEach((item) => item.draw());
              this.drawings.forEach((item) => item.draw());
            }
            emit(name: string) {
              if (name === "idle" && this.holdIdle) return;
              [...this.listeners].filter((item) => item.name === name).forEach((item) => item.callback());
            }
          }

          class FakeMarker {
            readonly wrapper = document.createElement("div");
            position: NaverCoordinate;
            content: HTMLElement;
            private map: FakeMap | null = null;
            private anchor: Point;
            constructor(options: ConstructorParameters<NaverMaps["Marker"]>[0]) {
              this.position = options.position;
              this.content = options.icon.content;
              this.anchor = options.icon.anchor;
              this.wrapper.style.cssText = "position:absolute;pointer-events:none";
              this.content.style.pointerEvents = options.clickable ? "auto" : "none";
              this.wrapper.dataset.markerKind = this.content.dataset.mapStopSequence
                ? "stop"
                : this.content.dataset.mapVehicleKey !== undefined
                  ? "vehicle"
                  : this.content.getAttribute("aria-label") === "내 현재 위치"
                    ? "location"
                    : "arrow";
              this.wrapper.append(this.content);
              this.setZIndex(options.zIndex);
              this.setMap(options.map);
            }
            setMap(map: NaverMap | null) {
              this.map?.markers.delete(this);
              this.map = map as FakeMap | null;
              if (this.map === null) this.wrapper.remove();
              else {
                this.map.markers.add(this);
                this.map.layer.append(this.wrapper);
                this.draw();
              }
            }
            setPosition(position: NaverCoordinate) {
              this.position = position;
              this.draw();
            }
            setVisible(visible: boolean) {
              this.wrapper.style.display = visible ? "block" : "none";
            }
            setIcon(icon: { content: HTMLElement; anchor: Point }) {
              this.content = icon.content;
              this.anchor = icon.anchor;
              this.wrapper.replaceChildren(this.content);
              this.draw();
            }
            setZIndex(zIndex: number) {
              this.wrapper.style.zIndex = String(zIndex);
            }
            draw() {
              if (this.map === null) return;
              const point = this.map.getProjection().fromCoordToOffset(this.position);
              this.wrapper.style.left = `${point.x - this.anchor.x}px`;
              this.wrapper.style.top = `${point.y - this.anchor.y}px`;
            }
          }

          class FakePolyline {
            private map: FakeMap | null = null;
            private svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
            private line = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
            private path: NaverCoordinate[];
            constructor(options: ConstructorParameters<NaverMaps["Polyline"]>[0]) {
              this.path = options.path;
              this.svg.dataset.testid = "naver-polyline";
              this.svg.style.cssText = `position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:${options.zIndex}`;
              this.line.setAttribute("fill", "none");
              this.line.setAttribute("stroke", options.strokeColor);
              this.line.setAttribute("stroke-width", String(options.strokeWeight));
              this.line.setAttribute("stroke-opacity", String(options.strokeOpacity));
              this.line.setAttribute("stroke-linecap", "round");
              this.svg.append(this.line);
              this.setMap(options.map);
            }
            setOptions(options: Parameters<InstanceType<NaverMaps["Polyline"]>["setOptions"]>[0]) {
              this.line.setAttribute("stroke", options.strokeColor);
              this.line.setAttribute("stroke-opacity", String(options.strokeOpacity));
              this.svg.style.zIndex = String(options.zIndex);
            }
            setMap(map: NaverMap | null) {
              this.map?.drawings.delete(this);
              this.map = map as FakeMap | null;
              if (this.map === null) this.svg.remove();
              else {
                this.map.drawings.add(this);
                this.map.layer.append(this.svg);
                this.draw();
              }
            }
            draw() {
              if (this.map === null) return;
              const project = this.map.getProjection().fromCoordToOffset;
              this.line.setAttribute(
                "points",
                this.path
                  .map((coordinate) => {
                    const point = project(coordinate);
                    return `${point.x},${point.y}`;
                  })
                  .join(" "),
              );
            }
          }

          class FakeCircle {
            private map: FakeMap | null = null;
            private element = document.createElement("div");
            private center: NaverCoordinate;
            constructor(options: ConstructorParameters<NaverMaps["Circle"]>[0]) {
              this.center = options.center;
              this.element.dataset.testid = "location-accuracy";
              this.element.style.cssText = `position:absolute;width:48px;height:48px;border-radius:50%;background:#307DF61F;border:1px solid #307DF666;pointer-events:none;z-index:${options.zIndex}`;
              this.setMap(options.map);
            }
            setMap(map: NaverMap | null) {
              this.map?.drawings.delete(this);
              this.map = map as FakeMap | null;
              if (this.map === null) this.element.remove();
              else {
                this.map.drawings.add(this);
                this.map.layer.append(this.element);
                this.draw();
              }
            }
            draw() {
              if (this.map === null) return;
              const point = this.map.getProjection().fromCoordToOffset(this.center);
              this.element.style.left = `${point.x - 24}px`;
              this.element.style.top = `${point.y - 24}px`;
            }
          }

          window.naver = {
            maps: {
              glEnabled: true,
              LatLng,
              Point: MapPoint,
              Size: MapSize,
              Map: FakeMap,
              Marker: FakeMarker,
              Polyline: FakePolyline,
              Circle: FakeCircle,
              Event: {
                addListener: (target, name, callback) => {
                  const listener = { target: target as FakeMap, name, callback };
                  listener.target.listeners.add(listener);
                  return listener;
                },
                removeListener: (value) => {
                  const listener = value as Listener;
                  listener.target.listeners.delete(listener);
                },
              },
            } satisfies NaverMaps,
          };
        },
        { holdMapInitialization, holdMapIdle },
      );

      await use({
        initialize: () => page.evaluate(() => (window as unknown as TestMapWindow).__naverMapTest.initialize()),
        finishIdle: () => page.evaluate(() => (window as unknown as TestMapWindow).__naverMapTest.finishIdle()),
        setZoom: async (zoom) => {
          await page.evaluate((value) => (window as unknown as TestMapWindow).__naverMapTest.setZoom(value), zoom);
        },
        panToStop: async (sequence) => {
          await page.evaluate(
            (value) => (window as unknown as TestMapWindow).__naverMapTest.panToStop(value),
            sequence,
          );
        },
        snapshot: () => page.evaluate(() => (window as unknown as TestMapWindow).__naverMapTest.snapshot()),
        failNative: (operation) =>
          page.evaluate((value) => (window as unknown as TestMapWindow).__naverMapTest.failNative(value), operation),
      });
    },
    { auto: true },
  ],
});

export { expect };
