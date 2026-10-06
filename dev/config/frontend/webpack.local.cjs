const { merge } = require("webpack-merge");
const webpack = require("webpack");
const ReactRefreshWebpackPlugin = require("@pmmmwh/react-refresh-webpack-plugin");
const common = require("./webpack.common.cjs");

module.exports = merge(common, {
  mode: "development",
  devtool: "eval-source-map",
  output: { publicPath: "/" },
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
    host: "0.0.0.0",
    port: 3000,
    hot: true,
    historyApiFallback: true,
    proxy: [{ context: ["/api"], target: "http://api:8080", changeOrigin: true, ws: true }],
  },
  plugins: [
    new ReactRefreshWebpackPlugin(),
    new webpack.DefinePlugin({ "process.env.WEBPACK_AMPLITUDE_API_KEY": JSON.stringify("") }),
  ],
});
