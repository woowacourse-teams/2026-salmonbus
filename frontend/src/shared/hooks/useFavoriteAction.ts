import { useEffect, useRef, useState } from "react";
import type { ApiResult } from "@/shared/api/client";
import { useFavorites } from "./useFavorites";
import { favoriteErrorOf } from "@/shared/utils/favoritePolicy";

export function useFavoriteAction() {
  const active = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { reload } = useFavorites();
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function run(operation: () => Promise<ApiResult<unknown>>): Promise<boolean> {
    if (busy) return false;
    setBusy(true);
    setError(null);
    const result = await operation();
    if (result.ok) reload();
    if (!active.current) return false;
    setBusy(false);
    if (!result.ok) setError(favoriteErrorOf(result.failure));
    return result.ok;
  }
  return { run, busy, error, setError };
}
