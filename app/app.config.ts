import type { ConfigContext, ExpoConfig } from 'expo/config';

export default ({ config }: ConfigContext): ExpoConfig => {
  const key = process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY?.trim();
  const plugins = [...(config.plugins ?? [])];
  if (key) plugins.push(['@react-native-seoul/kakao-login', { kakaoAppKey: key }]);
  return {
    ...config,
    name: config.name ?? '연어버스',
    slug: config.slug ?? 'salmonbus',
    plugins,
  };
};
