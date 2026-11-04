import { BrowserRouter } from "react-router";
import { AuthProvider } from "./providers/AuthProvider";
import { AnalyticsMember } from "./AnalyticsMember";
import { AppNotifications } from "./AppNotifications";
import { AuthReturn } from "./AuthReturn";
import { AppRoutes } from "./routes";
import { FavoritesProvider } from "./providers/FavoritesProvider";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <FavoritesProvider>
          <AnalyticsMember />
          <AppNotifications />
          <AuthReturn />
          <AppRoutes />
        </FavoritesProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
