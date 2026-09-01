import { Route, Routes, useNavigate } from "react-router";
import { RouteSelectPage } from "@/pages/route-select";
import { VerdictBoardPage } from "@/pages/verdict-board";
import { paths } from "@/shared/routing/paths";
import { ErrorView } from "@/shared/components/ErrorView";

export function AppRoutes() {
  const navigate = useNavigate();

  const handleBack = () => {
    navigate(-1);
  };

  const handleRetry = () => {
    window.location.reload();
  };

  return (
    <Routes>
      <Route path={paths.routeSelect} element={<RouteSelectPage />} />
      <Route path={paths.board} element={<VerdictBoardPage />} />
      <Route
        path={paths.error}
        element={<ErrorView title="에러 제목" caption="에러 설명" onBack={handleBack} onRetry={handleRetry} />}
      />
    </Routes>
  );
}
