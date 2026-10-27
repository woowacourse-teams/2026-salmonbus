import { describe, expect, it } from "@jest/globals";
import { matchingRoutesFor, numberOrderedRoutesFor, searchDigitsFrom } from "./routeSearchPolicy";

const operatingRoutes = ["3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500"].map((displayName) => ({
  displayName,
}));
const routesWithSymbols = ["1550-1", "M4102"].map((displayName) => ({ displayName }));

function displayNamesFor(routes: readonly { displayName: string }[], input: string) {
  return matchingRoutesFor(routes, input).map((route) => route.displayName);
}

describe("searchDigitsFrom", () => {
  it("번 접미사를 지운다", () => {
    expect(searchDigitsFrom("5600번")).toBe("5600");
  });

  it("공백과 하이픈을 지운다", () => {
    expect(searchDigitsFrom(" 1550-1 ")).toBe("15501");
  });

  it("영문 접두사를 지운다", () => {
    expect(searchDigitsFrom("M4102")).toBe("4102");
  });

  it("숫자가 없으면 빈 문자열이다", () => {
    expect(searchDigitsFrom("번")).toBe("");
  });
});

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

describe("matchingRoutesFor", () => {
  it("앞부분이 일치하는 노선을 받은 순서대로 고른다", () => {
    expect(displayNamesFor(operatingRoutes, "3")).toEqual(["3330", "3000", "3500"]);
  });

  it("번호 중간에 들어 있는 입력은 일치로 보지 않는다", () => {
    expect(displayNamesFor(operatingRoutes, "300")).toEqual(["3000"]);
  });

  it("번이 붙은 입력도 숫자만 비교한다", () => {
    expect(displayNamesFor(operatingRoutes, "56번")).toEqual(["5600"]);
  });

  it("앞부분이 일치하는 노선이 없으면 빈 배열이다", () => {
    expect(displayNamesFor(operatingRoutes, "7")).toEqual([]);
  });

  it("입력이 비면 노선 전체가 아니라 빈 배열이다", () => {
    expect(displayNamesFor(operatingRoutes, "")).toEqual([]);
  });

  it("숫자가 없는 입력도 노선 전체가 아니라 빈 배열이다", () => {
    expect(displayNamesFor(operatingRoutes, "번")).toEqual([]);
  });

  it("노선 번호의 하이픈을 지우고 비교한다", () => {
    expect(displayNamesFor(routesWithSymbols, "15501")).toEqual(["1550-1"]);
  });

  it("노선 번호의 영문 접두사를 지우고 비교한다", () => {
    expect(displayNamesFor(routesWithSymbols, "4102")).toEqual(["M4102"]);
  });
});
