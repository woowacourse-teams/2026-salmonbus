import { readFile } from "node:fs/promises";
import { beforeAll, describe, expect, it, jest } from "@jest/globals";

const tilesDirectory = "src/pages/route-select/home/data/adminAreaTiles";

let lookupAreaLabel: typeof import("./homeAreaLabel").lookupAreaLabel;

beforeAll(async () => {
  jest.unstable_mockModule("./homeAreaTiles", () => ({
    loadAreaTile: async (tileName: string) => JSON.parse(await readFile(`${tilesDirectory}/${tileName}.json`, "utf8")),
  }));
  ({ lookupAreaLabel } = await import("./homeAreaLabel"));
});

describe("lookupAreaLabel", () => {
  it("지도 중심이 들어가는 읍면동 이름을 돌려준다", async () => {
    expect(await lookupAreaLabel([127.111136, 37.394761])).toBe("경기도 성남시 분당구 삼평동");
  });

  it("수도권 밖은 시군구까지 돌려준다", async () => {
    expect(await lookupAreaLabel([127.15, 36.78])).toBe("충청남도 천안시 동남구");
  });

  it("조각 경계에 걸친 구역은 경계 양쪽 지점에서 같은 이름을 돌려준다", async () => {
    const west = [126.9995, 37.58033] as const;
    const east = [127.0005, 37.58033] as const;
    expect(Math.floor(west[0] / 0.25)).not.toBe(Math.floor(east[0] / 0.25));
    expect(await lookupAreaLabel(west)).toBe("서울특별시 종로구 이화동");
    expect(await lookupAreaLabel(east)).toBe("서울특별시 종로구 이화동");
  });

  it("구멍 안 지점은 바깥 구역 이름이 아니라 null이다", async () => {
    expect(await lookupAreaLabel([127.8277, 34.95314])).toBe("경상남도 하동군");
    expect(await lookupAreaLabel([127.8377, 34.95314])).toBeNull();
  });

  it("조각은 있지만 어느 구역에도 들어가지 않는 지점은 null이다", async () => {
    expect(await lookupAreaLabel([126.2, 37.45])).toBeNull();
  });

  it("조각이 없는 지점은 null이다", async () => {
    expect(await lookupAreaLabel([125.0, 35.0])).toBeNull();
  });
});
