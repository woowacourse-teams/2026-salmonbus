import { RouteList } from "./components/RouteList";
import salmongProud from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import salmonbusWordmark from "@/shared/assets/images/salmonbus-wordmark.webp";
import { PageInfo } from "@/shared/components/PageInfo";
import { titleMock, captionMock, routesMock } from "./api/routeSelect.mock";
import * as styles from "./RouteSelectPage.css";

export function RouteSelectPage() {
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.brandLockup}>
          <img className={styles.mascot} src={salmongProud} alt="연어 버스 로고" />
          <img className={styles.wordmark} src={salmonbusWordmark} width={272} height={70} alt="연어 버스 글자 로고" />
        </div>
        <div className={styles.intro}>
          <PageInfo title={titleMock} caption={captionMock} />
        </div>
      </header>
      <main className={styles.main}>
        <RouteList routes={routesMock} />
      </main>
    </div>
  );
}
