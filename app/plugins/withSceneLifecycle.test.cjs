const assert = require('node:assert/strict');
const test = require('node:test');
const { transformAppDelegate } = require('./withSceneLifecycle');

const template = `
internal import Expo
@main
class AppDelegate: ExpoAppDelegate {
  var window: UIWindow?
  var reactNativeFactory: RCTReactNativeFactory?
  func application() {
    let factory = ExpoReactNativeFactory(delegate: delegate)
    reactNativeFactory = factory
#if os(iOS) || os(tvOS)
    window = UIWindow(frame: UIScreen.main.bounds)
    factory.startReactNative(
      withModuleName: "main",
      in: window,
      launchOptions: launchOptions)
#endif
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
  func openURL() { RCTLinkingManager.application(app, open: url, options: options) }
}
`;

test('Expo retains the factory while its Scene Delegate exclusively creates the window and starts RN', () => {
  const result = transformAppDelegate(template, 'swift');
  assert.match(result, /class AppDelegate: ExpoAppDelegate, ExpoReactNativeFactoryProvider/);
  assert.match(result, /reactNativeFactory = factory/);
  assert.match(result, /return super.application/);
  assert.match(result, /RCTLinkingManager.application/);
  assert.doesNotMatch(result, /window = UIWindow|factory.startReactNative/);
});

test('running prebuild repeatedly does not add a second startup or provider conformance', () => {
  const once = transformAppDelegate(template, 'swift');
  assert.equal(transformAppDelegate(once, 'swift'), once);
});

test('a changed Expo template requires review instead of silently leaving two RN roots', () => {
  assert.throws(() => transformAppDelegate(template.replace('factory.startReactNative', 'other.startReactNative'), 'swift'));
  assert.throws(() => transformAppDelegate(template, 'objc'));
});
