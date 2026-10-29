/** 앱이 OS 설정 앱에서 연어버스 화면을 연다. 앱 app/settings/settingsMessages.ts가 받는 목록과 같아야 한다. */
export interface SettingsCommands {
  "settings.open": Record<string, never>;
}
