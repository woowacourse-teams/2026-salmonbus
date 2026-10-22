# iOS 카카오 로그인 준비 (SAL-237)

현재 앱은 WebView의 로그인 화면에서 카카오톡 인증을 시작할 수 있도록 준비되어 있다. 카카오톡이 없으면 SDK의 카카오계정 로그인으로 이어지고, 사용자가 취소하면 다른 인증창을 자동으로 열지 않는다.

## 설정

1. 카카오 개발자 콘솔에서 웹과 같은 카카오 애플리케이션에 iOS 번들 ID `com.salmonbus.app`를 등록한다.
2. `.env.example`을 참고해 로컬 `.env`에 `EXPO_PUBLIC_WEB_URL`과 공개 네이티브 앱 키 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`를 넣는다. 실제 키는 커밋하지 않는다. 관리자 키와 client secret은 앱에 넣지 않는다.
3. 앱 키를 설정한 뒤 `pnpm ios`로 네이티브 앱을 다시 빌드한다. JavaScript 새로고침만으로 URL scheme과 AppDelegate 설정이 바뀌지 않는다. Expo Go에서는 이 네이티브 모듈을 사용할 수 없다.
4. EAS 빌드에서는 해당 빌드 환경에도 같은 공개 네이티브 앱 키를 설정한다. Android 설정과 검증은 후속 범위다.

`app.config.ts`는 키가 있을 때만 Kakao의 Expo plugin을 켠다. plugin은 앱 키, `kakao{키}` URL scheme, 카카오톡 조회 scheme과 복귀 핸들러를 생성한다. 키가 없으면 앱은 실행되며 로그인 시 준비 안내를 표시한다.

## 현재 연결 경계

서버 PR #145는 웹의 OAuth 콜백과 쿠키 인증을 제공한다. 네이티브 SDK 결과를 연어버스 회원 인증으로 교환하는 API는 아직 없다.

따라서 현재는 카카오 인증의 성공·취소·실패 상태만 웹에 알린다. 제공자 인증만으로 연어버스 회원 상태를 만들지 않는다. 인증 결과의 토큰은 WebView·URL·로그로 전달하지 않고 SDK에서 지운다. 웹의 로그인 안내를 실제 로그인 성공으로 바꾸려면 서버와 인증 증명 검증, nonce, WebView 쿠키 발급 절차를 먼저 합의해야 한다.

iOS Apple 로그인은 준비 안내만 표시한다. 웹 Apple 로그인과 재인증 탈퇴는 `frontend/docs/login-integration.md`의 계약으로 개발되어 있다.

## 검증

- `pnpm typecheck`, `pnpm lint`
- `node --test loginBridge.test.cjs`: 서비스 origin, 로그인 경로와 메시지 형식 검사, 취소 분류, 토큰 없는 결과 전달
- `pnpm exec expo config --type introspect`: 키 설정 시 iOS 설정 확인
- 임시 복사본과 테스트용 키로 `expo prebuild --no-install --platform ios`를 실행해 Swift AppDelegate의 복귀 핸들러 생성 확인
- `expo export --platform ios --output-dir <임시 경로>`로 JavaScript 번들 생성 확인

실제 키로 빌드한 iPhone에서 카카오톡 설치/미설치, 인증 취소, 앱 복귀를 추가 확인해야 한다. 네이티브 컴파일·실기기 인증·WebView 로그인 유지는 별도 검증이다.
