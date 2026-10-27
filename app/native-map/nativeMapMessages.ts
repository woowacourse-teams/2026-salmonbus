// 웹의 frontend/src/shared/native-map/bridge.ts NATIVE_MAP_CAPABILITIES와 같아야 한다. 웹의 bridge.test.ts가 두 목록을 비교한다.
export const NATIVE_MAP_CAPABILITIES = [
  'map.mount',
  'map.updateScene',
  'map.motion',
  'map.layout',
  'map.camera',
  'map.location',
  'map.unmount',
] as const;
