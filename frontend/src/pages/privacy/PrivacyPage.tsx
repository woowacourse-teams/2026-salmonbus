import { useLayoutEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { paths, privacyEditionPathFor } from "@/shared/routing/paths";
import * as buttonStyles from "@/shared/styles/button.css";
import { PolicyBody } from "./components/PolicyBody";
import { editionViewFor, type EditionSummary, type EditionView } from "./editionPolicy";
import { EDITIONS } from "./editionSources";
import { editionPeriodLabelOf } from "./privacyCopy";
import * as styles from "./PrivacyPage.css";

export function PrivacyPage() {
  const { effectiveDate } = useParams();
  const [now] = useState(() => Date.now());
  const view = editionViewFor(EDITIONS, effectiveDate, now);
  useEntryScroll();

  return (
    <AccountLayout>
      <PrivacyHeader title={view.title} />
      {view.kind === "edition" ? <EditionContent view={view} /> : <UnknownEdition />}
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

function EditionContent({ view }: { view: Extract<EditionView, { kind: "edition" }> }) {
  return (
    <>
      <p className={styles.period}>{editionPeriodLabelOf(view.summary)}</p>
      {!view.summary.isCurrent && (
        <Link className={styles.currentEditionLink} to={paths.privacy}>
          지금 적용 중인 처리방침 보기
        </Link>
      )}
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
