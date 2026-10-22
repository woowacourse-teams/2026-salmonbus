import { BrowserRouter } from "react-router";
import { ChatGate } from "@/features/chat";
import { NumericKeypadProvider } from "@/shared/keypad/NumericKeypadProvider";
import { AppRoutes } from "./routes";

export function App() {
  return (
    <BrowserRouter>
      <NumericKeypadProvider>
        <AppRoutes />
      </NumericKeypadProvider>
      <ChatGate />
    </BrowserRouter>
  );
}
