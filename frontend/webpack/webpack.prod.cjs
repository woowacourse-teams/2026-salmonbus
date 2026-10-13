const path = require("path");
const { merge } = require("webpack-merge");
const MiniCssExtractPlugin = require("mini-css-extract-plugin");
const CssMinimizerPlugin = require("css-minimizer-webpack-plugin");
const common = require("./webpack.common.cjs");
const { sentryWebpackPlugin } = require("@sentry/webpack-plugin");

const uploadSourceMaps = process.env.SENTRY_UPLOAD_SOURCEMAPS === "true";
if (uploadSourceMaps) {
  for (const variable of ["SENTRY_AUTH_TOKEN", "APP_VERSION", "WEBPACK_SENTRY_DSN"]) {
    if (!process.env[variable]) throw new Error(variable + " is required for the monitored production deployment");
  }
}

module.exports = merge(common, {
  mode: "production",
  dotenv: {
    dir: path.resolve(__dirname, "../env"),
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
