import { editionsFrom } from "./editionPolicy";

// webpack이 빌드할 때 editions 폴더의 원고를 모두 모은다. Jest는 이 파일 대신 같은 폴더를 fs로 읽어 editionsFrom에 넣는다.
const context = import.meta.webpackContext("./editions", { recursive: false, regExp: /\.md$/ });

export const EDITIONS = editionsFrom(
  context.keys().map((key) => {
    const markdown: unknown = context(key);
    if (typeof markdown !== "string") throw new Error(`${key} 원고를 글자로 읽지 못했습니다.`);
    return { fileName: key.slice("./".length), markdown };
  }),
);
