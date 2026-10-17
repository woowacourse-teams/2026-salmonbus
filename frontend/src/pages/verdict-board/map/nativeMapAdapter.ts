import type { Board } from "@/shared/api/routeForecast.types";
import { createNativeMapChannel, type NativeMapCapability } from "@/shared/native-map/bridge";
import { startOverlayLayoutBridge } from "@/shared/native-map/overlayLayoutBridge";
import type { NativeMapScene, NativeMapSceneState, NativeMapVehicle } from "@/shared/native-map/protocol";
import type { StopSelection } from "../components/stopSelection";
import type { LiveVehicleView } from "../components/liveVehiclePolicy";
import type { LiveVehicleMotionStore } from "../hooks/useLiveVehicleMotion";
import type { RouteMapAdapter } from "./naverMapAdapter";
import type { GeoCoordinate, RouteMapData } from "./routeMapModel";
import { routeMapCoordinateForSequence, routeMapMotionPathFor } from "./routeMapGeometry";

export interface NativeRouteMapAdapter extends RouteMapAdapter {
  readonly native: true;
  locate: (requestId: number) => Promise<GeoCoordinate>;
}

export function createNativeRouteMap(options: {
  capability: NativeMapCapability;
  region: HTMLElement;
  board: Board;
  data: RouteMapData;
  motion: LiveVehicleMotionStore;
  onSelect: (selection: StopSelection) => void;
  onVehicleSelect: (selection: StopSelection, key: string) => void;
  onDeselect: () => void;
  onReady: () => void;
  onError: (error: Error) => void;
}): NativeRouteMapAdapter {
  const { capability, region, board, data, motion, onSelect, onVehicleSelect, onDeselect, onReady, onError } = options;
  const channel = createNativeMapChannel(capability);
  let vehicles: readonly LiveVehicleView[] = [];
  const sentPlans = new Map<string, string>();
  const locationRequests = new Map<
    number,
    { resolve: (coordinate: GeoCoordinate) => void; reject: (error: Error) => void; timer: number }
  >();
  let sceneState: NativeMapSceneState = {
    activeDirection: board.route.directions[0].id,
    selectedStop: null,
    selectedVehicleKey: null,
    vehicles: [],
  };
  let sceneSignature = "";
  let destroyed = false;
  const timer = window.setTimeout(() => onError(new Error("지도를 불러오지 못했어요")), 15_000);
  const unsubscribe = channel.subscribe((event) => {
    switch (event.type) {
      case "ready":
        window.clearTimeout(timer);
        onReady();
        break;
      case "stopPress":
        if (
          data.stops.some(
            (stop) =>
              stop.direction === event.payload.selection.direction &&
              stop.sequence === event.payload.selection.sequence,
          )
        )
          onSelect(event.payload.selection);
        break;
      case "vehiclePress":
        if (vehicles.some((view) => view.key === event.payload.key))
          onVehicleSelect(event.payload.selection, event.payload.key);
        break;
      case "mapPress":
        onDeselect();
        break;
      case "error":
        onError(new Error(event.payload.message));
        break;
      case "locationResult": {
        const request = locationRequests.get(event.payload.requestId);
        if (request === undefined) break;
        locationRequests.delete(event.payload.requestId);
        window.clearTimeout(request.timer);
        if (event.payload.coordinate !== undefined) request.resolve(event.payload.coordinate);
        else request.reject(new Error(event.payload.error ?? "unavailable"));
        break;
      }
      case "ack":
        break;
    }
  });
  const scene: NativeMapScene = {
    ...sceneState,
    routeId: data.routeId,
    referenceVersionId: data.referenceVersionId,
    stops: data.stops.flatMap((stop) => {
      const state = board.stops.find((item) => item.direction === stop.direction && item.sequence === stop.sequence);
      if (state === undefined) return [];
      return [
        {
          ...stop,
          name: state.name,
          boardingAllowed: state.boardingAllowed,
          terminalStopName:
            board.route.directions.find((direction) => direction.id === stop.direction)?.terminalStopName ?? "",
        },
      ];
    }),
    paths: data.paths,
    fullPath: data.fullPath ?? [],
  };
  channel.send("mount", scene);
  document.documentElement.dataset.nativeMap = "true";
  const stopLayout = startOverlayLayoutBridge(channel, region);

  function syncMotion() {
    if (destroyed) return;
    const now = performance.now();
    const plans = motion.plans.flatMap((plan) => {
      const view = vehicles.find((item) => item.key === plan.key);
      if (view === undefined || view.vehicle.direction !== plan.direction) return [];
      const signature = `${plan.signature}:${plan.startedAt}:${plan.durationMs}:${plan.start}:${plan.end}`;
      if (sentPlans.get(plan.key) === signature) return [];
      const progress = plan.durationMs === 0 ? 1 : Math.min(1, Math.max(0, (now - plan.startedAt) / plan.durationMs));
      const sequence = plan.start + (plan.end - plan.start) * progress;
      const mapped = data.paths.some((path) => path.direction === view.vehicle.direction);
      const from = mapped ? sequence : view.vehicle.currentStopSequence;
      const to = mapped ? plan.end : from;
      const path = routeMapMotionPathFor(data, from, to, view.vehicle.direction);
      if (path.length === 0) return [];
      sentPlans.set(plan.key, signature);
      return [
        { key: plan.key, signature, path, durationMs: mapped ? Math.max(0, plan.durationMs * (1 - progress)) : 0 },
      ];
    });
    if (plans.length > 0) channel.send("motion", { vehicles: plans });
  }
  const unsubscribeMotion = motion.subscribePlans(syncMotion);

  function metadataFor(view: LiveVehicleView): NativeMapVehicle[] {
    const vehicle = view.vehicle;
    const coordinate = routeMapCoordinateForSequence(data, vehicle.currentStopSequence, vehicle.direction);
    if (coordinate === null) return [];
    const remainingSeats = vehicle.seat.kind === "EXACT" ? vehicle.seat.remaining : null;
    return [
      {
        key: view.key,
        direction: vehicle.direction,
        currentStopSequence: vehicle.currentStopSequence,
        coordinate,
        remainingSeats,
        caption: remainingSeats === null ? "–" : `현재 ${remainingSeats}석`,
        label: `${vehicle.stopName} 부근 버스, ${remainingSeats === null ? "현재 잔여 좌석 정보 없음" : `현재 잔여 좌석 ${remainingSeats}석`}`,
      },
    ];
  }

  return {
    native: true,
    update(selectedStop, activeDirection, nextVehicles, selectedVehicleKey = null) {
      vehicles = nextVehicles;
      const keys = new Set(vehicles.map((view) => view.key));
      for (const key of sentPlans.keys()) if (!keys.has(key)) sentPlans.delete(key);
      sceneState = { selectedStop, activeDirection, selectedVehicleKey, vehicles: vehicles.flatMap(metadataFor) };
      const signature = JSON.stringify(sceneState);
      if (signature !== sceneSignature) {
        sceneSignature = signature;
        channel.send("updateScene", sceneState);
      }
      syncMotion();
    },
    // Native CADisplayLink owns interpolation; the web list continues using the shared JS clock.
    moveVehicles() {},
    resize() {},
    focus(selection) {
      channel.send("camera", { command: "stop", selection });
    },
    focusVehicle(key, { focusMarker = true } = {}) {
      channel.send("camera", { command: "vehicle", key, focusMarker });
    },
    showLocation(coordinate) {
      channel.send("camera", { command: "location", coordinate });
    },
    locate(requestId) {
      return new Promise((resolve, reject) => {
        const requestTimer = window.setTimeout(() => {
          locationRequests.delete(requestId);
          reject(new Error("timeout"));
        }, 12_000);
        locationRequests.set(requestId, { resolve, reject, timer: requestTimer });
        channel.send("location", { requestId });
      });
    },
    destroy() {
      destroyed = true;
      window.clearTimeout(timer);
      stopLayout();
      unsubscribeMotion();
      unsubscribe();
      for (const request of locationRequests.values()) {
        window.clearTimeout(request.timer);
        request.reject(new Error("unavailable"));
      }
      locationRequests.clear();
      channel.send("unmount", {});
      channel.destroy();
      delete document.documentElement.dataset.nativeMap;
    },
  };
}
