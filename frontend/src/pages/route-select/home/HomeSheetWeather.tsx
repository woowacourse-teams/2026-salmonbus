import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import refreshIcon from "@/shared/assets/icons/refresh.svg";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { weatherCondition, type Weather, type WeatherKind } from "./useHomeWeather";
import * as styles from "./HomeSheetWeather.css";

const hourLabel = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  hour: "numeric",
  hour12: true,
});
const HOUR_MS = 60 * 60 * 1_000;

export function HomeSheetWeather({
  weather,
  backgroundKind,
  areaLabel,
  loading,
  refreshRevision,
  now,
  onRefresh,
}: {
  weather: Weather | null;
  backgroundKind: WeatherKind | undefined;
  areaLabel: string | null;
  loading: boolean;
  refreshRevision: number;
  now: number;
  onRefresh: () => void;
}) {
  const caption = areaLabel ? `${areaLabel} 날씨` : "마지막 조회 위치 날씨";
  const forecast =
    weather?.hourly.filter(({ time }) => {
      const offset = Date.parse(time) - now;
      return offset > 0 && offset <= 5 * HOUR_MS;
    }) ?? [];

  return (
    <section className={styles.panel} data-weather={backgroundKind} aria-label="현재 지역 날씨">
      <div className={styles.weatherHeader}>
        <div className={styles.weatherLocation}>
          <p {...maskedPropsFor(styles.areaLabel)}>{caption}</p>
          <button
            type="button"
            className={styles.refresh}
            aria-label="지도 중심 날씨 새로고침"
            disabled={loading}
            onClick={onRefresh}
          >
            <img
              key={refreshRevision}
              className={refreshRevision > 0 ? styles.refreshSpinning : undefined}
              src={refreshIcon}
              width={14}
              height={14}
              alt=""
            />
          </button>
        </div>
        {weather && (
          <a
            className={styles.credit}
            href="https://open-meteo.com/en/licence"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="날씨 데이터 출처: Open-Meteo"
          >
            Open-Meteo
          </a>
        )}
      </div>
      <p className={styles.weatherHint}>마지막 조회 위치 기준이에요. 새로고침하면 지도 중심 날씨를 보여드려요.</p>
      {weather ? (
        <ol {...maskedPropsFor(styles.hourly)} aria-label="시간대별 날씨 예보">
          <WeatherCell
            reading={{ ...weather, precipitationProbability: forecast[0]?.precipitationProbability ?? null }}
            currentTime={now}
          />
          {forecast.map((hour) => (
            <WeatherCell key={hour.time} reading={hour} />
          ))}
        </ol>
      ) : (
        <div className={styles.reading} aria-busy={loading}>
          {loading ? (
            <span className={visuallyHidden} role="status">
              날씨를 불러오고 있어요…
            </span>
          ) : (
            <>
              <span role="status">날씨를 확인하지 못했어요</span>
              <button type="button" className={styles.retry} onClick={onRefresh}>
                다시 확인
              </button>
            </>
          )}
        </div>
      )}
    </section>
  );
}

function WeatherCell({ reading, currentTime }: { reading: Weather["hourly"][number]; currentTime?: number }) {
  const current = currentTime !== undefined;
  const time = current ? new Date(currentTime) : new Date(reading.time);
  const { kind, label } = weatherCondition(reading);
  const probability = reading.precipitationProbability === null ? null : `${reading.precipitationProbability}%`;
  const temperature = current ? Math.round(reading.temperature * 10) / 10 : Math.round(reading.temperature);

  return (
    <li className={styles.forecastHour} aria-current={current ? "time" : undefined}>
      <time dateTime={current ? time.toISOString() : reading.time}>{current ? "지금" : hourLabel.format(time)}</time>
      {kind === "unknown" ? (
        <span className={styles.forecastArt} role="img" aria-label={label}>
          –
        </span>
      ) : (
        <WeatherArt kind={kind} label={label} />
      )}
      <strong className={styles.temperature}>{temperature}°</strong>
      <span className={styles.rainProbability}>
        <span className={visuallyHidden}>{probability === null ? "강수 확률 정보 없음" : "강수 확률 "}</span>
        <svg width="9" height="9" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
          <path d="M8 1.6c1.3 1.9 4.2 5 4.2 7.5a4.2 4.2 0 0 1-8.4 0c0-2.5 2.9-5.6 4.2-7.5Z" />
        </svg>
        {probability === null ? <span aria-hidden="true">–</span> : <span>{probability}</span>}
      </span>
    </li>
  );
}

function WeatherArt({ kind, label }: { kind: WeatherKind; label: string }) {
  return (
    <svg className={styles.forecastArt} viewBox="8 4 64 64" role="img" aria-label={label} focusable="false">
      {kind === "sun" ? (
        <>
          <circle cx="40" cy="33" r="23" fill="#FFE7AA" opacity=".48" />
          <g className={styles.sunRays} fill="none" stroke="#E9AD46" strokeWidth="2" strokeLinecap="round">
            <path d="M40 8v6m0 38v6M15 33h6m38 0h6M22 15l4 4m28 28 4 4m0-36-4 4M26 47l-4 4" />
          </g>
          <circle cx="40" cy="33" r="12" fill="#FFCF69" stroke="#E9AD46" strokeWidth="1.25" />
        </>
      ) : kind === "night" ? (
        <>
          <circle cx="39" cy="34" r="24" fill="#DCD7FA" opacity=".58" />
          <path d="M43 16a17 17 0 1 0 12 26A16 16 0 0 1 43 16Z" fill="#AE9FDF" stroke="#8575BD" strokeWidth="1.25" />
          <g className={styles.stars} fill="#9C88CD">
            <path d="M57 17l1.5 4 4 1.5-4 1.5-1.5 4-1.5-4-4-1.5 4-1.5Z" />
            <circle cx="65" cy="33" r="1.8" />
          </g>
        </>
      ) : (
        <>
          <g className={styles.clouds} stroke="#A0B6CC" strokeWidth="1.2" strokeLinejoin="round">
            <path d="M41 31a8 8 0 0 1 7-8 9 9 0 0 1 17 2 6 6 0 0 1-1 12H45a6 6 0 0 1-4-6Z" fill="#DCE7F3" />
            <path d="M19 39a9 9 0 0 1 8-9 12 12 0 0 1 23 1 8 8 0 0 1 1 16H27a8 8 0 0 1-8-8Z" fill="#FFF" />
          </g>
          {kind === "rain" && (
            <path
              className={styles.precipitation}
              d="m29 51-3 6m14-6-3 6m14-6-3 6"
              fill="none"
              stroke="#6796D0"
              strokeWidth="2"
              strokeLinecap="round"
            />
          )}
          {kind === "snow" && (
            <g className={styles.precipitation} fill="#81ABCB">
              <circle cx="26" cy="54" r="2" />
              <circle cx="39" cy="59" r="2.2" />
              <circle cx="51" cy="53" r="1.8" />
            </g>
          )}
          {kind === "fog" && (
            <path
              className={styles.clouds}
              d="M23 53h34m-29 6h24"
              fill="none"
              stroke="#9EB5BA"
              strokeWidth="2"
              strokeLinecap="round"
            />
          )}
          {kind === "thunder" && <path className={styles.stars} d="m40 44-6 10h6l-3 10 11-14h-6l4-6Z" fill="#D99E39" />}
        </>
      )}
    </svg>
  );
}
