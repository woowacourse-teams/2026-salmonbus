const path = require("path");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const { VanillaExtractPlugin } = require("@vanilla-extract/webpack-plugin");
const { execSync } = require("child_process");

// CodePipeline sources arrive without .git, so buildspec injects APP_VERSION
function resolveAppVersion() {
  if (process.env.APP_VERSION) {
    return process.env.APP_VERSION;
  }
  try {
    return execSync("git rev-parse --short HEAD").toString().trim();
  } catch {
    return "unknown";
  }
}

module.exports = {
  entry: path.resolve(__dirname, "../src/index.tsx"),
  resolve: {
    extensions: [".tsx", ".ts", ".js"],
    alias: {
      "@": path.resolve(__dirname, "../src"),
    },
  },
  module: {
    rules: [
      {
        test: /\.(png|jpe?g|gif|svg|webp)$/i,
        type: "asset",
      },
      {
        test: /\.(woff2?|ttf|otf)$/i,
        type: "asset/resource",
      },
    ],
  },
  plugins: [
    new HtmlWebpackPlugin({
      template: path.resolve(__dirname, "../public/index.html"),
      meta: {
        "app-version": resolveAppVersion(),
      },
    }),
    new VanillaExtractPlugin(),
  ],
};
