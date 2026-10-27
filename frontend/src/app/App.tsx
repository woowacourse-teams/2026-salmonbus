import { BrowserRouter } from "react-router";
import { AuthProvider } from "./providers/AuthProvider";
import { AnalyticsMember } from "./AnalyticsMember";
import { AuthReturn } from "./AuthReturn";
import { AppRoutes } from "./routes";
import { FavoritesProvider } from "./providers/FavoritesProvider";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <FavoritesProvider>
          <AnalyticsMember />
          <AuthReturn />
          <AppRoutes />
        </FavoritesProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
