type InputMethod = "pointer" | "keyboard";

export const POINTER_INPUT = '[data-input-method="pointer"]';

export function recordInputMethod(method: InputMethod) {
  document.documentElement.dataset.inputMethod = method;
}

// 화면을 이동한 뒤 코드로 포커스를 옮겨도 마지막 입력 방식은 유지한다.
// 새로 연 페이지는 키를 누르기 전까지 포인터로 본다. 로그인에서 돌아오자마자 열리는 확인 창이 그런 경우다.
// 포커스가 body에 있을 때 누른 Tab도 받도록 document에서 듣는다.
export function initInputMethod() {
  recordInputMethod("pointer");
  document.addEventListener("pointerdown", () => recordInputMethod("pointer"), true);
  document.addEventListener("keydown", () => recordInputMethod("keyboard"), true);
}
