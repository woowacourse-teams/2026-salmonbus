import { describe, expect, it } from "@jest/globals";
import { stopNameLabels } from "./stopNameLabels";

const first = { id: "first", group: "6801-UP", name: "더샵지제역센트럴파크1.2단지(중)", width: 11 };
const second = { id: "second", group: "6801-UP", name: "더샵지제역센트럴파크2.3단지(중)", width: 11 };
const measure = (text: string) => Array.from(text).length;

describe("함께 표시되는 정류장 이름 구분", () => {
  it("끝 말줄임이 겹칠 때 차이가 나는 뒷부분을 보존한다", () => {
    expect(stopNameLabels([first, second], measure)).toEqual({ first: "더샵…1.2단지(중)", second: "더샵…2.3단지(중)" });
  });
  it("하나를 삭제하거나, 폭이 넓어져 구분되면 기본 표시로 돌아간다", () => {
    expect(stopNameLabels([first], measure)).toEqual({});
    expect(
      stopNameLabels(
        [
          { ...first, width: 100 },
          { ...second, width: 100 },
        ],
        measure,
      ),
    ).toEqual({});
  });
  it("다른 노선·방면과 원래부터 같은 이름은 예외 표시하지 않는다", () => {
    expect(stopNameLabels([first, { ...second, group: "6801-DOWN" }], measure)).toEqual({});
    expect(stopNameLabels([first, { ...second, name: first.name }], measure)).toEqual({});
  });
  it("차이 부분도 폭을 넘으면 잘못된 대체 이름을 만들지 않는다", () => {
    expect(
      stopNameLabels(
        [
          { ...first, width: 3 },
          { ...second, width: 3 },
        ],
        measure,
      ),
    ).toEqual({});
  });
});
