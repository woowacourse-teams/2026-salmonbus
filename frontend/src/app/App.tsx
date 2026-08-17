import { useState } from "react";
import * as styles from "./App.css";

export function App() {
  const [count, setCount] = useState(0);

  return (
    <main className={styles.layout}>
      <h1>salmonbus</h1>
      <p>Webpack + React + TypeScript 환경이 동작하고 있습니다.</p>
      <button className={styles.counterButton} onClick={() => setCount(count + 1)}>
        클릭 {count}회
      </button>
    </main>
  );
}
