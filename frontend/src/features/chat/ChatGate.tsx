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

const CHAT_ROUTE_IDS = new Set(["204000057", "234000050"]);

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

  if (routeId === null || !CHAT_ROUTE_IDS.has(routeId)) return null;
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
