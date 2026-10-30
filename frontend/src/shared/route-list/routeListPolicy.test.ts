import { describe, expect, it } from "@jest/globals";
import { numberOrderedRoutesFor } from "./routeListPolicy";

describe("numberOrderedRoutesFor", () => {
  function orderedNamesOf(displayNames: string[]) {
    return numberOrderedRoutesFor(displayNames.map((displayName) => ({ displayName }))).map(
      (route) => route.displayName,
    );
  }

  it("노선 번호 오름차순으로 놓는다", () => {
    expect(orderedNamesOf(["3330", "1650", "9007", "3000"])).toEqual(["1650", "3000", "3330", "9007"]);
  });

  it("글자 순서가 아니라 수의 크기로 놓는다", () => {
    expect(orderedNamesOf(["1000", "200", "50"])).toEqual(["50", "200", "1000"]);
  });

  it("하이픈 뒤 번호가 붙은 노선은 앞 번호 자리에 놓는다", () => {
    expect(orderedNamesOf(["1650", "1500-2", "1151", "1501"])).toEqual(["1151", "1500-2", "1501", "1650"]);
  });

  it("하이픈 뒤 번호가 붙은 노선은 같은 앞 번호 노선 바로 뒤에 놓는다", () => {
    expect(orderedNamesOf(["7007-1", "7007"])).toEqual(["7007", "7007-1"]);
  });

  it("영문 접두사가 붙은 노선은 접두사를 뺀 번호 자리에 놓는다", () => {
    expect(orderedNamesOf(["9000", "G8110", "8100"])).toEqual(["8100", "G8110", "9000"]);
  });

  it("영문 접두사만 다른 노선은 접두사 없는 노선을 먼저 놓는다", () => {
    expect(orderedNamesOf(["G8110", "8110"])).toEqual(["8110", "G8110"]);
  });

  it("받은 목록은 바꾸지 않는다", () => {
    const routes = [{ displayName: "3330" }, { displayName: "1650" }];

    numberOrderedRoutesFor(routes);

    expect(routes.map((route) => route.displayName)).toEqual(["3330", "1650"]);
  });
});
