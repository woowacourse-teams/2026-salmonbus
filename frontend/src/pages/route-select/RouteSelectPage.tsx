import { useEffect, useState } from "react";
import type { Routes } from "./api/RouteSelect.type.ts";
import { RouteLists } from "./components/RouteLists.js";
import salmongProud from "./assets/salmong-proud.png";

export function RouteSelectPage() {
  const [routes, setRoute] = useState<Routes>();

  return (
    <>
      <header>
        <img src={salmongProud}></img>
        <h1>어떤 버스를 타시나요?</h1>
        <p>노선을 선택하면 정류잘별 도착 예상 좌석과 탑승 판정을 빠르게 확인할 수 있어요.</p>
      </header>
      <main>
        <RouteLists />
      </main>
    </>
  );
}
