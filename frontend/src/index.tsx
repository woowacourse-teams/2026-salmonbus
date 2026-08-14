import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles/reset.css";
import { App } from "./App";
import { initAnalytics } from "./analytics";

const container = document.getElementById("root");
if (!container) {
  throw new Error("root 요소를 찾을 수 없습니다");
}

initAnalytics();

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
