import { Component, startTransition, type ReactElement, type ReactNode } from "react";
import { ErrorView } from "@/shared/components/ErrorView";
import { LoadError } from "@/shared/components/LoadError";
import { RetryButton } from "@/shared/components/RetryButton";
import type { LazyPage } from "./createLazyPage";

interface RouteErrorBoundaryProps {
  page: LazyPage;
  variant: "sheet" | "page";
  children: ReactNode;
}

interface RouteErrorBoundaryState {
  error: unknown;
}

const LOAD_FAILED_TITLE = "화면을 불러오지 못했어요";
const RETRY_CAPTION = "네트워크 연결을 확인하고 다시 시도해 주세요.";
const RETRY_LABEL = "다시 시도하기";

class RouteErrorBoundary extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  state: RouteErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: unknown): Partial<RouteErrorBoundaryState> {
    return { error };
  }

  retry = () => {
    startTransition(() => {
      this.props.page.retry();
      this.setState({ error: null });
    });
  };

  render() {
    const { error } = this.state;
    if (error === null) return this.props.children;
    if (this.props.page.status() !== "failed") throw error;
    if (this.props.variant === "sheet") {
      return (
        <LoadError title={LOAD_FAILED_TITLE} description={RETRY_CAPTION}>
          <RetryButton onRetry={this.retry}>{RETRY_LABEL}</RetryButton>
        </LoadError>
      );
    }
    return (
      <ErrorView title={LOAD_FAILED_TITLE} caption={RETRY_CAPTION} retryLabel={RETRY_LABEL} onRetry={this.retry} />
    );
  }
}

export function lazyRouteElement(page: LazyPage, variant: "sheet" | "page"): ReactElement {
  return (
    <RouteErrorBoundary key={page.name} page={page} variant={variant}>
      <page.Page />
    </RouteErrorBoundary>
  );
}
