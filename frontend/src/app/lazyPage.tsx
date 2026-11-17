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
    preload: () => {
      group.load().catch(() => undefined);
    },
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
  failures: number;
}

const LOAD_FAILED_TITLE = "화면을 불러오지 못했어요";
const RETRY_CAPTION = "네트워크 연결을 확인하고 다시 시도해 주세요.";
const RELOAD_CAPTION = "앱이 업데이트됐을 수 있어요. 화면을 새로고침해 주세요.";

class RouteErrorBoundary extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  state: RouteErrorBoundaryState = { error: null, failures: 0 };

  static getDerivedStateFromError(error: unknown): Partial<RouteErrorBoundaryState> {
    return { error };
  }

  retry = () => {
    if (this.state.failures > 0) {
      window.location.reload();
      return;
    }
    startTransition(() => {
      this.props.page.retry();
      this.setState(({ failures }) => ({ error: null, failures: failures + 1 }));
    });
  };

  render() {
    const { error, failures } = this.state;
    if (error === null) return this.props.children;
    if (this.props.page.status() !== "failed") throw error;
    const reloading = failures > 0;
    const retryLabel = reloading ? "새로고침" : "다시 시도하기";
    const caption = reloading ? RELOAD_CAPTION : RETRY_CAPTION;
    if (this.props.variant === "sheet") {
      return (
        <LoadError title={LOAD_FAILED_TITLE} description={caption}>
          <RetryButton onRetry={this.retry}>{retryLabel}</RetryButton>
        </LoadError>
      );
    }
    return <ErrorView title={LOAD_FAILED_TITLE} caption={caption} retryLabel={retryLabel} onRetry={this.retry} />;
  }
}

export function lazyRouteElement(page: LazyPage, variant: "sheet" | "page"): ReactElement {
  return (
    <RouteErrorBoundary key={page.name} page={page} variant={variant}>
      <page.Page />
    </RouteErrorBoundary>
  );
}
