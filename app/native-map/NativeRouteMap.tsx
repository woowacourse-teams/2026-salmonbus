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
  onMapEvent: (event: { nativeEvent: { message: string } }) => void;
}

export const NativeRouteMap =
  Platform.OS === 'ios' && requireOptionalNativeModule('SalmonNativeMap')
    ? requireNativeView<NativeRouteMapProps>('SalmonNativeMap')
    : null;

// This capability is only injected by an iOS build containing the native host.
export const NATIVE_MAP_BOOTSTRAP = `
  Object.defineProperty(window, 'SalmonNativeMap', {
    configurable: true,
    value: Object.freeze({
      version: 1,
      platform: 'ios',
      postMessage: function(message) {
        window.ReactNativeWebView.postMessage(message);
      }
    })
  });
  true;
`;

export function nativeMapEventScript(message: string): string {
  // Embed the JSON as data, including U+2028/U+2029, rather than executable source.
  const encoded = JSON.stringify(message).replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return `window.dispatchEvent(new CustomEvent('salmon:native-map', { detail: JSON.parse(${encoded}) })); true;`;
}

export function isNativeMapMessage(message: string): boolean {
  if (message.length > 4_000_000) return false;
  try {
    const value: unknown = JSON.parse(message);
    if (value === null || typeof value !== 'object') return false;
    const envelope = value as Record<string, unknown>;
    return envelope.v === 1 && typeof envelope.sessionId === 'string' && envelope.sessionId.length <= 128 &&
      Number.isInteger(envelope.revision) &&
      typeof envelope.type === 'string' &&
      ['mount', 'updateScene', 'motion', 'layout', 'camera', 'location', 'unmount'].includes(envelope.type) &&
      envelope.payload !== null && typeof envelope.payload === 'object';
  } catch {
    return false;
  }
}
