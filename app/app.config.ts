import type { ConfigContext, ExpoConfig } from 'expo/config';

export default ({ config }: ConfigContext): ExpoConfig => {
  const mapKeyId = process.env.NAVER_MAP_KEY_ID || process.env.EXPO_PUBLIC_NAVER_MAP_KEY_ID;
  return {
    ...config,
    name: config.name ?? '연어버스',
    slug: config.slug ?? 'salmonbus',
    ios: {
      ...config.ios,
      infoPlist: {
        ...config.ios?.infoPlist,
        NSLocationWhenInUseUsageDescription: '지도에서 현재 위치를 확인하기 위해 위치 권한을 사용해요.',
        ...(mapKeyId ? { NMFNcpKeyId: mapKeyId } : {}),
      },
    },
  };
};
