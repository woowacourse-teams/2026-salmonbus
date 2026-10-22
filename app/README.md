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

마커 UI의 기준은 `frontend/src/pages/verdict-board/map/RouteMap.css.ts`와 `naverMapAdapter.ts`입니다. iOS의 `MapMarkerImages.swift`가 같은 치수·색상·글꼴로 이미지를 만들며, `ios/Resources`의 3x PNG는 웹의 원본 SVG에서 변환한 아이콘입니다. 아이콘 원본을 바꾸면 PNG도 함께 갱신합니다.

`verification/MapMarkerImageChecks.swift`는 UIKit에서 정류장 8개 상태, 좌석 배지의 크기·중첩·줌아웃 상태를 검증하고 `/tmp/salmonbus-map-marker-preview.png`를 만듭니다. 부팅된 Apple Silicon iOS Simulator에서 `app` 디렉터리를 기준으로 실행합니다.

```sh
mkdir -p /tmp/SalmonMarkerChecks.app
cp modules/salmon-native-map/ios/Resources/*.png /tmp/SalmonMarkerChecks.app/
xcrun swiftc -parse-as-library -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -target arm64-apple-ios16.4-simulator modules/salmon-native-map/ios/MapMarkerImages.swift verification/MapMarkerImageChecks.swift -o /tmp/SalmonMarkerChecks.app/SalmonMarkerChecks
xcrun simctl spawn booted /tmp/SalmonMarkerChecks.app/SalmonMarkerChecks
```

## iOS 카카오 로그인 준비 (SAL-237)

현재 앱은 WebView의 로그인 화면에서 카카오톡 인증을 시작할 수 있도록 준비되어 있다. 카카오톡이 없으면 SDK의 카카오계정 로그인으로 이어지고, 사용자가 취소하면 다른 인증창을 자동으로 열지 않는다.

### 설정

1. 카카오 개발자 콘솔에서 웹과 같은 카카오 애플리케이션에 iOS 번들 ID `com.salmonbus.app`를 등록한다.
2. `.env.example`을 참고해 로컬 `.env`에 `EXPO_PUBLIC_WEB_URL`과 공개 네이티브 앱 키 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`를 넣는다. 실제 키는 커밋하지 않는다. 관리자 키와 client secret은 앱에 넣지 않는다.
3. 앱 키를 설정한 뒤 `pnpm ios`로 네이티브 앱을 다시 빌드한다. JavaScript 새로고침만으로 URL scheme과 AppDelegate 설정이 바뀌지 않는다. Expo Go에서는 이 네이티브 모듈을 사용할 수 없다.
4. EAS 빌드에서는 해당 빌드 환경에도 같은 공개 네이티브 앱 키를 설정한다. Android 설정과 검증은 후속 범위다.

`app.config.ts`는 키가 있을 때만 Kakao의 Expo plugin을 켠다. plugin은 앱 키, `kakao{키}` URL scheme, 카카오톡 조회 scheme과 복귀 핸들러를 생성한다. 키가 없으면 앱은 실행되며 로그인 시 준비 안내를 표시한다.

### 현재 연결 경계

서버 PR #145는 웹의 OAuth 콜백과 쿠키 인증을 제공한다. 네이티브 SDK 결과를 연어버스 회원 인증으로 교환하는 API는 아직 없다.

따라서 현재는 카카오 인증의 성공·취소·실패 상태만 웹에 알린다. 제공자 인증만으로 연어버스 회원 상태를 만들지 않는다. 인증 결과의 토큰은 WebView·URL·로그로 전달하지 않고 SDK에서 지운다. 웹의 로그인 안내를 실제 로그인 성공으로 바꾸려면 서버와 인증 증명 검증, nonce, WebView 쿠키 발급 절차를 먼저 합의해야 한다.

iOS Apple 로그인은 준비 안내만 표시한다. 웹 Apple 로그인과 재인증 탈퇴는 `frontend/docs/login-integration.md`의 계약으로 개발되어 있다.

### 검증

- `pnpm typecheck`, `pnpm lint`
- `node --test loginBridge.test.cjs`: 서비스 origin, 로그인 경로와 메시지 형식 검사, 취소 분류, 토큰 없는 결과 전달
- `pnpm exec expo config --type introspect`: 키 설정 시 iOS 설정 확인
- 임시 복사본과 테스트용 키로 `expo prebuild --no-install --platform ios`를 실행해 Swift AppDelegate의 복귀 핸들러 생성 확인
- `expo export --platform ios --output-dir <임시 경로>`로 JavaScript 번들 생성 확인

실제 키로 빌드한 iPhone에서 카카오톡 설치/미설치, 인증 취소, 앱 복귀를 추가 확인해야 한다. 네이티브 컴파일·실기기 인증·WebView 로그인 유지는 별도 검증이다.
