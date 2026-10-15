import { useEffect, useState } from "react";

interface BoardViewport {
  height: number;
  top: number;
}

function readViewport(): BoardViewport {
  return {
    height: window.visualViewport?.height ?? window.innerHeight,
    top: window.visualViewport?.offsetTop ?? 0,
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
    window.visualViewport?.addEventListener("resize", update);
    window.visualViewport?.addEventListener("scroll", update);
    return () => {
      window.removeEventListener("resize", update);
      window.visualViewport?.removeEventListener("resize", update);
      window.visualViewport?.removeEventListener("scroll", update);
    };
  }, []);

  return viewport;
}
