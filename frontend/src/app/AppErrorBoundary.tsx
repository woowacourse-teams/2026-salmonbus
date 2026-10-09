import { Component, type ReactNode } from "react";
import { ErrorView } from "@/shared/components/ErrorView";

interface AppErrorBoundaryProps {
  children: ReactNode;
}

interface AppErrorBoundaryState {
  failed: boolean;
}

export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? (
      <ErrorView
        title="앗, 문제가 생겼어요"
        caption="화면을 새로고침해 주세요."
        onRetry={() => window.location.reload()}
      />
    ) : (
      this.props.children
    );
  }
}
