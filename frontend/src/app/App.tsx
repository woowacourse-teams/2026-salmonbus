import { BrowserRouter } from "react-router";
import { AuthProvider } from "./providers/AuthProvider";
import { AnalyticsMember } from "./AnalyticsMember";
import { AppNotifications } from "./AppNotifications";
import { AuthReturn } from "./AuthReturn";
import { AppHistorySwipe } from "./AppHistorySwipe";
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
          <AppHistorySwipe />
          <AppRoutes />
        </FavoritesProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
