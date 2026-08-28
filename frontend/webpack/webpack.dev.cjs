const path = require("path");
const { merge } = require("webpack-merge");
const ReactRefreshWebpackPlugin = require("@pmmmwh/react-refresh-webpack-plugin");
const common = require("./webpack.common.cjs");

module.exports = merge(common, {
  mode: "development",
  dotenv: {
    dir: path.resolve(__dirname, "../env"),
  },
  devtool: "eval-source-map",
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
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    ],
  },
  plugins: [new ReactRefreshWebpackPlugin()],
});
