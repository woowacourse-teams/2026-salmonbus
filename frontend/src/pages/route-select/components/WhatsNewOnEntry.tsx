import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { track } from "@/shared/analytics/track";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { fetchRoutes } from "@/shared/api/routeForecast.api";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import type { FirstSeenTimes, ListedRoute } from "@/shared/route-list/newRoutePolicy";
import { recordSeenRoutes } from "@/shared/route-list/seenRouteStorage";
import { boardEntryStateFor } from "@/shared/routing/boardEntry";
import { readLoginReturn } from "@/shared/routing/loginReturn";
import { boardPathFor } from "@/shared/routing/paths";
import { WHATS_NEW_NOTES, type WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { newRoutesToShowFor, notesToShowFor, type WhatsNewCloseMethod } from "@/shared/whats-new/whatsNewPolicy";
import {
  loadWhatsNewRecord,
  recordNewRoutesSeen,
  recordWhatsNewClosed,
  recordWhatsNewShown,
} from "@/shared/whats-new/whatsNewStorage";
import { whatsNewClosedPropertiesOf, whatsNewViewedPropertiesOf } from "../analytics/whatsNewEventPolicy";
import { WhatsNewDialog } from "./WhatsNewDialog";

interface ShownWhatsNew {
  notes: readonly WhatsNewNote[];
  routes: readonly ListedRoute[];
  routesCheckedAt: number | null;
}

interface RouteList {
  routes: readonly RouteSummary[];
  firstSeenTimes: FirstSeenTimes;
  checkedAt: number;
}

// 노선 목록을 기다리는 한도다. 넘기면 노선 칸 없이 띄워서, 홈에 들어오고 한참 뒤에 모달이 뜨지 않게 한다.
const ROUTE_LIST_WAIT_MS = 1000;

export function WhatsNewOnEntry() {
  const location = useLocation();
  const navigate = useNavigate();
  // 로그인 복귀 중이면 곧 원래 화면으로 이동하고, 안내가 있으면 토스트와 겹쳐서 다음 홈 진입으로 미룬다.
  // 기록은 노선 목록을 적기 전에 읽어야 처음 온 기기가 돌아온 기기로 보이지 않는다.
  const [entry] = useState(() => ({
    record: loadWhatsNewRecord(WHATS_NEW_NOTES),
    deferred: readLoginReturn() !== null || typeof location.state?.notice === "string",
  }));
  const [shown, setShown] = useState<ShownWhatsNew | null>(null);
  const shownAt = useRef<number | null>(null);

  useEffect(() => {
    const gate = createLatestRequestGate();
    const ticket = gate.issue();
    let decided = false;
    const decide = (routeList: RouteList | null) => {
      if (decided) return;
      decided = true;
      if (entry.record === null || entry.deferred) return;
      const now = routeList?.checkedAt ?? Date.now();
      const notes = notesToShowFor(WHATS_NEW_NOTES, entry.record, now);
      const routes = routeList ? newRoutesToShowFor(routeList.routes, routeList.firstSeenTimes, entry.record, now) : [];
      if (notes.length === 0 && routes.length === 0) return;
      shownAt.current = performance.now();
      recordWhatsNewShown(now);
      track("whats_new_viewed", whatsNewViewedPropertiesOf(notes, routes.length));
      setShown({ notes, routes, routesCheckedAt: routeList?.checkedAt ?? null });
    };
    const timer = window.setTimeout(() => decide(null), ROUTE_LIST_WAIT_MS);
    void fetchRoutes({ signal: ticket.signal }).then((result) => {
      if (!result.ok) {
        if (result.failure.kind !== "aborted") decide(null);
        return;
      }
      const { routes } = result.body;
      const checkedAt = Date.now();
      const firstSeenTimes = recordSeenRoutes(routes, checkedAt);
      decide({ routes, firstSeenTimes, checkedAt });
    });
    return () => {
      // 정리한 Effect의 늦은 응답으로는 띄우지 않는다. 개발 모드 StrictMode가 Effect를 두 번 돌려도 노출 이벤트는 한 번 간다.
      decided = true;
      window.clearTimeout(timer);
      gate.close();
    };
  }, [entry]);

  const close = (closeMethod: WhatsNewCloseMethod) => {
    if (shown === null) return;
    recordWhatsNewClosed(shown.notes);
    if (shown.routesCheckedAt !== null) recordNewRoutesSeen(shown.routesCheckedAt);
    const closedAt = performance.now();
    track(
      "whats_new_closed",
      whatsNewClosedPropertiesOf(shown.notes, closeMethod, closedAt - (shownAt.current ?? closedAt)),
    );
    setShown(null);
  };

  const openBoard = (route: ListedRoute) => {
    close("routeRow");
    void navigate(boardPathFor(route.id), { state: boardEntryStateFor("whats_new") });
  };

  return shown !== null ? (
    <WhatsNewDialog notes={shown.notes} routes={shown.routes} onClose={close} onSelectRoute={openBoard} />
  ) : null;
}
