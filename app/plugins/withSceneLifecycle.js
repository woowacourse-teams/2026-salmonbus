const { createRunOncePlugin, withAppDelegate, withInfoPlist } = require('expo/config-plugins');

const SCENE_START_COMMENT = '// ExpoAppSceneDelegate creates the scene window and starts React Native.';
const LEGACY_WINDOW_START = /#if os\(iOS\) \|\| os\(tvOS\)\s+window = UIWindow\(frame: UIScreen\.main\.bounds\)\s+factory\.startReactNative\(\s+withModuleName: "main",\s+in: window,\s+launchOptions: launchOptions\)\s*#endif/;

function withSceneLifecycle(config) {
  config = withInfoPlist(config, (mod) => {
    mod.modResults.UIApplicationSceneManifest = {
      UIApplicationSupportsMultipleScenes: false,
      UISceneConfigurations: {
        UIWindowSceneSessionRoleApplication: [{
          UISceneClassName: 'UIWindowScene',
          UISceneConfigurationName: 'Default Configuration',
          UISceneDelegateClassName: 'EXExpoAppSceneDelegate',
        }],
      },
    };
    return mod;
  });
  return withAppDelegate(config, (mod) => {
    mod.modResults.contents = transformAppDelegate(mod.modResults.contents, mod.modResults.language);
    return mod;
  });
}

function transformAppDelegate(contents, language) {
  if (language !== 'swift') {
    throw new Error('The scene lifecycle plugin requires the Expo Swift AppDelegate.');
  }
  let source = contents;
  if (!source.includes('ExpoReactNativeFactoryProvider')) {
    const declaration = 'class AppDelegate: ExpoAppDelegate {';
    if (!source.includes(declaration)) {
      throw new Error('AppDelegate template changed: review the Expo scene lifecycle integration.');
    }
    source = source.replace(declaration, 'class AppDelegate: ExpoAppDelegate, ExpoReactNativeFactoryProvider {');
  }
  if (!source.includes(SCENE_START_COMMENT)) {
    if (!LEGACY_WINDOW_START.test(source)) {
      throw new Error('Legacy React Native window startup was not found; review the scene lifecycle integration.');
    }
    source = source.replace(LEGACY_WINDOW_START, `    ${SCENE_START_COMMENT}`);
  }
  return source;
}

module.exports = createRunOncePlugin(withSceneLifecycle, 'salmon-scene-lifecycle', '1.0.0');
module.exports.transformAppDelegate = transformAppDelegate;
