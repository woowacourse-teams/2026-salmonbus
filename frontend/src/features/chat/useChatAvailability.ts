import { useEffect, useState } from "react";
import { availabilityFrom, type ChatAvailability } from "./chatAvailability";

export function useChatAvailability(routeId: string): ChatAvailability | null {
  const [availability, setAvailability] = useState<ChatAvailability | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetch(`/api/chat/rooms/${encodeURIComponent(routeId)}`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) return null;
        return availabilityFrom(await response.json());
      })
      .then((result) => {
        if (result?.routeId === routeId) setAvailability(result);
      })
      .catch(() => null);

    return () => controller.abort();
  }, [routeId]);

  return availability;
}
