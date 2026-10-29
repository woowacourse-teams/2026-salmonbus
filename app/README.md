# 연어버스 앱

웹 UI는 `react-native-webview`에서 실행합니다. iOS development/release build는 로컬 Expo module의 네이버 iOS SDK 지도를 사용하며, Bottom Sheet와 목록은 기존 웹 컴포넌트를 유지합니다. 브라우저, Android와 native module이 없는 Expo Go는 기존 네이버 JavaScript SDK를 사용합니다.

## 로컬 실행

1. `app/.env.example`을 `app/.env`로 복사하고 `EXPO_PUBLIC_WEB_URL`을 지정합니다.
2. 네이버 클라우드에서 **Dynamic Map**을 활성화하고 iOS Bundle ID `com.salmonbus.app`을 등록합니다. 같은 Maps Application에서 웹과 앱을 함께 등록할 수 있습니다.
3. `NAVER_MAP_KEY_ID`에 클라이언트 Key ID를 지정합니다. 서버용 client secret은 넣지 않습니다.
4. `pnpm install` 후 `pnpm ios`를 실행합니다. Xcode와 iOS Simulator runtime, CocoaPods가 필요합니다.

`NAVER_MAP_KEY_ID`는 `app.config.ts`에서 iOS `Info.plist`의 `NMFNcpKeyId`로 들어갑니다. 키나 native 설정을 바꾸면 `pnpm exec expo prebuild --platform ios` 후 다시 빌드합니다. EAS에서도 같은 이름의 환경 변수를 설정합니다. `EXPO_PUBLIC_NAVER_MAP_KEY_ID`도 호환 이름으로 지원합니다.

## 로그인

앱 안 로그인은 Apple(`expo-apple-authentication`)과 카카오(`@react-native-kakao/user`) SDK로 합니다. 웹이 `auth.signIn`을 보내면 앱이 SDK로 받은 ID 토큰을 `auth.signInResult`로 돌려주고, 웹이 서버 네이티브 로그인 API(SAL-318)를 불러 WebView에 로그인 쿠키를 받습니다. 서버에 그 API가 없거나 앱이 그 제공자로 로그인할 수 없으면 웹은 WebView 안에서 제공자 로그인 화면을 엽니다. WebView는 웹과 제공자 로그인 화면(`navigation/navigationPolicy.ts`)만 안에서 열고 나머지 주소는 바깥 앱으로 넘깁니다.

- `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`: 웹 로그인과 같은 카카오 앱의 네이티브 앱 키입니다. dev와 운영 카카오 앱이 다르므로 EAS에서는 `preview`, `production` 환경 변수로 따로 넣습니다. 키가 없으면 카카오 URL 스킴을 등록하지 않고, 카카오 로그인은 WebView로 합니다.
- `usesAppleSignIn`이 Sign in with Apple entitlement를 넣습니다. App ID에도 같은 기능이 켜져 있어야 합니다.

키를 바꾸면 `pnpm exec expo prebuild --platform ios` 후 다시 빌드합니다. 시뮬레이터에는 카카오톡과 Apple 계정이 없어서 카카오톡 왕복과 Apple 바텀시트는 실기기에서 확인합니다.

Simulator에서 로컬 웹 서버에 연결할 때는 `http://127.0.0.1:<port>`를 사용합니다. 실제 iPhone에서는 Mac의 LAN 주소나 HTTPS 개발 서버가 필요합니다. native module은 Expo Go에 포함되지 않으므로 iOS native map 검증에는 development build를 사용합니다.

## 코드 위치

- `bridge/bridge.ts`: 앱 정보(`window.__SALMONBUS_APP__`) 주입, 웹과 주고받는 envelope 검증, 웹으로 이벤트 전달
- `auth/`: 로그인 메시지 검사(`authMessages.ts`)와 Apple, 카카오 SDK 호출(`signInNatively.ts`)
- `native-map/NativeRouteMap.tsx`: native view의 TypeScript 계약
- `native-map/nativeMapMessages.ts`: 앱이 받는 지도 메시지 목록(웹의 `NATIVE_MAP_CAPABILITIES`와 같음)
- `haptic/playHaptic.ts`: 진동 종류를 iOS 진동에 연결하는 표. 종류 목록은 웹의 `HapticKind`를 import type으로 읽고, 다르면 `pnpm typecheck`가 실패
- `plugins/withSceneLifecycle.js`: Expo의 `ExpoAppSceneDelegate`를 등록해 iOS 27의 scene lifecycle에서 앱 창과 React Native를 시작
- `modules/salmon-native-map/ios/NativeRouteMapHostView.swift`: `NMFMapView`, WebView ancestor touch gate, 경로와 마커, 위치 권한
- `modules/salmon-native-map/ios/MapContract.swift`: version 1 메시지 DTO
- `../frontend/src/shared/native-map/protocol.ts`: 웹과 앱의 메시지 기준 계약

웹은 API 요청과 선택 state를 유지합니다. native는 관측마다 받은 차량 이동 경로를 `CADisplayLink`로 보간하며, 매 프레임 WebView bridge를 통과하지 않습니다. 시트가 움직이는 동안 새 지도 touch를 막고 최종 영역을 적용한 뒤 다시 허용합니다. 이미 시작한 touch는 UIKit이 처음 정한 view에 남습니다.

`pnpm test`, `pnpm typecheck`, `pnpm lint`로 config plugin, bridge, TypeScript를 검사합니다. Swift와 SDK 연동은 Xcode Simulator build로 검사합니다. DEBUG build의 `[SalmonNativeMap]` 로그는 touch 대상, camera 이동 여부와 zoom, overlay 개수만 기록하며 클라이언트 키나 사용자 좌표를 기록하지 않습니다.

마커 UI의 기준은 `frontend/src/pages/verdict-board/map/RouteMap.css.ts`와 `naverMapAdapter.ts`입니다. iOS의 `MapMarkerImages.swift`가 같은 치수·색상·글꼴로 이미지를 만들며, 정류장은 웹과 같은 원형 링(승차 가능)·점(경유)으로 직접 그립니다. `ios/Resources`의 버스 3x PNG는 웹의 원본 SVG에서 변환한 아이콘이므로 버스 아이콘 원본을 바꾸면 PNG도 함께 갱신합니다.

`verification/MapMarkerImageChecks.swift`는 UIKit에서 정류장 8개 상태, 좌석 배지의 크기·중첩·줌아웃 상태를 검증하고 `/tmp/salmonbus-map-marker-preview.png`를 만듭니다. 부팅된 Apple Silicon iOS Simulator에서 `app` 디렉터리를 기준으로 실행합니다.

```sh
mkdir -p /tmp/SalmonMarkerChecks.app
cp modules/salmon-native-map/ios/Resources/*.png /tmp/SalmonMarkerChecks.app/
xcrun swiftc -parse-as-library -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -target arm64-apple-ios16.4-simulator modules/salmon-native-map/ios/MapMarkerImages.swift verification/MapMarkerImageChecks.swift -o /tmp/SalmonMarkerChecks.app/SalmonMarkerChecks
xcrun simctl spawn booted /tmp/SalmonMarkerChecks.app/SalmonMarkerChecks
```
