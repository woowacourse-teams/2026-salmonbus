export function nicknameErrorOf(input: string): string | null {
  const nickname = input.trim();
  if (nickname.length < 2 || nickname.length > 10) return "닉네임은 2자 이상 10자 이하로 입력해 주세요.";
  if (!/^[가-힣a-zA-Z0-9]+$/.test(nickname)) return "닉네임은 한글, 영문, 숫자만 사용할 수 있어요.";
  return null;
}
