import { BrowserRouter } from "react-router";
import { ChatGate } from "@/features/chat";
import { AuthProvider, AuthReturn } from "@/features/auth";
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
