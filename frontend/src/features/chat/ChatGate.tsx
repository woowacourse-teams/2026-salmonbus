import { Component, lazy, Suspense, type ReactNode } from "react";
import { useMatch } from "react-router";
import { paths } from "@/shared/routing/paths";
import { useChatAvailability } from "./useChatAvailability";

interface ChatAvailabilityGateProps {
  routeId: string;
}

interface ChatErrorBoundaryProps {
  children: ReactNode;
}

interface ChatErrorBoundaryState {
  failed: boolean;
}

const ChatWidget = lazy(async () => {
  const module = await import("./ChatWidget");
  return { default: module.ChatWidget };
});

export function ChatGate() {
  return (
    <ChatErrorBoundary>
      <ChatRouteGate />
    </ChatErrorBoundary>
  );
}

function ChatRouteGate() {
  const match = useMatch(paths.board);
  const routeId = match?.params.routeId ?? null;

  if (routeId === null) return null;
  return <ChatAvailabilityGate key={routeId} routeId={routeId} />;
}

function ChatAvailabilityGate({ routeId }: ChatAvailabilityGateProps) {
  const availability = useChatAvailability(routeId);

  if (availability === null) return null;
  return (
    <Suspense fallback={null}>
      <ChatWidget availability={availability} />
    </Suspense>
  );
}

class ChatErrorBoundary extends Component<ChatErrorBoundaryProps, ChatErrorBoundaryState> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}
