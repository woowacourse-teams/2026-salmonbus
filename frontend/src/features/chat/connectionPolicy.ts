export type ConnectionState = "connecting" | "reconnecting" | "ready" | "failed" | "unavailable";

const RECONNECT_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 16_000] as const;
const FINAL_CLOSE_CODES: ReadonlySet<number> = new Set([1003, 1008]);

export function reconnectDelayMs(attempt: number): number | null {
  return RECONNECT_DELAYS_MS[attempt] ?? null;
}

export function shouldReconnect(closeCode: number): boolean {
  return !FINAL_CLOSE_CODES.has(closeCode);
}

export function isConnectionStalled(connection: ConnectionState | "idle"): boolean {
  return connection === "failed" || connection === "unavailable";
}
