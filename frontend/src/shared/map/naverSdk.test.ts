import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { loadNaverMaps, subscribeNaverMapAuthFailure, type NaverMaps } from "./naverSdk";

interface ScriptStub {
  async: boolean;
  src: string;
  onerror: (() => void) | null;
  onload: (() => void) | null;
  remove: () => void;
}
const priorWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
const priorDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
let scripts: ScriptStub[];
beforeEach(() => {
  jest.useFakeTimers();
  scripts = [];
  Object.defineProperty(globalThis, "window", { configurable: true, value: { setTimeout, clearTimeout } });
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: {
      createElement: () => ({ async: false, src: "", onerror: null, onload: null, remove: jest.fn() }),
      head: { appendChild: (script: ScriptStub) => scripts.push(script) },
    },
  });
});
afterEach(() => {
  jest.useRealTimers();
  if (priorWindow !== undefined) Object.defineProperty(globalThis, "window", priorWindow);
  else Reflect.deleteProperty(globalThis, "window");
  if (priorDocument !== undefined) Object.defineProperty(globalThis, "document", priorDocument);
  else Reflect.deleteProperty(globalThis, "document");
});

// 이 테스트에서는 생성하지 않는다. 로드 완료에 필요한 native 기능의 존재만 확인한다.
function createReadyMapsStub(glEnabled = true): NaverMaps {
  return {
    glEnabled,
    LatLng: class {},
    Point: class {},
    Size: class {},
    Map: class {},
    Marker: class {},
    Polyline: class {},
    Circle: class {},
    Event: { addListener: jest.fn(), removeListener: jest.fn() },
  } as unknown as NaverMaps;
}

