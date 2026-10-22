import { BrowserRouter } from "react-router";
import { ChatGate } from "@/features/chat";
import { AuthProvider } from "./providers/AuthProvider";
import { AuthReturn } from "./AuthReturn";
import { AppRoutes } from "./routes";
import { FavoritesProvider } from "./providers/FavoritesProvider";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <FavoritesProvider>
          <AuthReturn />
          <AppRoutes />
          <ChatGate />
        </FavoritesProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
