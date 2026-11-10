const path = require("path");
const { merge } = require("webpack-merge");
const ReactRefreshWebpackPlugin = require("@pmmmwh/react-refresh-webpack-plugin");
const common = require("./webpack.common.cjs");
const { DefinePlugin } = require("webpack");

// 아래 devServer.proxy가 설정 시점에 process.env를 읽는다. webpack의 dotenv 옵션은 번들에만 값을 넣는다.
try {
  process.loadEnvFile(path.resolve(__dirname, "../env/.env.development"));
} catch (error) {
  if (error?.code !== "ENOENT") {
    throw error;
  }
}

const apiProxyTarget = process.env.API_PROXY_TARGET || "http://localhost:8080";
const localHttpAuth = process.env.LOCAL_HTTP_AUTH === "true" && apiProxyTarget === "http://127.0.0.1:8080";
const authCookieNames = ["sb_session", "sb_at", "sb_rt", "XSRF-TOKEN"];

function usesLocalAuthCookies(request) {
  return request.headers.host === "localhost:3000" && request.socket.remoteAddress === "127.0.0.1";
}

function forwardLocalAuthCookies(proxyRequest, request) {
  if (!usesLocalAuthCookies(request) || !request.headers.cookie) return;
  const cookies = request.headers.cookie.split(";").map((cookie) => cookie.trim());
  for (const name of authCookieNames) {
    const local = cookies.findIndex((cookie) => cookie.startsWith(`sb-local-${name}=`));
    if (local === -1) continue;
    const secure = cookies.findIndex((cookie) => cookie.startsWith(`__Host-${name}=`));
    cookies[local] = cookies[local].replace(/^sb-local-/, "__Host-");
    if (secure !== -1) cookies.splice(secure, 1);
  }
  proxyRequest.setHeader("cookie", cookies.join("; "));
}

function writeLocalAuthCookies(proxyResponse, request) {
  if (!usesLocalAuthCookies(request) || !proxyResponse.headers["set-cookie"]) return;
  proxyResponse.headers["set-cookie"] = proxyResponse.headers["set-cookie"].map((cookie) =>
    authCookieNames.some((name) => cookie.startsWith(`__Host-${name}=`))
      ? cookie.replace(/^__Host-/, "sb-local-").replace(/;\s*Secure(?=;|$)/i, "")
      : cookie,
  );
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
    ...(localHttpAuth && { host: "127.0.0.1" }),
    hot: true,
    historyApiFallback: true,
    proxy: [
      {
        context: ["/api"],
        target: apiProxyTarget,
        changeOrigin: true,
        ws: true,
        ...(localHttpAuth && { onProxyReq: forwardLocalAuthCookies, onProxyRes: writeLocalAuthCookies }),
      },
    ],
  },
  plugins: [
    new ReactRefreshWebpackPlugin(),
    new DefinePlugin({
      __BOARD_MAP_PREVIEW__: JSON.stringify(true),
      "process.env.LOCAL_HTTP_AUTH": JSON.stringify(localHttpAuth ? "true" : "false"),
    }),
  ],
});
