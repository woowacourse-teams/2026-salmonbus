import { useEffect, useState } from "react";

interface BoardViewport {
  height: number;
  top: number;
}

function readViewport(): BoardViewport {
  const visual = window.visualViewport;
  if (visual && visual.scale > 1) {
    // 확대 중에도 키보드가 가린 높이에 맞춰 시트를 줄이지 않는다.
    const heightAtScale = document.documentElement.clientHeight / visual.scale;
    return { height: Math.max(visual.height, heightAtScale), top: visual.offsetTop };
  }

  // 기본 배율에서는 키보드가 닫혀도 시트의 배치 기준을 유지한다.
  return { height: window.innerHeight, top: 0 };
}

export function useBoardViewport(): BoardViewport {
  const [viewport, setViewport] = useState(readViewport);

  useEffect(() => {
    const visual = window.visualViewport;
    const update = () => {
      const next = readViewport();
      setViewport((current) => (current.height === next.height && current.top === next.top ? current : next));
    };
    window.addEventListener("resize", update);
    visual?.addEventListener("resize", update);
    visual?.addEventListener("scroll", update);
    return () => {
      window.removeEventListener("resize", update);
      visual?.removeEventListener("resize", update);
      visual?.removeEventListener("scroll", update);
    };
  }, []);

  return viewport;
}
