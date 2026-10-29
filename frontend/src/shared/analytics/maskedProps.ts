/**
 * 회원이 지은 이름처럼 Amplitude로 보내면 안 되는 글자를 감싼 요소에 붙인다.
 * Session Replay는 amp-mask 클래스만, 클릭 자동 수집은 data-amp-mask 속성만 보고 글자를 가린다.
 * 자동 수집은 aria-label을 글자와 따로 싣기 때문에 data-amp-mask-attributes로 함께 가린다.
 */
export function maskedPropsFor(className?: string) {
  return {
    className: className ? `${className} amp-mask` : "amp-mask",
    "data-amp-mask": "",
    "data-amp-mask-attributes": "aria-label",
  };
}
