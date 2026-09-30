// 참고 영상(가로로 넓어지는 배너)의 폭 변화에 맞춘 스프링. 약 10% 넘쳤다가 1.1초에 멈춘다
const DAMPING_RATIO = 0.64;
const NATURAL_FREQUENCY = 4.95;
const INITIAL_VELOCITY = 3.2;
const FRAME_MS = 1000 / 60;

export const RESIZE_DURATION_MS = 1100;
export const RESIZING_ATTRIBUTE = "data-resizing";

// 폭이 목표에 처음 닿는 약 410ms에 문구와 숫자가 다 나타나도록 150ms 페이드를 늦춰 시작한다
export const REVEAL_FADE_MS = 150;
export const REVEAL_DELAY_MS = 260;

function springProgressAt(seconds: number) {
  const decay = DAMPING_RATIO * NATURAL_FREQUENCY;
  const frequency = NATURAL_FREQUENCY * Math.sqrt(1 - DAMPING_RATIO ** 2);
  const sineWeight = (decay - INITIAL_VELOCITY) / frequency;
  return 1 - Math.exp(-decay * seconds) * (Math.cos(frequency * seconds) + sineWeight * Math.sin(frequency * seconds));
}

export function resizeKeyframesFor(fromWidth: number, toWidth: number): Keyframe[] {
  const frameCount = Math.round(RESIZE_DURATION_MS / FRAME_MS);
  const remainder = 1 - springProgressAt(RESIZE_DURATION_MS / 1000);

  return Array.from({ length: frameCount + 1 }, (_, frame) => {
    const ratio = frame / frameCount;
    const progress = springProgressAt((ratio * RESIZE_DURATION_MS) / 1000) + remainder * ratio;
    return { width: `${fromWidth + (toWidth - fromWidth) * progress}px` };
  });
}
