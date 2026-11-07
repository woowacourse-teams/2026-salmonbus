import { playHaptic } from "./haptic";

// 결과 진동은 응답이 온 뒤 화면에 남아 있을 때만 울려야 해서 playHaptic으로 보내지 않는다.
// 누가 playHaptic의 타입을 넓혀서 에러가 사라지면 @ts-expect-error가 거꾸로 에러를 내니, CI 타입 검사에서 바로 걸린다.
function rejectsResultHaptic(): void {
  // @ts-expect-error success는 결과 진동이라 playHaptic으로 보낼 수 없다
  playHaptic("success");
  // @ts-expect-error error는 결과 진동이라 playHaptic으로 보낼 수 없다
  playHaptic("error");
}
