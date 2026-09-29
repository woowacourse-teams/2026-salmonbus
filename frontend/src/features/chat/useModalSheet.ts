import { useEffect } from "react";

export function useModalSheet(modal: boolean) {
  useEffect(() => {
    if (!modal) return;
    const root = document.getElementById("root");
    const previousOverflow = document.body.style.overflow;
    const previousInert = root?.inert ?? false;
    document.body.style.overflow = "hidden";
    if (root !== null) root.inert = true;
    return () => {
      document.body.style.overflow = previousOverflow;
      if (root !== null) root.inert = previousInert;
    };
  }, [modal]);
}
