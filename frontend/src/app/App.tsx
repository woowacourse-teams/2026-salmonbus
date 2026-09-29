import { BrowserRouter } from "react-router";
import { ChatGate } from "@/features/chat";
import { AppRoutes } from "./routes";

export function App() {
  return (
    <BrowserRouter>
      <AppRoutes />
      <ChatGate />
    </BrowserRouter>
  );
}
