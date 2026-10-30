interface StopName {
  id: string;
  name: string;
  group: string;
  width: number;
}

const segmenter = new Intl.Segmenter("ko", { granularity: "grapheme" });
const characters = (text: string) => Array.from(segmenter.segment(text), (part) => part.segment);

export function stopNameLabels(stops: StopName[], measure: (text: string) => number): Record<string, string> {
  const buckets = new Map<string, StopName[]>();
  for (const stop of stops) {
    if (stop.width <= 0 || measure(stop.name) <= stop.width) continue;
    const chars = characters(stop.name);
    let length = chars.length - 1;
    while (length > 0 && measure(chars.slice(0, length).join("") + "…") > stop.width) length--;
    const key = JSON.stringify([stop.group, chars.slice(0, length).join("") + "…"]);
    const bucket = buckets.get(key) ?? [];
    bucket.push(stop);
    buckets.set(key, bucket);
  }
  const labels: Record<string, string> = {};
  for (const bucket of buckets.values()) {
    if (new Set(bucket.map((stop) => stop.name)).size < 2) continue;
    const names = bucket.map((stop) => characters(stop.name));
    let shared = 0;
    while (names[0]![shared] !== undefined && names.every((name) => name[shared] === names[0]![shared])) shared++;
    const alternatives = bucket.map((stop, index) => {
      const chars = names[index]!;
      const suffix = chars.slice(shared).join("");
      if (!suffix || measure("…" + suffix) > stop.width) return null;
      let prefix = shared;
      while (prefix > 0 && measure(chars.slice(0, prefix).join("") + "…" + suffix) > stop.width) prefix--;
      return chars.slice(0, prefix).join("") + "…" + suffix;
    });
    // shortcut: 차이가 나는 뒷부분도 한 줄에 안 들어가면 기존 표시 유지, 그런 이름을 지원할 때 보완한다.
    if (
      alternatives.some((label) => label === null) ||
      new Set(alternatives).size !== new Set(bucket.map((stop) => stop.name)).size
    )
      continue;
    bucket.forEach((stop, index) => {
      labels[stop.id] = alternatives[index]!;
    });
  }
  return labels;
}
