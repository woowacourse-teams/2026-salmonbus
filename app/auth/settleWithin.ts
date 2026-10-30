/** work가 ms 안에 끝나지 않으면 실패로 끝낸다. 끝난 뒤에는 타이머를 지운다. */
export function settleWithin<T>(work: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${ms}ms 안에 끝나지 않았습니다`)), ms);
  });
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer));
}
