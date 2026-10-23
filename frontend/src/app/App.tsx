import { BrowserRouter } from "react-router";
import { AuthProvider } from "./providers/AuthProvider";
import { AuthReturn } from "./AuthReturn";
import { AppRoutes } from "./routes";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AuthReturn />
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
