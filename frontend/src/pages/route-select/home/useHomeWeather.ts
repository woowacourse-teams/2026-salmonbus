import { useEffect, useState } from "react";
import { isRecord } from "@/shared/utils/valueGuards";
import type { Point } from "./homeMapData";

interface WeatherReading {
  temperature: number;
  code: number;
  time: string;
  isDay: boolean;
  precipitationProbability: number | null;
}

export interface Weather extends Omit<WeatherReading, "precipitationProbability"> {
  hourly: WeatherReading[];
}

function precipitationProbabilityFrom(value: unknown): number | null {
  return typeof value === "number" && value >= 0 && value <= 100 ? value : null;
}

function weatherFrom(value: unknown): Weather {
  if (!isRecord(value) || !isRecord(value.current)) throw new Error("날씨 응답 형식 오류");
  const current = value.current;
  if (
    typeof current.temperature_2m !== "number" ||
    !Number.isFinite(current.temperature_2m) ||
    typeof current.weather_code !== "number" ||
    typeof current.time !== "string" ||
    !Number.isFinite(Date.parse(current.time + "+09:00")) ||
    (current.is_day !== 0 && current.is_day !== 1)
  )
    throw new Error("날씨 값 오류");
  const hourly = isRecord(value.hourly) ? value.hourly : null;
  const times = Array.isArray(hourly?.time) ? hourly.time : [];
  const probabilities = Array.isArray(hourly?.precipitation_probability) ? hourly.precipitation_probability : [];
  const temperatures = Array.isArray(hourly?.temperature_2m) ? hourly.temperature_2m : [];
  const codes = Array.isArray(hourly?.weather_code) ? hourly.weather_code : [];
  const daylight = Array.isArray(hourly?.is_day) ? hourly.is_day : [];
  return {
    temperature: current.temperature_2m,
    code: current.weather_code,
    time: current.time + "+09:00",
    isDay: current.is_day === 1,
    hourly: times.flatMap((time, index) => {
      const temperature: unknown = temperatures[index];
      const code: unknown = codes[index];
      const day: unknown = daylight[index];
      if (
        typeof time !== "string" ||
        !Number.isFinite(Date.parse(time + "+09:00")) ||
        typeof temperature !== "number" ||
        !Number.isFinite(temperature) ||
        typeof code !== "number" ||
        !Number.isFinite(code) ||
        (day !== 0 && day !== 1)
      )
        return [];
      return [
        {
          time: time + "+09:00",
          temperature,
          code,
          isDay: day === 1,
          precipitationProbability: precipitationProbabilityFrom(probabilities[index]),
        },
      ];
    }),
  };
}

export type WeatherKind = "sun" | "night" | "cloud" | "fog" | "rain" | "snow" | "thunder" | "unknown";

export function weatherCondition({ code, isDay }: Pick<WeatherReading, "code" | "isDay">): {
  label: string;
  kind: WeatherKind;
} {
  if (code === 0 || code === 1) return { label: code === 0 ? "맑음" : "대체로 맑음", kind: isDay ? "sun" : "night" };
  if (code === 2 || code === 3) return { label: code === 2 ? "구름 조금" : "흐림", kind: "cloud" };
  if (code === 45 || code === 48) return { label: "안개", kind: "fog" };
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return { label: "눈", kind: "snow" };
  if (code >= 95) return { label: "뇌우", kind: "thunder" };
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return { label: "비", kind: "rain" };
  return { label: "날씨 정보 확인 중", kind: "unknown" };
}

export function useHomeWeather(point: Point, revision: number) {
  const key = `${point[0].toFixed(3)},${point[1].toFixed(3)}:${revision}`;
  const [state, setState] = useState<{ key: string; lastWeather: Weather | null; error: boolean }>({
    key: "",
    lastWeather: null,
    error: false,
  });
  useEffect(() => {
    let active = true;
    const [coordinate] = key.split(":");
    const [longitude = "", latitude = ""] = (coordinate ?? "").split(",");
    const params = new URLSearchParams({
      longitude,
      latitude,
      current: "temperature_2m,weather_code,is_day",
      hourly: "temperature_2m,weather_code,is_day,precipitation_probability",
      timezone: "Asia/Seoul",
      forecast_days: "2",
    });
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10_000);
    void fetch(`https://api.open-meteo.com/v1/forecast?${params}`, {
      signal: controller.signal,
      credentials: "omit",
      cache: "no-cache",
    })
      .then((response) => {
        if (!response.ok) throw new Error("날씨 조회 실패");
        return response.json() as Promise<unknown>;
      })
      .then((value) => {
        if (active) setState({ key, lastWeather: weatherFrom(value), error: false });
      })
      .catch(() => {
        if (active) setState((previous) => ({ ...previous, key, error: true }));
      })
      .finally(() => window.clearTimeout(timeout));
    return () => {
      active = false;
      window.clearTimeout(timeout);
      controller.abort();
    };
  }, [key]);
  const loading = state.key !== key;
  return {
    weather: loading || state.error ? null : state.lastWeather,
    backgroundKind: state.lastWeather ? weatherCondition(state.lastWeather).kind : undefined,
    loading,
  };
}
