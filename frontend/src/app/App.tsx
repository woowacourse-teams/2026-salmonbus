import * as styles from "./App.css";
import { RouteSelectPage } from "@/pages/route-select/RouteSelectPage";

export function App() {
  return (
    <main className={styles.layout}>
      <RouteSelectPage />
    </main>
  );
}
