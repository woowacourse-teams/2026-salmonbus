import { useEffect, useReducer, useState } from "react";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { useAuth } from "@/shared/hooks/useAuth";
import { HomeSheetWeather } from "./HomeSheetWeather";
import { lookupAreaLabel, nearbyAreaLabel } from "./homeAreaLabel";
import type { HomeStop, Point } from "./homeMapData";
import { useHomeWeather } from "./useHomeWeather";
import * as styles from "./HomeSheetWeather.css";

const seoulHour = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", hour: "2-digit", hourCycle: "h23" });

function greetingFor(now: number): string {
  const hour = Number(seoulHour.formatToParts(now).find(({ type }) => type === "hour")?.value);
  if (hour < 5 || hour >= 22) return "편안한 밤 보내세요.";
  if (hour < 11) return "오늘도 힘찬 하루 보내세요!";
  if (hour < 17) return "남은 하루도 기분 좋게 보내세요.";
  return "오늘 하루도 고생 많으셨어요.";
}

export function HomeOverview({ point, stops, onRefresh }: { point: Point; stops: HomeStop[]; onRefresh: () => void }) {
  const { state: auth } = useAuth();
  const nickname = auth.status === "authenticated" ? auth.member.nickname.trim() : "";
  const [now, setNow] = useState(Date.now);
  const [revision, retry] = useReducer((value: number) => value + 1, 0);
  const { weather, backgroundKind, loading } = useHomeWeather(point, revision);
  const [area, setArea] = useState<{ point: Point; label: string | null } | null>(null);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    let active = true;
    void lookupAreaLabel(point).then((label) => {
      if (active) setArea({ point, label });
    });
    return () => {
      active = false;
    };
  }, [point]);

  const areaLabel = (area?.point === point ? area.label : null) ?? nearbyAreaLabel(point, stops);
  return (
    <div className={styles.overview}>
      <HomeSheetWeather
        weather={weather}
        backgroundKind={backgroundKind}
        loading={loading}
        refreshRevision={revision}
        areaLabel={areaLabel}
        now={now}
        onRefresh={() => {
          onRefresh();
          retry();
        }}
      />
      <p className={styles.greeting}>
        {nickname && <span {...maskedPropsFor(styles.greetingName)}>{nickname}님,</span>}
        {greetingFor(now)}
      </p>
    </div>
  );
}
