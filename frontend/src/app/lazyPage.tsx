import {
  Component,
  createElement,
  startTransition,
  use,
  type ComponentType,
  type ReactElement,
  type ReactNode,
} from "react";
import { ErrorView } from "@/shared/components/ErrorView";
import { LoadError } from "@/shared/components/LoadError";
import { RetryButton } from "@/shared/components/RetryButton";
import type { PageLoader, PageLoadStatus } from "@/shared/routing/pageLoader";
import type { LazyPageName } from "@/shared/routing/pagePreload";

export interface LazyPage {
  readonly name: LazyPageName;
  readonly Page: ComponentType;
  preload: () => void;
  retry: () => void;
  status: () => PageLoadStatus;
}

export function createLazyPage<M>(
  name: LazyPageName,
  group: PageLoader<M>,
  pick: (module: M) => ComponentType,
): LazyPage {
  function Page() {
    const snapshot = group.snapshot();
    if (snapshot.status === "failed") throw snapshot.error;
    const loaded = snapshot.status === "ready" ? snapshot.value : use(group.load());
    return createElement(pick(loaded));
  }
  return {
    name,
    Page,
    preload: group.preload,
    retry: group.reset,
    status: () => group.snapshot().status,
  };
}

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
