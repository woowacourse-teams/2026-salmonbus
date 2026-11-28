import { readFileSync } from "node:fs";
import { describe, expect, test } from "@jest/globals";
import ts from "typescript";
import { pageNameFor } from "@/shared/routing/pagePreload";
import { paths } from "@/shared/routing/paths";
import { lazyPagePreloads } from "./lazyPages";

function isLazyRouteCall(expression: ts.Expression): boolean {
  return (
    ts.isCallExpression(expression) &&
    ts.isIdentifier(expression.expression) &&
    expression.expression.text === "lazyRouteElement"
  );
}

function resolveRoutePath(expression: ts.Expression): string {
  if (ts.isStringLiteral(expression)) return expression.text;
  if (
    ts.isPropertyAccessExpression(expression) &&
    ts.isIdentifier(expression.expression) &&
    expression.expression.text === "paths"
  ) {
    const key = expression.name.text;
    if (key in paths) return paths[key as keyof typeof paths];
  }
  throw new Error(`routes.tsx의 경로 식을 해석하지 못했습니다: ${expression.getText()}`);
}

function lazyRoutePathsInRoutesFile(): string[] {
  const source = readFileSync(new URL("./routes.tsx", import.meta.url), "utf8");
  const file = ts.createSourceFile("routes.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const lazyElementNames = new Set<string>();
  const routePaths: string[] = [];

  function collectAliases(node: ts.Node) {
    if (
      ts.isVariableDeclaration(node) &&
      ts.isIdentifier(node.name) &&
      node.initializer &&
      isLazyRouteCall(node.initializer)
    ) {
      lazyElementNames.add(node.name.text);
    }
    ts.forEachChild(node, collectAliases);
  }

  function collectRoutes(node: ts.Node) {
    if (ts.isObjectLiteralExpression(node)) {
      let pathExpression: ts.Expression | undefined;
      let elementExpression: ts.Expression | undefined;
      for (const property of node.properties) {
        if (!ts.isPropertyAssignment(property) || !ts.isIdentifier(property.name)) continue;
        if (property.name.text === "path") pathExpression = property.initializer;
        if (property.name.text === "element") elementExpression = property.initializer;
      }
      if (
        pathExpression &&
        elementExpression &&
        (isLazyRouteCall(elementExpression) ||
          (ts.isIdentifier(elementExpression) && lazyElementNames.has(elementExpression.text)))
      ) {
        routePaths.push(resolveRoutePath(pathExpression));
      }
    }
    ts.forEachChild(node, collectRoutes);
  }

  collectAliases(file);
  collectRoutes(file);
  return routePaths;
}

describe("lazyPagePreloads", () => {
  test("routes.tsx에서 lazyRouteElement로 그리는 주소마다 pageNameFor가 이름을 돌려준다", () => {
    const lazyPaths = lazyRoutePathsInRoutesFile();
    const registeredPathCount = lazyPagePreloads.reduce((count, entry) => count + entry.paths.length, 0);

    expect(lazyPaths).toHaveLength(registeredPathCount);
    for (const routePath of lazyPaths) {
      const samplePath = routePath.replace(/:[^/]+/g, "sample");
      expect(pageNameFor(lazyPagePreloads, samplePath)).not.toBeNull();
    }
  });
});
