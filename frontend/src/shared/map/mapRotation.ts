export function rotationFromNorth(rotation: number): number {
  return (((rotation % 360) + 540) % 360) - 180;
}

export function isMapRotated(rotation: number): boolean {
  return Math.abs(rotationFromNorth(rotation)) > 0.01;
}
