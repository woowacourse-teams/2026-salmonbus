import storybook from "eslint-plugin-storybook";

import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import prettierConfig from "eslint-config-prettier";

export default tseslint.config(
  { ignores: ["dist/", "node_modules/", "playwright-report/", "test-results/"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  reactHooks.configs.flat["recommended-latest"],
  {
    files: ["src/**/*.{ts,tsx}"],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    files: ["src/pages/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              group: ["@/pages/**", "@/features/**"],
              message:
                "페이지 전용 코드는 페이지 안에서, 공유 코드는 shared/에서 가져오세요. (같은 페이지 안에서는 상대경로를 사용)",
            },
          ],
        },
      ],
    },
  },
  {
    files: ["src/shared/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              group: ["@/app/**", "@/pages/**", "@/features/**"],
              message: "shared/는 app, pages, features에 의존할 수 없습니다.",
            },
          ],
        },
      ],
    },
  },
  {
    files: ["src/**/*{Favorite,favorite}*.{ts,tsx}", "src/shared/components/favorite-editor/**/*.{ts,tsx}"],
    ignores: ["src/**/*.test.ts", "src/**/*.typeTest.ts"],
    rules: {
      "@typescript-eslint/consistent-type-definitions": ["error", "interface"],
      "@typescript-eslint/method-signature-style": ["error", "property"],
      "@typescript-eslint/consistent-type-assertions": ["error", { assertionStyle: "never" }],
      "@typescript-eslint/no-non-null-assertion": "error",
    },
  },
  {
    files: ["webpack/**/*.cjs"],
    languageOptions: {
      globals: globals.node,
      sourceType: "commonjs",
    },
    rules: {
      "@typescript-eslint/no-require-imports": "off",
    },
  },
  prettierConfig,
  storybook.configs["flat/recommended"],
);
