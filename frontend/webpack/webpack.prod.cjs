const path = require("path");
const { merge } = require("webpack-merge");
const MiniCssExtractPlugin = require("mini-css-extract-plugin");
const CssMinimizerPlugin = require("css-minimizer-webpack-plugin");
const common = require("./webpack.common.cjs");

module.exports = merge(common, {
  mode: "production",
  dotenv: {
    dir: "./env",
  },
  devtool: "source-map",
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
  ],
});
