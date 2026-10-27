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
          {/* 로그인 결과 이벤트에 회원 ID가 붙도록 AuthReturn보다 먼저 둔다. */}
          <AnalyticsMember />
          <AuthReturn />
          <AppRoutes />
        </FavoritesProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
