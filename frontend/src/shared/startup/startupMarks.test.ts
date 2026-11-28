import { afterEach, describe, expect, it, jest } from "@jest/globals";
import {
  STARTUP_MARKS,
  createStartupMarker,
  flushStartupDom,
  observeStartupDom,
  type StartupMarkName,
} from "./startupMarks";

type Callback = () => void;

class FakeObserver {
  static all: FakeObserver[] = [];
  target: unknown = null;
  options: MutationObserverInit | null = null;
  constructor(readonly callback: Callback) {
    FakeObserver.all.push(this);
  }
  observe(target: unknown, options: MutationObserverInit) {
    this.target = target;
    this.options = options;
  }
  disconnect() {
    this.target = null;
  }
}

function notify(target: unknown) {
  FakeObserver.all.filter((observer) => observer.target === target).forEach((observer) => observer.callback());
}

function fakeRoot() {
  const state = { child: false, main: false };
  const root = {
    get firstChild() {
      return state.child ? {} : null;
    },
    querySelector: (selector: string) => (selector === "main" && state.main ? {} : null),
  };
  return { root: root as unknown as HTMLElement, state };
}

function fakeHtml() {
  const attributes = new Map<string, string>();
  const html = { getAttribute: (name: string) => attributes.get(name) ?? null };
  return { html: html as unknown as HTMLElement, attributes };
}

function recorder() {
  const names: StartupMarkName[] = [];
  return {
    names,
    marker: {
      mark: (name: StartupMarkName) => {
        names.push(name);
        return true;
      },
    },
  };
}

afterEach(() => {
  FakeObserver.all = [];
  Reflect.deleteProperty(globalThis, "MutationObserver");
});

describe("시작 마커 이름", () => {
  it("이름 목록은 정해진 값이다", () => {
    expect(Object.values(STARTUP_MARKS)).toMatchInlineSnapshot(`
[
  "salmon:web:entry",
  "salmon:web:first-commit",
  "salmon:web:shell-commit",
  "salmon:map:sdk-start",
  "salmon:map:sdk-ready",
  "salmon:map:create",
  "salmon:map:init",
  "salmon:map:native-mount",
  "salmon:map:native-ready",
  "salmon:map:native-error",
  "salmon:map:web-transparent",
  "salmon:map:stops-interactive",
]
`);
  });
});

describe("시작 마커", () => {
  it("이름마다 처음 한 번만 detail 없이 찍는다", () => {
    const mark = jest.fn<(name: string) => void>();
    const marker = createStartupMarker({ mark } as unknown as Pick<Performance, "mark">);
    expect(marker.mark(STARTUP_MARKS.webEntry)).toBe(true);
    expect(marker.mark(STARTUP_MARKS.webEntry)).toBe(false);
    expect(marker.mark(STARTUP_MARKS.webFirstCommit)).toBe(true);
    expect(mark.mock.calls).toEqual([[STARTUP_MARKS.webEntry], [STARTUP_MARKS.webFirstCommit]]);
  });

  it("performance가 없거나 mark가 실패해도 예외를 내지 않는다", () => {
    expect(createStartupMarker(undefined).mark(STARTUP_MARKS.webEntry)).toBe(false);
    const failing = createStartupMarker({
      mark: () => {
        throw new Error("mark");
      },
    } as unknown as Pick<Performance, "mark">);
    expect(failing.mark(STARTUP_MARKS.webEntry)).toBe(false);
  });
});

describe("시작 DOM 관찰", () => {
  it("MutationObserver가 없으면 아무것도 하지 않는다", () => {
    const { names, marker } = recorder();
    const stop = observeStartupDom(fakeRoot().root, fakeHtml().html, marker);
    expect(names).toEqual([]);
    expect(stop).not.toThrow();
  });

  it("첫 자식과 첫 main을 나눠 찍고, 둘 다 찍으면 root 관찰을 끝낸다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { root, state } = fakeRoot();
    const { names, marker } = recorder();
    observeStartupDom(root, fakeHtml().html, marker);
    notify(root);
    expect(names).toEqual([]);
    state.child = true;
    notify(root);
    expect(names).toEqual([STARTUP_MARKS.webFirstCommit]);
    state.main = true;
    notify(root);
    expect(names).toEqual([STARTUP_MARKS.webFirstCommit, STARTUP_MARKS.webShellCommit]);
    expect(FakeObserver.all.some((observer) => observer.target === root)).toBe(false);
  });

  it("이미 그려진 root는 관찰 없이 바로 찍는다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { root, state } = fakeRoot();
    state.child = true;
    state.main = true;
    const { names, marker } = recorder();
    observeStartupDom(root, fakeHtml().html, marker);
    expect(names).toEqual([STARTUP_MARKS.webFirstCommit, STARTUP_MARKS.webShellCommit]);
    expect(FakeObserver.all.some((observer) => observer.target === root)).toBe(false);
  });

  it("html의 data-native-home-map이 true가 되면 투명화를 한 번 찍는다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { html, attributes } = fakeHtml();
    const { names, marker } = recorder();
    observeStartupDom(fakeRoot().root, html, marker);
    const htmlObserver = FakeObserver.all.find((observer) => observer.target === html);
    expect(htmlObserver?.options).toEqual({
      attributes: true,
      attributeFilter: ["data-native-home-map"],
    });
    attributes.set("data-native-home-map", "false");
    notify(html);
    expect(names).toEqual([]);
    attributes.set("data-native-home-map", "true");
    notify(html);
    notify(html);
    expect(names).toEqual([STARTUP_MARKS.mapWebTransparent]);
  });

  it("판정보드 지도 속성만 true가 되면 web-transparent를 찍지 않는다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { html, attributes } = fakeHtml();
    const { names, marker } = recorder();
    const stop = observeStartupDom(fakeRoot().root, html, marker);
    attributes.set("data-shared-native-map-ready", "true");
    notify(html);
    flushStartupDom();
    expect(names).toEqual([]);
    stop();
  });

  it("flushStartupDom은 관찰 콜백을 기다리지 않고 지금 DOM으로 바로 찍는다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { root, state } = fakeRoot();
    const { html, attributes } = fakeHtml();
    const { names, marker } = recorder();
    const stop = observeStartupDom(root, html, marker);
    state.child = true;
    state.main = true;
    attributes.set("data-native-home-map", "true");
    flushStartupDom();
    flushStartupDom();
    expect(names).toEqual([
      STARTUP_MARKS.webFirstCommit,
      STARTUP_MARKS.webShellCommit,
      STARTUP_MARKS.mapWebTransparent,
    ]);
    expect(FakeObserver.all.every((observer) => observer.target === null)).toBe(true);
    stop();
  });

  it("정리한 뒤의 flushStartupDom은 찍지 않는다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { root, state } = fakeRoot();
    const { names, marker } = recorder();
    const stop = observeStartupDom(root, fakeHtml().html, marker);
    stop();
    state.child = true;
    flushStartupDom();
    expect(names).toEqual([]);
  });

  it("정리 함수는 두 관찰을 모두 끝낸다", () => {
    Object.assign(globalThis, { MutationObserver: FakeObserver });
    const { root } = fakeRoot();
    const { html } = fakeHtml();
    const stop = observeStartupDom(root, html, recorder().marker);
    stop();
    expect(FakeObserver.all.every((observer) => observer.target === null)).toBe(true);
  });
});
