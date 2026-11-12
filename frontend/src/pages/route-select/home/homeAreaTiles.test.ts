import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { loadAreaTile } from "./homeAreaTiles";

const tile = { scale: 100000, areas: [] };

describe("loadAreaTile", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("같은 조각은 한 번만 받는다", async () => {
    const fetchMock = jest.spyOn(globalThis, "fetch").mockImplementation(async () => Response.json(tile));

    await loadAreaTile("500_100");
    await loadAreaTile("500_100");

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("받지 못한 조각은 다음 호출에서 다시 받는다", async () => {
    const fetchMock = jest
      .spyOn(globalThis, "fetch")
      .mockImplementationOnce(async () => new Response(null, { status: 503 }))
      .mockImplementationOnce(async () => Response.json(tile));

    await expect(loadAreaTile("500_101")).rejects.toThrow();
    await expect(loadAreaTile("500_101")).resolves.toEqual(tile);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
