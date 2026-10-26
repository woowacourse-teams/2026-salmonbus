const path = require("path");
const { merge } = require("webpack-merge");
const MiniCssExtractPlugin = require("mini-css-extract-plugin");
const CssMinimizerPlugin = require("css-minimizer-webpack-plugin");
const common = require("./webpack.common.cjs");
const { DefinePlugin } = require("webpack");
const { sentryWebpackPlugin } = require("@sentry/webpack-plugin");

// IGNORE_ENV_FILES=true면 env 파일을 읽지 않아서, 누가 빌드해도 같은 번들이 나온다.
const ignoresEnvFiles = process.env.IGNORE_ENV_FILES === "true";
const envDir = path.resolve(__dirname, "../env");

// webpack.common.cjs의 runtimeValue 값은 빌드 중에 process.env를 읽는다. webpack의 dotenv 옵션은 번들에만 값을 넣는다.
if (!ignoresEnvFiles) {
  try {
    process.loadEnvFile(path.join(envDir, ".env.production"));
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw error;
    }
  }
}

const uploadSourceMaps = process.env.SENTRY_UPLOAD_SOURCEMAPS === "true";
if (uploadSourceMaps) {
  for (const variable of ["SENTRY_AUTH_TOKEN", "APP_VERSION", "WEBPACK_SENTRY_DSN"]) {
    if (!process.env[variable]) throw new Error(variable + " is required for the monitored production deployment");
  }
}

module.exports = merge(common, {
  mode: "production",
  dotenv: {
    dir: ignoresEnvFiles ? false : envDir,
  },
  devtool: "hidden-source-map",
  output: {
    path: path.resolve(__dirname, "../dist"),
    publicPath: "/",
    filename: "assets/js/[name].[contenthash:8].js",
    chunkFilename: "assets/js/[name].[contenthash:8].chunk.js",
    assetModuleFilename: "assets/media/[name].[contenthash:8][ext]",
    clean: true,
  },
  module: {
    rules: [
      {
        test: /\.[jt]sx?$/,
        exclude: /node_modules/,
        use: {
          loader: "babel-loader",
          options: {
            envName: "production",
          },
        },
      },
      {
        test: /\.vanilla\.css$/i,
        use: [MiniCssExtractPlugin.loader, { loader: "css-loader", options: { url: false } }],
      },
    ],
  },
  optimization: {
    minimizer: ["...", new CssMinimizerPlugin()],
    splitChunks: { chunks: "all" },
    runtimeChunk: "single",
  },
  plugins: [
    new DefinePlugin({ __BOARD_MAP_PREVIEW__: JSON.stringify(false) }),
    new MiniCssExtractPlugin({
      filename: "assets/css/[name].[contenthash:8].css",
    }),
    ...(uploadSourceMaps
      ? [
          sentryWebpackPlugin({
            org: process.env.SENTRY_ORG ?? "salmonbus",
            project: process.env.SENTRY_PROJECT ?? "salmonbus-fe",
            authToken: process.env.SENTRY_AUTH_TOKEN,
            telemetry: false,
            errorHandler: (error) => {
              console.warn(
                "[Sentry] 릴리스 생성 또는 소스맵 업로드에 실패했습니다. " +
                  "빌드는 계속 진행하지만, 해당 배포의 오류 스택을 원본 코드로 복원하지 못할 수 있습니다.",
                error.message,
              );
            },
            release: { name: "salmonbus-fe@" + process.env.APP_VERSION, inject: false },
            sourcemaps: {
              assets: "./dist/assets/**",
              filesToDeleteAfterUpload: ["./dist/**/*.map"],
            },
          }),
        ]
      : []),
  ],
});
