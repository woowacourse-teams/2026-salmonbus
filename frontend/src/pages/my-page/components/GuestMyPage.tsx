import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { LoginButton } from "@/shared/components/LoginButton";
import { PrivacySection } from "./PrivacySection";
import { WhatsNewLink } from "./WhatsNewLink";
import { menuRow } from "./menuRow.css";
import * as styles from "./GuestMyPage.css";

export function GuestMyPage() {
  return (
    <div className={styles.page}>
      <h1 className={visuallyHidden}>마이페이지</h1>
      <LoginButton returnTo="/mypage" menuRowClassName={menuRow} />
      <WhatsNewLink />
      <PrivacySection />
    </div>
  );
}
