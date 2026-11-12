const path = require("path");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const { VanillaExtractPlugin } = require("@vanilla-extract/webpack-plugin");
const { execSync } = require("child_process");
const { DefinePlugin } = require("webpack");
const fs = require("node:fs");

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

const appVersion = resolveAppVersion();

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
      {
        test: /adminAreaTiles[\\/][^\\/]+\.json$/,
        type: "asset/resource",
        generator: { filename: "assets/areas/[name].[contenthash:8][ext]" },
      },
      {
        test: /\.md$/i,
        include: path.resolve(__dirname, "../src/pages/privacy/editions"),
        type: "asset/source",
      },
    ],
  },
  plugins: [
    {
      apply(compiler) {
        compiler.hooks.thisCompilation.tap("HomeEvents", (compilation) => {
          const source = path.resolve(__dirname, "../public/home-events.json");
          compilation.fileDependencies.add(source);
          compilation.hooks.processAssets.tap(
            { name: "HomeEvents", stage: compiler.webpack.Compilation.PROCESS_ASSETS_STAGE_ADDITIONAL },
            () => {
              const feed =
                process.env.IGNORE_ENV_FILES !== "true" && fs.existsSync(source)
                  ? fs.readFileSync(source, "utf8")
                  : JSON.stringify({ generatedAt: null, events: [] });
              JSON.parse(feed);
              compilation.emitAsset("assets/home-events.json", new compiler.webpack.sources.RawSource(feed));
            },
          );
        });
      },
    },
    new DefinePlugin({
      __SENTRY_DSN__: JSON.stringify(process.env.WEBPACK_SENTRY_DSN ?? ""),
      __SENTRY_ENVIRONMENT__: JSON.stringify(process.env.WEBPACK_SENTRY_ENVIRONMENT ?? ""),
      __APP_RELEASE__: JSON.stringify("salmonbus-fe@" + appVersion),
      __AMPLITUDE_API_KEY__: DefinePlugin.runtimeValue(
        () => JSON.stringify(process.env.WEBPACK_AMPLITUDE_API_KEY ?? ""),
        true,
      ),
      __NAVER_MAP_KEY_ID__: DefinePlugin.runtimeValue(
        () => JSON.stringify(process.env.WEBPACK_NAVER_MAP_KEY_ID?.trim() ?? ""),
        true,
      ),
    }),
    new HtmlWebpackPlugin({
      template: path.resolve(__dirname, "../public/index.html"),
      favicon: path.resolve(__dirname, "../public/favicon.ico"),
      meta: {
        "app-version": appVersion,
      },
    }),
    new VanillaExtractPlugin(),
  ],
};
