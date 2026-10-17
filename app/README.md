# 연어버스 앱

웹 UI는 `react-native-webview`에서 실행합니다. iOS development/release build는 로컬 Expo module의 네이버 iOS SDK 지도를 사용하며, Bottom Sheet와 목록은 기존 웹 컴포넌트를 유지합니다. 브라우저, Android와 native module이 없는 Expo Go는 기존 네이버 JavaScript SDK를 사용합니다.

## 로컬 실행

1. `app/.env.example`을 `app/.env`로 복사하고 `EXPO_PUBLIC_WEB_URL`을 지정합니다.
2. 네이버 클라우드에서 **Dynamic Map**을 활성화하고 iOS Bundle ID `com.salmonbus.app`을 등록합니다. 같은 Maps Application에서 웹과 앱을 함께 등록할 수 있습니다.
3. `NAVER_MAP_KEY_ID`에 클라이언트 Key ID를 지정합니다. 서버용 client secret은 넣지 않습니다.
4. `pnpm install` 후 `pnpm ios`를 실행합니다. Xcode와 iOS Simulator runtime, CocoaPods가 필요합니다.

`NAVER_MAP_KEY_ID`는 `app.config.ts`에서 iOS `Info.plist`의 `NMFNcpKeyId`로 들어갑니다. 키나 native 설정을 바꾸면 `pnpm exec expo prebuild --platform ios` 후 다시 빌드합니다. EAS에서도 같은 이름의 환경 변수를 설정합니다. `EXPO_PUBLIC_NAVER_MAP_KEY_ID`도 호환 이름으로 지원합니다.

Simulator에서 로컬 웹 서버에 연결할 때는 `http://127.0.0.1:<port>`를 사용합니다. 실제 iPhone에서는 Mac의 LAN 주소나 HTTPS 개발 서버가 필요합니다. native module은 Expo Go에 포함되지 않으므로 iOS native map 검증에는 development build를 사용합니다.

## 코드 위치

- `native-map/NativeRouteMap.tsx`: iOS capability 주입, 메시지 검증, native view의 TypeScript 계약
- `plugins/withSceneLifecycle.js`: Expo의 `ExpoAppSceneDelegate`를 등록해 iOS 27의 scene lifecycle에서 앱 창과 React Native를 시작
- `modules/salmon-native-map/ios/NativeRouteMapHostView.swift`: `NMFMapView`, WebView ancestor touch gate, 경로와 마커, 위치 권한
- `modules/salmon-native-map/ios/MapContract.swift`: version 1 메시지 DTO
- `../frontend/src/shared/native-map/protocol.ts`: 웹과 앱의 메시지 기준 계약

웹은 API 요청과 선택 state를 유지합니다. native는 관측마다 받은 차량 이동 경로를 `CADisplayLink`로 보간하며, 매 프레임 WebView bridge를 통과하지 않습니다. 시트가 움직이는 동안 새 지도 touch를 막고 최종 영역을 적용한 뒤 다시 허용합니다. 이미 시작한 touch는 UIKit이 처음 정한 view에 남습니다.

`pnpm test`, `pnpm typecheck`, `pnpm lint`로 config plugin과 TypeScript를 검사합니다. Swift와 SDK 연동은 Xcode Simulator build로 검사합니다. DEBUG build의 `[SalmonNativeMap]` 로그는 touch 대상, camera 이동 여부와 zoom, overlay 개수만 기록하며 클라이언트 키나 사용자 좌표를 기록하지 않습니다.
