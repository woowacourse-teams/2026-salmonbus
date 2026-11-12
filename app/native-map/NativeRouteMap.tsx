import type { ReactNode, Ref } from 'react';
import { requireNativeView, requireOptionalNativeModule } from 'expo';
import { Platform, type StyleProp, type ViewStyle } from 'react-native';

export interface NativeRouteMapRef {
  handleMessage: (message: string) => Promise<void>;
  reset: () => Promise<void>;
}

interface NativeRouteMapProps {
  ref?: Ref<NativeRouteMapRef>;
  children: ReactNode;
  style?: StyleProp<ViewStyle>;
  historySwipeAllowed: boolean;
  onMapEvent: (event: { nativeEvent: { message: string } }) => void;
}

export const NativeRouteMap =
  Platform.OS === 'ios' && requireOptionalNativeModule('SalmonNativeMap')
    ? requireNativeView<NativeRouteMapProps>('SalmonNativeMap')
    : null;
