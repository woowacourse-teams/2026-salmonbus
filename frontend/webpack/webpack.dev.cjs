const path = require("path");
const { merge } = require("webpack-merge");
const ReactRefreshWebpackPlugin = require("@pmmmwh/react-refresh-webpack-plugin");
const common = require("./webpack.common.cjs");

// 아래 devServer.proxy가 설정 시점에 process.env를 읽는다. webpack의 dotenv 옵션은 번들에만 값을 넣는다.
try {
  process.loadEnvFile(path.resolve(__dirname, "../env/.env.development"));
} catch {
  // 파일이 없으면 기본값으로 간다
}

module.exports = merge(common, {
  mode: "development",
  dotenv: {
    dir: path.resolve(__dirname, "../env"),
  },
  devtool: "eval-source-map",
  output: {
    publicPath: "/",
  },
  module: {
    rules: [
      {
        test: /\.[jt]sx?$/,
        exclude: /node_modules/,
        use: {
          loader: "babel-loader",
          options: {
            envName: "development",
            plugins: ["react-refresh/babel"],
          },
        },
      },
      {
        test: /\.vanilla\.css$/i,
        use: ["style-loader", { loader: "css-loader", options: { url: false } }],
      },
    ],
  },
  devServer: {
    port: 3000,
    hot: true,
    historyApiFallback: true,
    proxy: [
      {
        context: ["/api"],
        target: process.env.API_PROXY_TARGET || "http://localhost:8080",
        changeOrigin: true,
      },
    ],
  },
  plugins: [new ReactRefreshWebpackPlugin()],
});
