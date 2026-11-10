import { useEffect, useState } from "react";
import { homeMapDataFrom, type HomeMapData } from "./homeMapData";

const EMPTY_MAP_DATA: HomeMapData = { stops: [], routePaths: [] };

export function useHomeMapData() {
  const [data, setData] = useState(EMPTY_MAP_DATA);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10_000);
    void fetch("/assets/home-events.json", { signal: controller.signal, cache: "no-cache" })
      .then((response) => {
        if (!response.ok) throw new Error("지도 데이터 조회 실패");
        return response.json() as Promise<unknown>;
      })
      .then((value) => {
        if (active) setData(homeMapDataFrom(value));
      })
      .catch(() => {
        if (active) setData(EMPTY_MAP_DATA);
      })
      .finally(() => window.clearTimeout(timeout));
    return () => {
      active = false;
      window.clearTimeout(timeout);
      controller.abort();
    };
  }, []);
  return data;
}
