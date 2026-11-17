import { describe, expect, test } from "@jest/globals";
import { paths } from "./paths";
import { internalPathnameOf, pageNameFor, type PagePreloadEntry } from "./pagePreload";

const noop = () => undefined;

const entries: PagePreloadEntry[] = [
  { name: "board", paths: [paths.board, paths.routeSearch], preload: noop },
  { name: "favorites", paths: [paths.favorites, paths.favoriteGroups], preload: noop },
  { name: "favorite-group", paths: [paths.favoriteGroup], preload: noop },
  { name: "mypage", paths: [paths.mypage], preload: noop },
  { name: "nickname", paths: [paths.nickname], preload: noop },
  { name: "privacy", paths: [paths.privacy, paths.privacyEdition], preload: noop },
];

describe("pageNameFor", () => {
  test.each([
    ["/routes/234000884", "board"],
    ["/search", "board"],
    ["/favorites", "favorites"],
    ["/favorites/groups", "favorites"],
    ["/favorites/groups/g1", "favorite-group"],
    ["/mypage", "mypage"],
    ["/mypage/nickname", "nickname"],
    ["/privacy/2026-01-01", "privacy"],
  ])("%s는 %s 페이지로 매칭한다", (pathname, name) => {
    expect(pageNameFor(entries, pathname)).toBe(name);
  });

  test.each(["/", "/route-map", "/mypage/whats-new", "/unknown"])("등록되지 않은 경로 %s는 null이다", (pathname) => {
    expect(pageNameFor(entries, pathname)).toBeNull();
  });
});

describe("internalPathnameOf", () => {
  const origin = "https://www.salmonbus.com";

  test("같은 origin의 절대·상대 링크는 pathname만 돌려준다", () => {
    expect(internalPathnameOf("https://www.salmonbus.com/favorites?tab=1", origin)).toBe("/favorites");
    expect(internalPathnameOf("/mypage#top", origin)).toBe("/mypage");
  });

  test("다른 origin 링크는 무시한다", () => {
    expect(internalPathnameOf("https://example.com/favorites", origin)).toBeNull();
    expect(internalPathnameOf("mailto:help@salmonbus.com", origin)).toBeNull();
  });

  test("해석할 수 없는 href는 무시한다", () => {
    expect(internalPathnameOf("http://[", origin)).toBeNull();
  });
});
