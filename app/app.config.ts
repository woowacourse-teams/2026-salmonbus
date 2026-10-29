import type { ConfigContext, ExpoConfig } from 'expo/config';

export default ({ config }: ConfigContext): ExpoConfig => {
  const mapKeyId = process.env.NAVER_MAP_KEY_ID || process.env.EXPO_PUBLIC_NAVER_MAP_KEY_ID;
  // 키가 없으면 카카오 URL 스킴을 등록하지 않는다. 앱은 카카오 로그인을 못 한다고 답하고 웹은 WebView 로그인을 쓴다.
  const kakaoNativeAppKey = process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY;
  return {
    ...config,
    name: config.name ?? '연어버스',
    slug: config.slug ?? 'salmonbus',
    ios: {
      ...config.ios,
      usesAppleSignIn: true,
      infoPlist: {
        ...config.ios?.infoPlist,
        NSLocationWhenInUseUsageDescription: '지도에서 현재 위치를 확인하기 위해 위치 권한을 사용해요.',
        ...(mapKeyId ? { NMFNcpKeyId: mapKeyId } : {}),
      },
    },
    plugins: [
      ...(config.plugins ?? []),
      ...(kakaoNativeAppKey
        ? [['@react-native-kakao/core', { nativeAppKey: kakaoNativeAppKey, ios: { handleKakaoOpenUrl: true } }] as [string, unknown]]
        : []),
    ],
  };
};
