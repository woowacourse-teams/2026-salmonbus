import { useEffect, useState } from "react";

interface BoardViewport {
  height: number;
  top: number;
}

function readViewport(): BoardViewport {
  // 키보드가 닫히며 변하는 시각적 뷰포트 대신 고정 시트의 기준인 레이아웃 뷰포트를 쓴다.
  return {
    height: window.innerHeight,
    top: 0,
  };
}

export function useBoardViewport(): BoardViewport {
  const [viewport, setViewport] = useState(readViewport);

  useEffect(() => {
    const update = () => {
      const next = readViewport();
      setViewport((current) => (current.height === next.height && current.top === next.top ? current : next));
    };
    window.addEventListener("resize", update);
    return () => {
      window.removeEventListener("resize", update);
    };
  }, []);

  return viewport;
}
