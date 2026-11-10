import type { NaverCoordinate, NaverMap, NaverMaps, NaverMarker } from "@/shared/map/naverSdk";
import type { HomeStop } from "./homeMapData";
import { MIN_STATION_ZOOM } from "./homeStationTiles";
import { createMapStopPin } from "@/shared/map/mapStopPin";
import { stopPinSize } from "@/shared/map/mapStopPin.css";

const MARKER_MARGIN = 16;
const PIN_SPACING_ZOOM = 15;
const PIN_CLEARANCE = stopPinSize + 2;

export function mountHomeStops(map: NaverMap, maps: NaverMaps, stops: HomeStop[], onSelect: (id: string) => void) {
  const markers = new Map<string, { marker: NaverMarker; button: HTMLButtonElement; label: HTMLSpanElement }>();
  const coordinates = [...new Map(stops.map((stop) => [stop.id, stop])).values()].map((stop) => ({
    stop,
    position: new maps.LatLng(stop.point[1], stop.point[0]),
  }));
  let selected: string | null = null;

  function showStop(stop: HomeStop, position: NaverCoordinate, zoom: number) {
    let entry = markers.get(stop.id);
    if (!entry) {
      const { button, label } = createMapStopPin(stop.name, `${stop.name} 정류장`);
      button.onclick = (event) => {
        event.stopPropagation();
        onSelect(stop.id);
      };
      const marker = new maps.Marker({
        map,
        position,
        icon: { content: button, anchor: new maps.Point(stopPinSize / 2, stopPinSize / 2) },
        zIndex: 0,
        clickable: true,
      });
      entry = { marker, button, label };
      markers.set(stop.id, entry);
    }
    const active = selected === stop.id;
    entry.button.dataset.labelVisible = String(active || zoom >= 17);
    entry.label.style.marginTop = "";
    entry.button.setAttribute("aria-pressed", String(active));
    entry.marker.setZIndex(active ? 1 : 0);
    return entry;
  }

  function update() {
    const zoom = map.getZoom();
    const projection = map.getProjection();
    if (!projection) return;
    const { width, height } = map.getSize();
    const visible = new Set<string>();
    const center = projection.fromCoordToOffset(map.getCenter());
    const left = center.x - width / 2;
    const top = center.y - height / 2;
    const kept: { x: number; y: number }[] = [];
    const labels: { button: HTMLButtonElement; label: HTMLSpanElement }[] = [];

    if (zoom >= MIN_STATION_ZOOM) {
      const spacing = zoom < PIN_SPACING_ZOOM ? 36 : 0;
      for (const { stop, position } of coordinates) {
        const { x, y } = projection.fromCoordToOffset(position);
        if (
          x < left - MARKER_MARGIN ||
          y < top - MARKER_MARGIN ||
          x > left + width + MARKER_MARGIN ||
          y > top + height + MARKER_MARGIN
        )
          continue;
        if (spacing > 0 && kept.some((point) => Math.hypot(point.x - x, point.y - y) < spacing)) continue;

        const pinX =
          spacing > 0
            ? x
            : ([x, x + PIN_CLEARANCE, x - PIN_CLEARANCE].find(
                (candidate) =>
                  !kept.some(
                    (point) => Math.abs(point.x - candidate) < PIN_CLEARANCE && Math.abs(point.y - y) < PIN_CLEARANCE,
                  ),
              ) ?? x);
        visible.add(stop.id);
        kept.push({ x: pinX, y });
        const entry = showStop(stop, position, zoom);
        entry.button.style.translate = `${pinX - x}px`;
        if (zoom >= 17) labels.push(entry);
      }
    }

    for (const [id, { marker }] of markers) {
      if (visible.has(id)) continue;
      marker.setMap(null);
      markers.delete(id);
    }

    const labelBounds = labels.map(({ button, label }) => ({ button, label, bounds: label.getBoundingClientRect() }));
    const selectedLabel = labelBounds.find(({ button }) => button.getAttribute("aria-pressed") === "true");
    const occupied: DOMRect[] = selectedLabel ? [selectedLabel.bounds] : [];
    const overlaps = (bounds: DOMRect) =>
      occupied.some(
        (other) =>
          bounds.left < other.right &&
          bounds.right > other.left &&
          bounds.top < other.bottom &&
          bounds.bottom > other.top,
      );
    for (const { button, label, bounds } of labelBounds) {
      if (button === selectedLabel?.button) continue;
      let placement = bounds;
      if (overlaps(bounds)) {
        const offset = bounds.height + 2;
        placement = new DOMRect(bounds.x, bounds.y + offset, bounds.width, bounds.height);
        label.style.marginTop = `${offset}px`;
      }
      const fits = !overlaps(placement);
      button.dataset.labelVisible = String(fits);
      if (fits) occupied.push(placement);
    }
  }

  const idle = maps.Event.addListener(map, "idle", update);
  const resize = maps.Event.addListener(map, "size_changed", update);
  update();
  return {
    select(id: string | null) {
      if (selected === id) return;
      selected = id;
      update();
    },
    dispose() {
      if (window.naver?.maps !== maps) return;
      maps.Event.removeListener(idle);
      maps.Event.removeListener(resize);
      markers.forEach(({ marker }) => marker.setMap(null));
    },
  };
}
