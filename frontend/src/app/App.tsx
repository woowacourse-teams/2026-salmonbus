import { BrowserRouter } from "react-router";
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
        </FavoritesProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
