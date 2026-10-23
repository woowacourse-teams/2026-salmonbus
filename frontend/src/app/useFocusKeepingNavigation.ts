import { startTransition, useEffect, useState } from "react";
import { useLocation, useNavigate, type Location } from "react-router";
import type { NavigateKeepingFocus } from "@/shared/routing/focusKeepingNavigation";

// iOS Safari는 주소가 바뀐 직후 다른 입력창으로 옮겨진 포커스에는 키보드를 띄우지 않을 때가 있다.
// 그래서 다음 화면을 지금 주소에서 먼저 그려 포커스를 넘겨받게 하고, 한 프레임 그려진 뒤에 실제 주소를 바꾼다.
// 그 한 프레임 동안 Routes 안쪽은 다음 주소를, 바깥쪽(AuthReturn)은 지금 주소를 본다.
// 바깥쪽은 /login과 판정 보드 주소만 보므로 이 차이로 동작이 달라지지 않는다.
// shownLocation은 미리 그릴 때만이 아니라 늘 Routes에 넘겨야 한다. 아니면 실제 주소로 바뀔 때 화면이 다시 마운트되어 포커스가 또 옮겨진다.
export function useFocusKeepingNavigation(): { shownLocation: Location; navigateKeepingFocus: NavigateKeepingFocus } {
  const location = useLocation();
  const navigate = useNavigate();
  const [pending, setPending] = useState<{ pathname: string; state: unknown } | null>(null);

  useEffect(() => {
    if (pending === null) return;
    let timer = 0;
    const frame = window.requestAnimationFrame(() => {
      timer = window.setTimeout(() => {
        startTransition(() => {
          void navigate(pending.pathname, { state: pending.state });
          setPending(null);
        });
      }, 0);
    });
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(timer);
    };
  }, [pending, navigate]);

  const shownLocation =
    pending === null
      ? location
      : { ...location, pathname: pending.pathname, search: "", hash: "", state: pending.state };
  return { shownLocation, navigateKeepingFocus: (pathname, state) => setPending({ pathname, state }) };
}
