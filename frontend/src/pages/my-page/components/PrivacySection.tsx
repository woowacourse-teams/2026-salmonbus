import { useState } from "react";
import { Link } from "react-router";
import { isAnalyticsOptedOut, setAnalyticsOptOut } from "@/shared/analytics/analytics";
import { paths } from "@/shared/routing/paths";
import * as styles from "./PrivacySection.css";

export function PrivacySection() {
  const [participating, setParticipating] = useState(() => !isAnalyticsOptedOut());
  return (
    <section className={styles.section} aria-labelledby="privacy-title">
      <h2 id="privacy-title" className={styles.heading}>
        개인정보
      </h2>
      <label className={styles.row}>
        서비스 개선에 참여하기
        <span className={styles.track}>
          <input
            className={styles.input}
            type="checkbox"
            role="switch"
            checked={participating}
            aria-describedby="analytics-participation-help"
            onChange={(event) => {
              setParticipating(event.target.checked);
              setAnalyticsOptOut(!event.target.checked);
            }}
          />
          <span className={styles.thumb} aria-hidden="true" />
        </span>
      </label>
      <p id="analytics-participation-help" className={styles.description}>
        끄면 이 기기의 이용 기록과 화면 기록을 Amplitude(미국)로 보내지 않고, 안내와 설문도 뜨지 않아요.
      </p>
      <Link className={styles.policyLink} to={paths.privacy}>
        개인정보 처리방침<span aria-hidden="true">›</span>
      </Link>
    </section>
  );
}
