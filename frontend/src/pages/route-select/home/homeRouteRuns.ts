import type { Point } from "./homeMapData";

export function homeRouteRuns(paths: Point[][]): Point[][] {
  const seen = new Set<string>();
  const runs: Point[][] = [];

  for (const path of paths) {
    let previous: Point | undefined;
    let run: Point[] | undefined;
    for (const point of path) {
      if (previous) {
        const from = previous.join(",");
        const to = point.join(",");
        if (from === to) continue;
        const edge = from < to ? `${from}|${to}` : `${to}|${from}`;
        if (seen.has(edge)) {
          run = undefined;
        } else {
          seen.add(edge);
          if (!run) {
            run = [previous];
            runs.push(run);
          }
          run.push(point);
        }
      }
      previous = point;
    }
  }
  return runs;
}
