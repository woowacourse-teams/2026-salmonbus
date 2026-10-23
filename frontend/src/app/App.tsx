import { BrowserRouter } from "react-router";
import { ChatGate } from "@/features/chat";
import { NumericKeypadProvider } from "@/shared/keypad/NumericKeypadProvider";
import { AuthProvider } from "./providers/AuthProvider";
import { AuthReturn } from "./AuthReturn";
import { AppRoutes } from "./routes";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AuthReturn />
        <NumericKeypadProvider>
          <AppRoutes />
        </NumericKeypadProvider>
        <ChatGate />
      </AuthProvider>
    </BrowserRouter>
  );
}
