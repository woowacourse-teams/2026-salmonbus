export type LoginProvider = "KAKAO" | "APPLE";

export interface Member {
  id: string;
  nickname: string;
  provider: LoginProvider;
}

export type AuthSession =
  | { authenticated: true; refreshable: false; member: Member }
  | { authenticated: false; refreshable: boolean; member?: never };
