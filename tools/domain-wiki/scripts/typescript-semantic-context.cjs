const fs = require("fs");
const path = require("path");

const [rootArgument, typescriptModule, headSha, pathsFile] = process.argv.slice(2);
if (!rootArgument || !typescriptModule || !headSha || !pathsFile) {
  throw new Error(
    "usage: node typescript-semantic-context.cjs ROOT TYPESCRIPT_MODULE HEAD_SHA PATHS_FILE",
  );
}

const projectRoot = path.resolve(rootArgument);
const ts = require(path.resolve(typescriptModule));
const changedPaths = fs
  .readFileSync(pathsFile)
  .toString("utf8")
  .split("\0")
  .filter(Boolean)
  .filter((value) => /^frontend\/.*\.tsx?$/.test(value));
const configPath = path.join(projectRoot, "frontend", "tsconfig.json");

const config = ts.readConfigFile(configPath, ts.sys.readFile);
if (config.error) {
  throw new Error(ts.flattenDiagnosticMessageText(config.error.messageText, "\n"));
}
const parsed = ts.parseJsonConfigFileContent(
  config.config,
  ts.sys,
  path.dirname(configPath),
);
const versions = new Map(parsed.fileNames.map((file) => [path.resolve(file), "0"]));
const host = {
  getCompilationSettings: () => parsed.options,
  getScriptFileNames: () => [...versions.keys()],
  getScriptVersion: (file) => versions.get(path.resolve(file)) || "0",
  getScriptSnapshot: (file) => {
    if (!fs.existsSync(file)) return undefined;
    return ts.ScriptSnapshot.fromString(fs.readFileSync(file, "utf8"));
  },
  getCurrentDirectory: () => projectRoot,
  getDefaultLibFileName: (options) => ts.getDefaultLibFilePath(options),
  fileExists: ts.sys.fileExists,
  readFile: ts.sys.readFile,
  readDirectory: ts.sys.readDirectory,
};
const service = ts.createLanguageService(host, ts.createDocumentRegistry());
const program = service.getProgram();
if (!program) throw new Error("TypeScript language service did not create a program");

function repositoryPath(file) {
  return path.relative(projectRoot, path.resolve(file)).split(path.sep).join("/");
}

function isRepositorySource(file) {
  const relative = repositoryPath(file);
  return (
    relative !== "" &&
    !relative.startsWith("../") &&
    !path.isAbsolute(relative) &&
    !relative.split("/").includes("node_modules")
  );
}

function lineOf(file, position) {
  const source = program.getSourceFile(file);
  if (!source) return null;
  return source.getLineAndCharacterOfPosition(position).line + 1;
}

function snippetOf(file, position) {
  const source = program.getSourceFile(file);
  if (!source) return "";
  const line = source.getLineAndCharacterOfPosition(position).line;
  const lines = source.text.split(/\r?\n/).slice(line, line + 12);
  while (lines.length && lines.join("\n").length > 8000) lines.pop();
  return lines.join("\n");
}

function resolvedDefinitions(file, position, depth = 0) {
  const definitions = service.getDefinitionAtPosition(file, position) || [];
  if (depth >= 3) return definitions;
  const resolved = [];
  for (const definition of definitions) {
    if (definition.kind === "alias") {
      const nested = resolvedDefinitions(
        definition.fileName,
        definition.textSpan.start,
        depth + 1,
      );
      if (nested.length) {
        resolved.push(...nested);
      }
      continue;
    }
    resolved.push(definition);
  }
  return resolved;
}

function analyze(relativeTarget) {
  const target = path.resolve(projectRoot, relativeTarget);
  const source = program.getSourceFile(target);
  if (!source || !isRepositorySource(target)) return null;
  const symbols = [];
  const callTargets = [];
  const callsSeen = new Set();

  function visit(node) {
    const named =
      (ts.isFunctionDeclaration(node) ||
        ts.isClassDeclaration(node) ||
        ts.isInterfaceDeclaration(node) ||
        ts.isTypeAliasDeclaration(node) ||
        ts.isVariableDeclaration(node)) &&
      node.name &&
      ts.isIdentifier(node.name);
    if (named && symbols.length < 500) {
      const position = node.name.getStart(source);
      const groups = service.findReferences(target, position) || [];
      const references = [];
      for (const group of groups) {
        for (const reference of group.references) {
          if (references.length >= 100 || !isRepositorySource(reference.fileName)) continue;
          const line = lineOf(reference.fileName, reference.textSpan.start);
          if (line !== null) {
            references.push({ path: repositoryPath(reference.fileName), line });
          }
        }
      }
      symbols.push({
        name: node.name.text,
        kind: ts.SyntaxKind[node.kind],
        line: source.getLineAndCharacterOfPosition(position).line + 1,
        references,
      });
    }

    if (ts.isCallExpression(node) && callTargets.length < 100) {
      const expression = node.expression;
      const callee = ts.isPropertyAccessExpression(expression)
        ? expression.name
        : ts.isElementAccessExpression(expression) && expression.argumentExpression
          ? expression.argumentExpression
          : expression;
      const call = callee.getText(source).slice(0, 200);
      const callPosition = callee.getStart(source);
      const callLine = source.getLineAndCharacterOfPosition(callPosition).line + 1;
      if (call.includes("\n")) {
        ts.forEachChild(node, visit);
        return;
      }
      const definitions = resolvedDefinitions(target, callPosition);
      for (const definition of definitions) {
        if (!isRepositorySource(definition.fileName)) continue;
        const definitionLine = lineOf(definition.fileName, definition.textSpan.start);
        if (definitionLine === null) continue;
        const definitionSnippet = snippetOf(
          definition.fileName,
          definition.textSpan.start,
        );
        if (!definitionSnippet) continue;
        const item = {
          call,
          callLine,
          definitionPath: repositoryPath(definition.fileName),
          definitionLine,
          definitionSnippet,
        };
        const key = JSON.stringify(item);
        if (!callsSeen.has(key)) {
          callsSeen.add(key);
          callTargets.push(item);
        }
        if (callTargets.length >= 100) break;
      }
    }
    ts.forEachChild(node, visit);
  }

  visit(source);
  return { path: relativeTarget, symbols, callTargets };
}

const files = changedPaths.map(analyze).filter(Boolean).slice(0, 100);
const payload = {
    schemaVersion: 1,
    provider: "typescript-language-service",
    headSha,
    files,
};
let serialized = JSON.stringify(payload);
while (Buffer.byteLength(serialized, "utf8") > 70000) {
  const callFile = files.reduce(
    (largest, file) =>
      file.callTargets.length > (largest?.callTargets.length || 0) ? file : largest,
    null,
  );
  if (callFile?.callTargets.length) {
    callFile.callTargets.pop();
  } else {
    const referenceSymbol = files
      .flatMap((file) => file.symbols)
      .find((symbol) => symbol.references.length);
    if (referenceSymbol) {
      referenceSymbol.references.pop();
    } else {
      const symbolFile = files.find((file) => file.symbols.length);
      if (symbolFile) symbolFile.symbols.pop();
      else if (files.length) files.pop();
      else throw new Error("semantic context metadata exceeds the size limit");
    }
  }
  serialized = JSON.stringify(payload);
}
process.stdout.write(serialized);
