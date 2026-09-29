export function codePointLength(value: string): number {
  return [...value].length;
}

export function normalizedBody(value: string, maxCodePoints: number): string | null {
  const trimmed = value.trim();
  const length = codePointLength(trimmed);
  return length === 0 || length > maxCodePoints ? null : trimmed;
}

export function limitCodePoints(value: string, maximum: number): string {
  return [...value].slice(0, maximum).join("");
}
