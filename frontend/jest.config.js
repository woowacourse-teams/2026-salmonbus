import { createRequire } from "node:module";

// Jest가 기본으로 고르는 것은 @babel/core 7용이라서 @babel/core 8용 복사본을 경로로 지정한다.
const require = createRequire(import.meta.url);
const babelJest = require.resolve("babel-jest");

const shared = {
  roots: ["<rootDir>/src"],
  transform: {
    "^.+\\.[jt]sx?$": babelJest,
  },
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/src/$1",
    "\\.(png|jpe?g|gif|svg|webp|woff2?|ttf|otf)$": "<rootDir>/src/testing/assetStub.ts",
  },
  restoreMocks: true,
};

export default {
  projects: [
    {
      ...shared,
      displayName: "unit",
      testEnvironment: "node",
      testMatch: ["<rootDir>/src/**/*.test.ts"],
    },
  ],
};
