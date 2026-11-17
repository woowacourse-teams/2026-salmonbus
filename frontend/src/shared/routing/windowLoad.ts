let windowLoad: Promise<void> | null = null;

export function afterWindowLoad(): Promise<void> {
  if (document.readyState === "complete") return Promise.resolve();
  windowLoad ??= new Promise((resolve) => window.addEventListener("load", () => resolve(), { once: true }));
  return windowLoad;
}
