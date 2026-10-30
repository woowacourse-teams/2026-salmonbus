/** 누르거나 끄는 순간 바로 울리는 손 동작 진동. */
export type GestureHapticKind = "impact" | "selection";

/** 서버 작업의 응답이 온 뒤 성공과 실패를 알리는 결과 진동. */
export type ResultHapticKind = "success" | "error";

/**
 * 웹과 앱이 함께 쓰는 진동 종류의 유일한 목록. 앱의 app/haptic/playHaptic.ts가 이 파일을 import type으로 읽으므로
 * 다른 모듈을 import하지 않는다. 종류가 바뀌면 앱 tsc가 앱의 진동 표에서 막는다.
 */
export type HapticKind = GestureHapticKind | ResultHapticKind;

export type HapticCommands = { [Kind in HapticKind as `haptic.${Kind}`]: Record<string, never> };
