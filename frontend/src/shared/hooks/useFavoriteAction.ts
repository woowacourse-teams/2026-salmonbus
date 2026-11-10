import { useEffect, useRef, useState } from "react";
import type { ApiResult } from "@/shared/api/client";
import { useResultHaptic } from "@/shared/haptic/haptic";
import { useFavorites } from "./useFavorites";
import { favoriteErrorOf, type FavoriteOperation } from "@/shared/utils/favoritePolicy";

interface FavoriteActionOptions {
  /** 켜면 응답에 맞춰 결과 진동을 울린다. 켤지는 화면이 정하고, 언제 울릴지는 결과를 아는 run이 정한다. */
  resultHaptic?: boolean;
  kind: FavoriteOperation;
}

interface FavoriteActionFailure {
  operation: FavoriteOperation;
  message: string;
}

export function useFavoriteAction() {
  const active = useRef(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<FavoriteActionFailure | null>(null);
  const { reload } = useFavorites();
  const playResultHaptic = useResultHaptic();
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function run(
    operation: () => Promise<ApiResult<unknown>>,
    { resultHaptic = false, kind }: FavoriteActionOptions,
  ): Promise<boolean> {
    if (busy) return false;
    setBusy(true);
    setFailure(null);
    const result = await operation();
    if (result.ok) reload();
    if (!active.current) return false;
    setBusy(false);
    if (!result.ok) setFailure({ operation: kind, message: favoriteErrorOf(result.failure, kind) });
    if (resultHaptic) playResultHaptic(result.ok);
    return result.ok;
  }
  return { run, busy, failure, error: failure?.message ?? null, clearError: () => setFailure(null) };
}