describe("NAVER SDK 비동기 로드", () => {
  it("호스트가 이미 불러온 GL SDK는 추가 스크립트나 키 없이 사용한다", async () => {
    const maps = createReadyMapsStub();
    window.naver = { maps };
    expect(await loadNaverMaps("")).toBe(maps);
    expect(scripts).toHaveLength(0);
  });
  it("기본 SDK만 있으면 키 없이 GL 서브모듈만 불러온다", async () => {
    const maps = createReadyMapsStub(false);
    window.naver = { maps };
    const promise = loadNaverMaps("");
    expect(scripts).toHaveLength(1);
    expect(scripts[0]?.src).toBe("https://oapi.map.naver.com/openapi/v3/maps-gl.js");
    maps.glEnabled = true;
    scripts[0]?.onload?.();
    expect(await promise).toBe(maps);
    expect(scripts[0]?.remove).not.toHaveBeenCalled();
    expect(jest.getTimerCount()).toBe(0);
  });
  it("기존 SDK의 GL 서브모듈을 불러오는 동안 추가 요청은 같은 작업을 기다린다", async () => {
    const maps = createReadyMapsStub(false);
    window.naver = { maps };
    const first = loadNaverMaps("");
    const second = loadNaverMaps("another-key");
    expect(first).toBe(second);
    expect(scripts).toHaveLength(1);
    maps.glEnabled = true;
    scripts[0]?.onload?.();
    expect(await first).toBe(maps);
  });
  it("키가 없으면 외부 요청을 보내지 않는다", async () => {
    await expect(loadNaverMaps("")).rejects.toThrow("지도 설정");
    expect(scripts).toHaveLength(0);
  });
  it("로드 실패는 정리하고 재시도할 수 있다", async () => {
    const promise = loadNaverMaps("test-key");
    const assertion = expect(promise).rejects.toThrow("불러오지 못");
    scripts[0]?.onerror?.();
    await assertion;
    expect(scripts[0]?.remove).toHaveBeenCalled();
  });
  it("동시에 요청해도 한 번만 로드하고 완료 콜백 후 준비된다", async () => {
    const first = loadNaverMaps("test-key");
    const second = loadNaverMaps("test-key");
    expect(first).toBe(second);
    expect(scripts).toHaveLength(1);
    expect(scripts[0]?.src).toContain("ncpKeyId=test-key");
    expect(scripts[0]?.src).toContain("submodules=gl");
    expect(scripts[0]?.src).toContain("callback=__salmonbusNaverMapsReady");
    const maps = createReadyMapsStub();
    window.naver = { maps };
    window.__salmonbusNaverMapsReady?.();
    expect(await first).toBe(maps);
    expect(window.__salmonbusNaverMapsReady).toBeUndefined();
  });
  it("완료 콜백이 오지 않아도 script.onload에서 완성된 SDK를 사용한다", async () => {
    const promise = loadNaverMaps("test-key");
    const maps = createReadyMapsStub();
    window.naver = { maps };
    scripts[0]?.onload?.();
    expect(await promise).toBe(maps);
    expect(window.__salmonbusNaverMapsReady).toBeUndefined();
    expect(scripts[0]?.remove).not.toHaveBeenCalled();
    expect(jest.getTimerCount()).toBe(0);
  });
  it("기본 SDK의 onload와 콜백이 먼저 와도 GL이 준비될 때까지 기다린다", async () => {
    const promise = loadNaverMaps("test-key");
    const maps = createReadyMapsStub(false);
    window.naver = { maps };
    const resolved = jest.fn();
    void promise.then(resolved);
    scripts[0]?.onload?.();
    window.__salmonbusNaverMapsReady?.();
    await Promise.resolve();
    expect(resolved).not.toHaveBeenCalled();
    expect(window.__salmonbusNaverMapsReady).toBeDefined();
    expect(jest.getTimerCount()).toBe(1);
    expect(loadNaverMaps("test-key")).toBe(promise);
    expect(scripts).toHaveLength(1);
    maps.glEnabled = true;
    window.__salmonbusNaverMapsReady?.();
    expect(await promise).toBe(maps);
    expect(window.__salmonbusNaverMapsReady).toBeUndefined();
    expect(jest.getTimerCount()).toBe(0);
  });
  it("불완전한 Maps 객체는 기존 SDK나 onload 완료로 취급하지 않는다", async () => {
    window.naver = { maps: {} as NaverMaps };
    const promise = loadNaverMaps("test-key");
    const resolved = jest.fn();
    void promise.then(resolved);
    scripts[0]?.onload?.();
    window.__salmonbusNaverMapsReady?.();
    await Promise.resolve();
    expect(resolved).not.toHaveBeenCalled();
    expect(scripts).toHaveLength(1);
    const maps = createReadyMapsStub();
    window.naver = { maps };
    window.__salmonbusNaverMapsReady?.();
    expect(await promise).toBe(maps);
  });
  it("onload 당시 기본 SDK만 있어도 마지막 확인에서 GL이 준비되면 사용한다", async () => {
    const promise = loadNaverMaps("test-key");
    const maps = createReadyMapsStub(false);
    window.naver = { maps };
    scripts[0]?.onload?.();
    maps.glEnabled = true;
    jest.advanceTimersByTime(10_000);
    expect(await promise).toBe(maps);
  });
  it("제한 시간이 지나도 SDK가 불완전하면 실패하고 다음 호출에 재시도한다", async () => {
    const promise = loadNaverMaps("test-key");
    window.naver = { maps: {} as NaverMaps };
    scripts[0]?.onload?.();
    const assertion = expect(promise).rejects.toThrow("불러오지 못");
    jest.advanceTimersByTime(10_000);
    await assertion;
    expect(scripts[0]?.remove).toHaveBeenCalled();
    const retry = loadNaverMaps("test-key");
    expect(scripts).toHaveLength(2);
    const retryAssertion = expect(retry).rejects.toThrow("불러오지 못");
    scripts[1]?.onerror?.();
    await retryAssertion;
  });
  it("기본 SDK만 준비되고 GL이 끝내 준비되지 않으면 실패하고 서브모듈만 재시도한다", async () => {
    const promise = loadNaverMaps("test-key");
    const maps = createReadyMapsStub(false);
    window.naver = { maps };
    scripts[0]?.onload?.();
    window.__salmonbusNaverMapsReady?.();
    const assertion = expect(promise).rejects.toThrow("불러오지 못");
    jest.advanceTimersByTime(10_000);
    await assertion;
    expect(scripts[0]?.remove).toHaveBeenCalled();
    expect(window.__salmonbusNaverMapsReady).toBeUndefined();
    const retry = loadNaverMaps("");
    expect(scripts).toHaveLength(2);
    expect(scripts[1]?.src).toBe("https://oapi.map.naver.com/openapi/v3/maps-gl.js");
    maps.glEnabled = true;
    scripts[1]?.onload?.();
    expect(await retry).toBe(maps);
  });
  it("GL 서브모듈 요청도 로드 오류가 나면 정리하고 실패한다", async () => {
    window.naver = { maps: createReadyMapsStub(false) };
    const promise = loadNaverMaps("");
    const assertion = expect(promise).rejects.toThrow("불러오지 못");
    scripts[0]?.onerror?.();
    await assertion;
    expect(scripts[0]?.remove).toHaveBeenCalled();
    expect(jest.getTimerCount()).toBe(0);
  });
  it("Maps 객체가 만들어졌어도 인증 실패 콜백이 오면 즉시 실패한다", async () => {
    const promise = loadNaverMaps("test-key");
    window.naver = { maps: createReadyMapsStub() };
    const assertion = expect(promise).rejects.toThrow("불러오지 못");
    window.navermap_authFailure?.();
    await assertion;
    expect(scripts[0]?.remove).toHaveBeenCalled();
    expect(window.navermap_authFailure).toBeUndefined();
  });
  it("SDK 준비 이후의 인증 실패도 소비자에게 전달하고 마지막 해제 시 호스트 훅을 복구한다", async () => {
    const hostAuthFailure = jest.fn();
    window.navermap_authFailure = hostAuthFailure;
    const onAuthFailure = jest.fn();
    const unsubscribe = subscribeNaverMapAuthFailure(onAuthFailure);
    try {
      const promise = loadNaverMaps("test-key");
      const maps = createReadyMapsStub();
      window.naver = { maps };
      scripts[0]?.onload?.();
      expect(await promise).toBe(maps);
      window.navermap_authFailure?.();
      expect(onAuthFailure).toHaveBeenCalledTimes(1);
      expect(hostAuthFailure).toHaveBeenCalledTimes(1);
    } finally {
      unsubscribe();
    }
    expect(window.navermap_authFailure).toBe(hostAuthFailure);
  });
  it("인증 실패로 생성자 getter가 깨진 SDK 객체를 다시 읽어도 예외를 전파하지 않는다", async () => {
    const maps = createReadyMapsStub();
    Object.defineProperty(maps, "LatLng", {
      get() {
        throw new TypeError("SDK 내부 구현이 제거됨");
      },
    });
    window.naver = { maps };
    const promise = loadNaverMaps("test-key");
    const assertion = expect(promise).rejects.toThrow("불러오지 못");
    expect(() => scripts[0]?.onload?.()).not.toThrow();
    jest.advanceTimersByTime(10_000);
    await assertion;
    expect(scripts[0]?.remove).toHaveBeenCalled();
  });
});
