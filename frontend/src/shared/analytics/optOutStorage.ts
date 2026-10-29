const STORAGE_KEY = "salmonbus.analyticsOptOut";

type OptOutStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

// 저장소를 막아 둔 브라우저는 localStorage를 꺼내기만 해도 예외를 던져서 꺼내는 일도 try 안에서 한다.
const openLocalStorage = () => window.localStorage;

export function loadAnalyticsOptOut(openStorage: () => OptOutStorage = openLocalStorage) {
  try {
    return openStorage().getItem(STORAGE_KEY) === "true";
  } catch {
    return false;
  }
}

export function storeAnalyticsOptOut(optedOut: boolean, openStorage: () => OptOutStorage = openLocalStorage) {
  try {
    const storage = openStorage();
    if (optedOut) storage.setItem(STORAGE_KEY, "true");
    else storage.removeItem(STORAGE_KEY);
  } catch {
    /* 기억하지 못해도 이번 화면에서는 전송을 멈춘다. */
  }
}

// 다른 탭에서 localStorage.clear()를 부르면 storage 이벤트의 key가 null로 온다.
export function isAnalyticsOptOutChange(event: Pick<StorageEvent, "key">) {
  return event.key === null || event.key === STORAGE_KEY;
}
