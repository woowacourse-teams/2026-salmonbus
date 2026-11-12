export interface AreaTile {
  scale: number;
  areas: [label: string, polygons: number[][][]][];
}

const tilesByName = new Map<string, Promise<AreaTile>>();

export function loadAreaTile(tileName: string): Promise<AreaTile> {
  const cached = tilesByName.get(tileName);
  if (cached) return cached;
  const tile = fetchAreaTile(tileName);
  tilesByName.set(tileName, tile);
  tile.catch(() => tilesByName.delete(tileName));
  return tile;
}

async function fetchAreaTile(tileName: string): Promise<AreaTile> {
  const response = await fetch(new URL(`./data/adminAreaTiles/${tileName}.json`, import.meta.url));
  if (!response.ok) throw new Error("경계 조각을 받지 못했습니다.");
  return (await response.json()) as AreaTile;
}
