import { describe, expect, it } from "@jest/globals";
import { nicknameErrorOf } from "./nicknamePolicy";

describe("닉네임 입력", () => {
  it.each(["가", "12345678901", "가 나", "가나!", "가나😀", "ㄱㄴ"])("잘못된 닉네임 %s를 거절한다", (nickname) => {
    expect(nicknameErrorOf(nickname)).not.toBeNull();
  });
  it("앞뒤 공백만 제거하고 한글·영문·숫자를 허용한다", () => {
    expect(nicknameErrorOf("  살몽A1  ")).toBeNull();
  });
});
