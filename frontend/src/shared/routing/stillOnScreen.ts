import { useEffect, useRef } from "react";
import { useLocation } from "react-router";

// 라우터는 주소를 바로 바꾸고 화면 교체는 transition으로 미룬다. 그 사이에도 떠나는 화면은 마운트되어 있으니 지금 주소가 이 화면을 그린 주소인지도 본다.
// 마운트 여부는 떠났다가 다시 들어온 경우를 막는다. 그때는 주소가 같아도 이 화면은 이미 사라진 화면이다.
// Routes 안의 주소는 미리 그리는 한 프레임 동안 실제 주소보다 앞선다(useFocusKeepingNavigation). 그 화면에서는 이 판단이 한 프레임 동안 거짓이 된다.
export function useStillOnScreen(): () => boolean {
  const { pathname } = useLocation();
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return () => mounted.current && window.location.pathname === pathname;
}
