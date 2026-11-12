import { useLayoutEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { paths, privacyEditionPathFor } from "@/shared/routing/paths";
import * as buttonStyles from "@/shared/styles/button.css";
import { kstDayOf } from "@/shared/utils/kstDayOf";
import { PolicyBody } from "./components/PolicyBody";
import { editionViewFor, type EditionSummary, type EditionView } from "./editionPolicy";
import { EDITIONS } from "./editionSources";
import { editionPeriodLabelOf } from "./privacyCopy";
import { policyDocumentFrom } from "./policyMarkdown";
import * as styles from "./PrivacyPage.css";

const pushPrivacyNotice = policyDocumentFrom(`# 개인정보 처리방침 변경 안내

## 개인정보 처리방침 변경 안내

2026년 11월 14일 시행을 목표로 iOS 출발 알림과 관련한 개인정보 처리 내용을 추가합니다.

| 항목 | 기존 안내 | 추가할 안내 |
| --- | --- | --- |
| 기기 등록 | 출발 알림 기기 등록에 관한 안내 없음 | 알림을 허용한 iOS 기기의 푸시 토큰을 등록하고, 출발 알림을 해당 기기에 보내는 데 사용합니다. |
| Apple에 전달하는 정보 | 출발 알림 발송에 관한 안내 없음 | 알림을 보낼 때 기기 토큰, 알림 제목과 본문, 알림을 누르면 열 화면의 노선 ID, 정류장 ID와 방향을 Apple 푸시 알림 서비스에 보냅니다. |
| 알림 끄기 | 출발 알림을 끄는 방법에 관한 안내 없음 | 즐겨찾기에서 출발 알림을 끄거나 iOS 설정에서 연어버스의 알림 권한을 끌 수 있습니다. |
`);

export function PrivacyPage() {
  const { effectiveDate } = useParams();
  const [now] = useState(() => Date.now());
  const view = editionViewFor(EDITIONS, effectiveDate, now);
  const showPushPrivacyNotice = effectiveDate === undefined && kstDayOf(now) === "2026-11-13";
  useEntryScroll();

  return (
    <AccountLayout>
      <PrivacyHeader title={view.title} />
      {view.kind === "edition" ? (
        <EditionContent view={view} showPushPrivacyNotice={showPushPrivacyNotice} />
      ) : (
        <UnknownEdition />
      )}
    </AccountLayout>
  );
}

// 마이페이지에서 내려간 만큼 처리방침도 내려가 있지 않게 들어올 때마다 맨 위나 주소의 #이 가리키는 제목을 보여 준다.
// Routes에 location을 넘기면 이동 종류가 늘 POP으로 보여서 이동 종류 대신 기록 항목(key)마다 다시 맞춘다.
function useEntryScroll() {
  const { key, hash } = useLocation();
  useLayoutEffect(() => {
    const heading = headingOf(hash);
    if (heading === null) window.scrollTo(0, 0);
    else heading.scrollIntoView();
  }, [key, hash]);
}

function headingOf(hash: string): HTMLElement | null {
  try {
    return hash === "" ? null : document.getElementById(decodeURIComponent(hash.slice(1)));
  } catch {
    return null;
  }
}

function PrivacyHeader({ title }: { title: string }) {
  const location = useLocation();
  const navigate = useNavigate();
  const isFirstPage = location.key === "default";

  return (
    <div className={styles.backTitle}>
      <Link
        className={styles.backIcon}
        to={paths.routeSelect}
        onClick={(event) => {
          if (isFirstPage || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          void navigate(-1);
        }}
        aria-label="이전 화면으로 돌아가기"
      >
        <svg
          width="17"
          height="17"
          viewBox="0 0 17 17"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M13.6 8.5H3.4M7.9 4 3.4 8.5 7.9 13" />
        </svg>
      </Link>
      <h1 className={styles.title}>{title}</h1>
    </div>
  );
}

function EditionContent({
  view,
  showPushPrivacyNotice,
}: {
  view: Extract<EditionView, { kind: "edition" }>;
  showPushPrivacyNotice: boolean;
}) {
  return (
    <>
      <p className={styles.period}>{editionPeriodLabelOf(view.summary)}</p>
      {!view.summary.isCurrent && (
        <Link className={styles.currentEditionLink} to={paths.privacy}>
          지금 적용 중인 처리방침 보기
        </Link>
      )}
      {showPushPrivacyNotice && <PolicyBody blocks={pushPrivacyNotice.blocks} />}
      <PolicyBody blocks={view.edition.document.blocks} />
      {view.others.length > 0 && <OtherEditions summaries={view.others} />}
    </>
  );
}

function UnknownEdition() {
  return (
    <>
      <p className={styles.notice}>이 주소의 처리방침을 찾지 못했어요.</p>
      <Link className={buttonStyles.secondary} to={paths.privacy}>
        지금 적용 중인 처리방침 보기
      </Link>
    </>
  );
}

function OtherEditions({ summaries }: { summaries: readonly EditionSummary[] }) {
  return (
    <section className={styles.otherEditions} aria-labelledby="other-editions-title">
      <h2 id="other-editions-title" className={styles.otherEditionsTitle}>
        다른 판
      </h2>
      <ul className={styles.editionList}>
        {summaries.map((summary) => (
          <li key={summary.effectiveDate}>
            <Link className={styles.editionLink} to={privacyEditionPathFor(summary.effectiveDate)}>
              {editionPeriodLabelOf(summary)}
              <span aria-hidden="true">›</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
