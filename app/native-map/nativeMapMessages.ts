// 웹의 frontend/src/shared/native-map/bridge.ts NATIVE_MAP_CAPABILITIES와 같아야 한다. 한쪽을 바꾸면 양쪽 테스트의 목록도 같이 바꾼다.
export const NATIVE_MAP_CAPABILITIES = [
  'map.mount',
  'map.updateScene',
  'map.motion',
  'map.layout',
  'map.camera',
  'map.location',
  'map.unmount',
] as const;
