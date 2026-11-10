import { useState, useSyncExternalStore } from "react";
import { Link } from "react-router";
import { isAnalyticsOptedOut, setAnalyticsOptOut, subscribeAnalyticsOptOut } from "@/shared/analytics/analytics";
import { Switch } from "@/shared/components/Switch";
import { paths } from "@/shared/routing/paths";
import * as styles from "./PrivacySection.css";

export function PrivacySection() {
  const optedOut = useSyncExternalStore(subscribeAnalyticsOptOut, isAnalyticsOptedOut);
  const [descriptionExpanded, setDescriptionExpanded] = useState(false);
  return (
    <section className={styles.section} aria-labelledby="privacy-title">
      <h2 id="privacy-title" className={styles.heading}>
        개인정보
      </h2>
      <div className={styles.row}>
        <button
          type="button"
          className={styles.descriptionToggle}
          aria-expanded={descriptionExpanded}
          aria-controls="analytics-participation-help"
          onClick={() => setDescriptionExpanded(!descriptionExpanded)}
        >
          <span id="analytics-participation-label">서비스 개선에 참여하기</span>
          <svg
            className={styles.chevron}
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m6 4 4 4-4 4" />
          </svg>
        </button>
        <Switch
          checked={!optedOut}
          aria-labelledby="analytics-participation-label"
          aria-describedby="analytics-participation-help"
          onChange={(event) => setAnalyticsOptOut(!event.target.checked)}
        />
      </div>
      <div className={styles.descriptionPanel} aria-hidden={!descriptionExpanded}>
        <p id="analytics-participation-help" className={styles.description}>
          끄면 이 기기의 이용 기록과 화면 기록을 Amplitude(미국)로 보내지 않고, 안내와 설문도 뜨지 않아요.
        </p>
      </div>
      <Link className={styles.policyLink} to={paths.privacy}>
        개인정보 처리방침<span aria-hidden="true">›</span>
      </Link>
    </section>
  );
}
