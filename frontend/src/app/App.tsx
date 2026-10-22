import { BrowserRouter } from "react-router";
import { ChatGate } from "@/features/chat";
import { AuthProvider } from "./providers/AuthProvider";
import { AuthReturn } from "./AuthReturn";
import { AppRoutes } from "./routes";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AuthReturn />
        <AppRoutes />
        <ChatGate />
      </AuthProvider>
    </BrowserRouter>
  );
}
