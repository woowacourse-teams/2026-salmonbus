// NAVER native 객체는 지도 adapter 안에서만 사용한다.
export interface NaverCoordinate {
  lat(): number;
  lng(): number;
}

interface NaverPoint {
  x: number;
  y: number;
}
interface NaverSize {
  width: number;
  height: number;
}
export interface NaverMap {
  getOptions(key: "gl"): boolean;
  setOptions(options: {
    overlayZoomEffect: string | null;
    scrollWheel: boolean;
    disableDoubleClickZoom: boolean;
  }): void;
  getZoom(): number;
  setZoom(zoom: number, animate: boolean): void;
  zoomBy(delta: number, origin: NaverCoordinate, animate: boolean): void;
  getCenter(): NaverCoordinate;
  setCenter(center: NaverCoordinate): void;
  fitBounds(
    coordinates: NaverCoordinate[],
    margins: { top: number; right: number; bottom: number; left: number; maxZoom: number },
  ): void;
  getSize(): NaverSize;
  setSize(size: NaverSize): void;
  getProjection(): {
    fromCoordToOffset(coordinate: NaverCoordinate): NaverPoint;
    fromOffsetToCoord(point: NaverPoint): NaverCoordinate;
  };
  destroy(): void;
}

export interface NaverMarker {
  setMap(map: NaverMap | null): void;
  setPosition(position: NaverCoordinate): void;
  setVisible(visible: boolean): void;
  setIcon(icon: { content: HTMLElement; anchor: NaverPoint }): void;
  setZIndex(zIndex: number): void;
}

interface NaverOverlay {
  setMap(map: NaverMap | null): void;
}

interface NaverPolyline extends NaverOverlay {
  setOptions(options: { strokeColor: string; strokeOpacity: number; zIndex: number }): void;
}

export interface NaverMaps {
  glEnabled: boolean;
  LatLng: new (latitude: number, longitude: number) => NaverCoordinate;
  Point: new (x: number, y: number) => NaverPoint;
  Size: new (width: number, height: number) => NaverSize;
  Map: new (
    element: HTMLElement,
    options: {
      center: NaverCoordinate;
      zoom: number;
      size: NaverSize;
      zoomControl: boolean;
      scaleControl: boolean;
      mapDataControl: boolean;
      overlayZoomEffect: string | null;
      gl: boolean;
      scrollWheel: boolean;
      disableDoubleClickZoom: boolean;
    },
  ) => NaverMap;
  Marker: new (options: {
    map: NaverMap;
    position: NaverCoordinate;
    icon: { content: HTMLElement; anchor: NaverPoint };
    zIndex: number;
    clickable: boolean;
  }) => NaverMarker;
  Polyline: new (options: {
    map: NaverMap;
    path: NaverCoordinate[];
    strokeColor: string;
    strokeWeight: number;
    strokeOpacity: number;
    zIndex: number;
  }) => NaverPolyline;
  Circle: new (options: {
    map: NaverMap;
    center: NaverCoordinate;
    radius: number;
    fillColor: string;
    fillOpacity: number;
    strokeColor: string;
    strokeOpacity: number;
    strokeWeight: number;
    zIndex: number;
  }) => NaverOverlay;
  Event: {
    addListener(target: NaverMap, name: string, listener: () => void): unknown;
    removeListener(listener: unknown): void;
  };
}

declare global {
  interface Window {
    naver?: { maps: NaverMaps };
    __salmonbusNaverMapsReady?: () => void;
    navermap_authFailure?: () => void;
  }
}

let sdkPromise: Promise<NaverMaps> | null = null;
const authFailureListeners = new Set<() => void>();
let previousAuthFailure: Window["navermap_authFailure"];

function notifyAuthFailure() {
  for (const listener of [
    ...authFailureListeners,
    ...(previousAuthFailure === undefined ? [] : [previousAuthFailure]),
  ]) {
    try {
      listener();
    } catch {
      // 한 소비자의 정리 실패가 다른 지도 소비자의 인증 실패 안내를 막지 않게 한다.
    }
  }
}

export function subscribeNaverMapAuthFailure(listener: () => void): () => void {
  if (authFailureListeners.size === 0) {
    previousAuthFailure = window.navermap_authFailure;
    window.navermap_authFailure = notifyAuthFailure;
  }
  authFailureListeners.add(listener);
  return () => {
    authFailureListeners.delete(listener);
    if (authFailureListeners.size > 0) return;
    if (window.navermap_authFailure === notifyAuthFailure) {
      if (previousAuthFailure === undefined) delete window.navermap_authFailure;
      else window.navermap_authFailure = previousAuthFailure;
    }
    previousAuthFailure = undefined;
  };
}

function readyNaverMapsOf(host: Window): NaverMaps | null {
  try {
    const maps = host.naver?.maps;
    if (
      maps === undefined ||
      typeof maps.LatLng !== "function" ||
      typeof maps.Point !== "function" ||
      typeof maps.Size !== "function" ||
      typeof maps.Map !== "function" ||
      typeof maps.Marker !== "function" ||
      typeof maps.Polyline !== "function" ||
      typeof maps.Circle !== "function" ||
      typeof maps.Event?.addListener !== "function" ||
      typeof maps.Event?.removeListener !== "function"
    )
      return null;
    return maps;
  } catch {
    // 인증 실패 후 SDK가 내부 구현을 비우면 생성자 getter 자체가 실패할 수 있다.
    return null;
  }
}

export function loadNaverMaps(keyId: string): Promise<NaverMaps> {
  const existingMaps = readyNaverMapsOf(window);
  if (existingMaps?.glEnabled === true) return Promise.resolve(existingMaps);
  if (existingMaps === null && keyId.trim() === "") return Promise.reject(new Error("지도 설정을 준비 중이에요"));
  if (sdkPromise !== null) return sdkPromise;
  sdkPromise = new Promise<NaverMaps>((resolve, reject) => {
    const script = document.createElement("script");
    let finished = false;
    const finish = (maps: NaverMaps | null) => {
      if (finished) return;
      finished = true;
      window.clearTimeout(timer);
      script.onload = null;
      script.onerror = null;
      delete window.__salmonbusNaverMapsReady;
      unsubscribeAuthFailure();
      if (maps !== null) resolve(maps);
      else {
        script.remove();
        reject(new Error("지도를 불러오지 못했어요"));
      }
    };
    const finishLoadingWhenReady = () => {
      const maps = readyNaverMapsOf(window);
      // 기본 스크립트의 onload가 GL 모듈 완료보다 먼저 올 수 있다.
      if (maps?.glEnabled === true) finish(maps);
    };
    const timer = window.setTimeout(() => {
      const maps = readyNaverMapsOf(window);
      finish(maps?.glEnabled === true ? maps : null);
    }, 10_000);
    const unsubscribeAuthFailure = subscribeNaverMapAuthFailure(() => finish(null));
    window.__salmonbusNaverMapsReady = finishLoadingWhenReady;
    script.async = true;
    script.src =
      existingMaps === null
        ? `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(keyId)}&submodules=gl&callback=__salmonbusNaverMapsReady`
        : "https://oapi.map.naver.com/openapi/v3/maps-gl.js";
    script.onerror = () => finish(null);
    script.onload = finishLoadingWhenReady;
    document.head.appendChild(script);
  }).finally(() => {
    sdkPromise = null;
  });
  return sdkPromise;
}
