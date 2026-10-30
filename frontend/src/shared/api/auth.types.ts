export type LoginProvider = "KAKAO" | "APPLE";

export interface Member {
  id: string;
  nickname: string;
  provider: LoginProvider;
}

export type AuthSession =
  | { authenticated: true; refreshable: false; member: Member }
  | { authenticated: false; refreshable: boolean; member?: never };

/** 앱 SDK가 받은 ID 토큰과, 그 토큰을 요청할 때 쓴 서버 nonce. authorizationCode는 Apple만 있다. */
export interface NativeCredential {
  idToken: string;
  nonce: string;
  authorizationCode?: string;
}
