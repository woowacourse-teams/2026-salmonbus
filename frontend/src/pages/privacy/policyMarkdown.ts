export type PolicyInline =
  | { kind: "text"; text: string }
  | { kind: "strong"; text: string }
  | { kind: "code"; text: string }
  | { kind: "sectionLink"; text: string; targetId: string };

export type PolicyCell = readonly PolicyInline[];

export interface PolicyListItem {
  content: readonly PolicyInline[];
  subitems: readonly (readonly PolicyInline[])[];
}

export type PolicyBlock =
  | { kind: "heading"; level: 2 | 3 | 4; id: string; content: readonly PolicyInline[] }
  | { kind: "paragraph"; content: readonly PolicyInline[] }
  | { kind: "bulletList"; items: readonly PolicyListItem[] }
  | { kind: "numberedList"; start: number; items: readonly PolicyListItem[] }
  | { kind: "table"; header: readonly PolicyCell[]; rows: readonly (readonly PolicyCell[])[] }
  | { kind: "quote"; blocks: readonly PolicyBlock[] };

export interface PolicyDocument {
  title: string;
  blocks: readonly PolicyBlock[];
}

interface SourceLine {
  number: number;
  text: string;
}

interface ParseContext {
  headingIds: Map<string, number>;
  sectionLinks: { targetId: string; lineNumber: number }[];
}

type LineKind = "heading" | "table" | "quote" | "bulletItem" | "numberedItem" | "text";

const REJECTED_LINE_STARTS: readonly { pattern: RegExp; message: string }[] = [
  { pattern: /^#{5,}(\s|$)/, message: "제목은 ####까지만 쓸 수 있습니다." },
  { pattern: /^#+$/, message: "빈 제목은 쓸 수 없습니다." },
  { pattern: /^# /, message: "# 제목은 첫 줄에 한 번만 쓸 수 있습니다. 절 제목은 ##로 써 주세요." },
  { pattern: /^#+[^#\s]/, message: "제목은 # 뒤에 한 칸을 띄워 주세요." },
  { pattern: /^(```|~~~)/, message: "코드 블록(```)은 쓸 수 없습니다." },
  { pattern: /^([-*_])(?: *\1){2,}$/, message: "가로줄(---)은 쓸 수 없습니다." },
  { pattern: /^=+$/, message: "제목 밑줄(===)은 쓸 수 없습니다. ## 제목으로 써 주세요." },
  { pattern: /^[*+]( |$)/, message: "목록은 '- '로 시작해 주세요." },
  { pattern: /^\d+\)( |$)/, message: "번호 목록은 '1. '처럼 점을 찍어 주세요." },
  { pattern: /^(-|\d+\.)$/, message: "빈 목록 항목은 쓸 수 없습니다." },
  { pattern: /^\s/, message: "들여쓰기는 '- ' 목록 아래의 '  - '만 쓸 수 있습니다." },
  { pattern: /^\[[^\]]+\]:/, message: "참조 링크와 각주([이름]: ...)는 쓸 수 없습니다." },
  { pattern: /^>(?! |$)/, message: "인용은 '> '로 시작해 주세요." },
];

const REJECTED_INLINES: readonly { pattern: RegExp; message: string }[] = [
  { pattern: /^\*/, message: "기울임(*글자*)은 쓸 수 없습니다. 굵게(**글자**)로 쓰거나 백틱(`)으로 감싸 주세요." },
  { pattern: /^_/, message: "밑줄 기호(_)는 기울임이 될 수 있어서 쓸 수 없습니다. 백틱(`)으로 감싸 주세요." },
  { pattern: /^~/, message: "물결표(~)는 취소선이 될 수 있어서 쓸 수 없습니다. '15초에서 20초'처럼 말로 써 주세요." },
  { pattern: /^!\[/, message: "그림(![설명](주소))은 쓸 수 없습니다." },
  { pattern: /^\\/, message: "역슬래시(\\)는 쓸 수 없습니다." },
  { pattern: /^<[A-Za-z/!?]/, message: "HTML 태그와 <주소>는 쓸 수 없습니다. 주소는 꺾쇠 없이 써 주세요." },
  {
    pattern: /^&#?[A-Za-z0-9]+;/,
    message: "HTML 문자 참조(&amp; 같은 표기)는 쓸 수 없습니다. 글자를 그대로 써 주세요.",
  },
];

const INLINE_MARKERS = /[`*_~!\\<&[]/;
const PUNCTUATION = /[\p{P}\p{S}]/u;
const LIST_CONTINUATION_MESSAGE = "목록 바로 아래 줄은 빈 줄이어야 합니다. 항목의 글은 한 줄에 이어서 써 주세요.";

// 법적 문서라서 GitHub와 다르게 보일 수 있는 문법은 글자로 넘기지 않고 "<n>번째 줄: <규칙>" 오류로 막는다.
export function policyDocumentFrom(markdown: string): PolicyDocument {
  const lines = markdown
    .replace(/^\uFEFF/, "")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((text, index) => ({ number: index + 1, text: text.trimEnd() }));
  const [titleLine, ...bodyLines] = lines;
  const titleSource = /^# (.+)$/.exec(titleLine?.text ?? "")?.[1];
  if (titleSource === undefined) throw createLineError(1, "첫 줄은 '# 제목'이어야 합니다.");

  const context: ParseContext = { headingIds: new Map(), sectionLinks: [] };
  const title = plainTextFrom(titleSource, 1, context);
  const blocks = blocksFrom(bodyLines, context);
  for (const link of context.sectionLinks) {
    if (!context.headingIds.has(link.targetId))
      throw createLineError(link.lineNumber, `목차 링크 #${link.targetId}에 맞는 제목이 없습니다.`);
  }
  return { title, blocks };
}

export function plainTextOf(content: readonly PolicyInline[]): string {
  return content.map((inline) => inline.text).join("");
}

function createLineError(lineNumber: number, message: string): Error {
  return new Error(`${lineNumber}번째 줄: ${message}`);
}

function blocksFrom(lines: readonly SourceLine[], context: ParseContext): PolicyBlock[] {
  return runsOf(lines).flatMap((run) => blocksFromRun(run, context));
}

// 제목 줄만 빼면 모든 블록은 빈 줄로 끝나야 한다. 그래서 빈 줄로 나눈 덩어리 하나가 제목 몇 줄과 블록 하나다.
function runsOf(lines: readonly SourceLine[]): SourceLine[][] {
  const runs: SourceLine[][] = [];
  let run: SourceLine[] = [];
  for (const line of lines) {
    if (line.text !== "") {
      run.push(line);
      continue;
    }
    if (run.length > 0) runs.push(run);
    run = [];
  }
  if (run.length > 0) runs.push(run);
  return runs;
}

function blocksFromRun(run: readonly SourceLine[], context: ParseContext): PolicyBlock[] {
  const [first, ...rest] = run;
  if (first === undefined) return [];
  const rejection = rejectionOf(first.text);
  if (rejection !== null) throw createLineError(first.number, rejection);
  switch (lineKindOf(first.text)) {
    case "heading":
      return [headingFrom(first, context), ...blocksFromRun(rest, context)];
    case "table":
      return [tableFrom(first, rest, context)];
    case "quote":
      return [quoteFrom(run, context)];
    case "bulletItem":
      return [bulletListFrom(run, context)];
    case "numberedItem":
      return [numberedListFrom(run, context)];
    case "text":
      return [paragraphFrom(run, context)];
  }
}

function rejectionOf(text: string): string | null {
  return REJECTED_LINE_STARTS.find(({ pattern }) => pattern.test(text))?.message ?? null;
}

function lineKindOf(text: string): LineKind {
  if (/^#{2,4} /.test(text)) return "heading";
  if (text.startsWith("|")) return "table";
  if (/^>( |$)/.test(text)) return "quote";
  if (text.startsWith("- ")) return "bulletItem";
  if (/^\d+\. /.test(text)) return "numberedItem";
  return "text";
}

function headingFrom(line: SourceLine, context: ParseContext): PolicyBlock {
  const [, hashes = "", source = ""] = /^(#{2,4}) (.+)$/.exec(line.text) ?? [];
  const level = hashes.length;
  if (level !== 2 && level !== 3 && level !== 4)
    throw createLineError(line.number, "제목은 ##에서 ####까지 쓸 수 있습니다.");
  if (/(^|\s)#+$/.test(source)) throw createLineError(line.number, "제목 끝에는 #을 쓸 수 없습니다.");
  const content = inlinesFrom(source, line.number, context);
  return { kind: "heading", level, id: headingIdFrom(plainTextOf(content), context.headingIds), content };
}

// github-slugger와 같은 규칙이라 GitHub 미리보기에서 만든 목차 링크가 그대로 맞는다.
function headingIdFrom(text: string, headingIds: Map<string, number>): string {
  const base = text
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}\p{Pc}\- ]/gu, "")
    .replace(/ /g, "-");
  let id = base;
  while (headingIds.has(id)) {
    const count = (headingIds.get(base) ?? 0) + 1;
    headingIds.set(base, count);
    id = `${base}-${count}`;
  }
  headingIds.set(id, 0);
  return id;
}

function paragraphFrom(run: readonly SourceLine[], context: ParseContext): PolicyBlock {
  for (const line of run.slice(1)) {
    const rejection = rejectionOf(line.text);
    if (rejection !== null) throw createLineError(line.number, rejection);
    if (lineKindOf(line.text) !== "text")
      throw createLineError(line.number, "문단 바로 아래에 다른 블록이 붙어 있습니다. 그 사이에 빈 줄을 넣어 주세요.");
  }
  return { kind: "paragraph", content: inlinesFromLines(run, context) };
}

function inlinesFromLines(lines: readonly SourceLine[], context: ParseContext): PolicyInline[] {
  const inlines = lines.flatMap((line, index) => [
    ...(index === 0 ? [] : [{ kind: "text" as const, text: "\n" }]),
    ...inlinesFrom(line.text, line.number, context),
  ]);
  return inlines.reduce<PolicyInline[]>((merged, inline) => {
    const previous = merged.at(-1);
    if (previous?.kind === "text" && inline.kind === "text")
      return [...merged.slice(0, -1), { kind: "text", text: previous.text + inline.text }];
    return [...merged, inline];
  }, []);
}

function bulletListFrom(run: readonly SourceLine[], context: ParseContext): PolicyBlock {
  const items: { content: readonly PolicyInline[]; subitems: (readonly PolicyInline[])[] }[] = [];
  for (const line of run) {
    const subitem = /^ {2}- (.+)$/.exec(line.text)?.[1];
    const current = items.at(-1);
    if (subitem !== undefined && current !== undefined) {
      current.subitems.push(listItemContentFrom(subitem, line.number, context));
      continue;
    }
    if (/^\s+([-*+]|\d+[.)])( |$)/.test(line.text))
      throw createLineError(line.number, "목록은 한 단계(두 칸 들여쓴 '  - ')까지만 쓸 수 있습니다.");
    const rejection = rejectionOf(line.text);
    if (rejection !== null) throw createLineError(line.number, rejection);
    const item = /^- (.+)$/.exec(line.text)?.[1];
    if (item === undefined) throw createLineError(line.number, LIST_CONTINUATION_MESSAGE);
    items.push({ content: listItemContentFrom(item, line.number, context), subitems: [] });
  }
  return { kind: "bulletList", items };
}

function numberedListFrom(run: readonly SourceLine[], context: ParseContext): PolicyBlock {
  const items: PolicyListItem[] = [];
  let start = 0;
  for (const line of run) {
    if (/^\s/.test(line.text)) throw createLineError(line.number, "번호 목록 안에는 하위 목록을 쓸 수 없습니다.");
    const rejection = rejectionOf(line.text);
    if (rejection !== null) throw createLineError(line.number, rejection);
    const [, marker, source] = /^(\d+)\. (.+)$/.exec(line.text) ?? [];
    if (marker === undefined || source === undefined) throw createLineError(line.number, LIST_CONTINUATION_MESSAGE);
    const number = Number(marker);
    if (items.length === 0) start = number;
    else if (number !== start + items.length)
      throw createLineError(
        line.number,
        `번호는 차례대로 써야 합니다. ${start + items.length - 1}. 다음 줄이 ${number}.입니다.`,
      );
    items.push({ content: listItemContentFrom(source, line.number, context), subitems: [] });
  }
  return { kind: "numberedList", start, items };
}

function listItemContentFrom(source: string, lineNumber: number, context: ParseContext): PolicyInline[] {
  if (rejectionOf(source) !== null || lineKindOf(source) !== "text")
    throw createLineError(lineNumber, "목록 항목 안에는 제목, 표, 인용, 다른 목록을 넣을 수 없습니다.");
  return inlinesFrom(source, lineNumber, context);
}

function tableFrom(headerLine: SourceLine, bodyLines: readonly SourceLine[], context: ParseContext): PolicyBlock {
  const [delimiterLine, ...rowLines] = bodyLines;
  const header = cellsFrom(headerLine, context);
  if (delimiterLine === undefined || !/^\|(?: *-{3,} *\|)+$/.test(delimiterLine.text))
    throw createLineError(
      delimiterLine?.number ?? headerLine.number + 1,
      "표 둘째 줄은 '| --- | --- |' 꼴이어야 합니다. 정렬 표시(:)는 쓸 수 없습니다.",
    );
  const delimiterCount = delimiterLine.text.split("|").length - 2;
  if (delimiterCount !== header.length)
    throw createLineError(
      delimiterLine.number,
      `칸이 ${delimiterCount}개라서 머리 줄의 ${header.length}개와 다릅니다.`,
    );
  const rows = rowLines.map((line) => {
    if (!line.text.startsWith("|")) throw createLineError(line.number, "표 바로 아래 줄은 빈 줄이어야 합니다.");
    const cells = cellsFrom(line, context);
    if (cells.length !== header.length)
      throw createLineError(line.number, `칸이 ${cells.length}개라서 머리 줄의 ${header.length}개와 다릅니다.`);
    return cells;
  });
  return { kind: "table", header, rows };
}

function cellsFrom(line: SourceLine, context: ParseContext): PolicyCell[] {
  if (line.text.length < 2 || !line.text.endsWith("|"))
    throw createLineError(line.number, "표의 줄은 |로 시작하고 |로 끝나야 합니다.");
  return line.text
    .slice(1, -1)
    .split("|")
    .map((cell) => inlinesFrom(cell.trim(), line.number, context));
}

function quoteFrom(run: readonly SourceLine[], context: ParseContext): PolicyBlock {
  const innerLines = run.map((line) => {
    const match = /^>(?: (.*))?$/.exec(line.text);
    if (match === null)
      throw createLineError(
        line.number,
        "인용 바로 아래 줄은 빈 줄이어야 합니다. 인용을 이으려면 '> '로 시작해 주세요.",
      );
    return { number: line.number, text: match[1] ?? "" };
  });
  return { kind: "quote", blocks: blocksFrom(innerLines, context) };
}

function inlinesFrom(source: string, lineNumber: number, context: ParseContext): PolicyInline[] {
  const inlines: PolicyInline[] = [];
  let text = "";
  let index = 0;
  const push = (inline: PolicyInline, next: number) => {
    if (text !== "") inlines.push({ kind: "text", text });
    inlines.push(inline);
    text = "";
    index = next;
  };

  while (index < source.length) {
    const character = source.charAt(index);
    if (!INLINE_MARKERS.test(character)) {
      text += character;
      index += 1;
      continue;
    }
    const rest = source.slice(index);
    if (character === "`") {
      const close = source.indexOf("`", index + 1);
      if (close === -1) throw createLineError(lineNumber, "백틱(`)이 닫히지 않았습니다.");
      const code = source.slice(index + 1, close);
      if (code.trim() !== code || code === "")
        throw createLineError(lineNumber, "백틱 안은 비우거나 빈칸으로 시작하고 끝날 수 없습니다.");
      push({ kind: "code", text: code }, close + 1);
    } else if (rest.startsWith("**")) {
      const close = source.indexOf("**", index + 2);
      if (close === -1) throw createLineError(lineNumber, "굵게(**)가 닫히지 않았습니다.");
      const inner = source.slice(index + 2, close);
      if (inner === "" || inner.trim() !== inner)
        throw createLineError(lineNumber, "굵은 글자는 ** 바로 안쪽에 빈칸 없이 써 주세요.");
      // GitHub는 문장부호 앞뒤에 글자가 바로 붙은 **를 굵게로 읽지 않는다. 여기서도 같은 자리를 막는다.
      if (isFlankedByText(source.charAt(index - 1), inner.charAt(0)))
        throw createLineError(lineNumber, "여는 ** 앞에 빈칸을 넣어 주세요. 이대로면 굵게 보이지 않습니다.");
      if (isFlankedByText(source.charAt(close + 2), inner.charAt(inner.length - 1)))
        throw createLineError(lineNumber, "닫는 ** 뒤에 빈칸을 넣어 주세요. 이대로면 굵게 보이지 않습니다.");
      push({ kind: "strong", text: plainTextFrom(inner, lineNumber, context) }, close + 2);
    } else if (character === "[" && /^\[[^\]]*\]\(/.test(rest)) {
      const labelEnd = source.indexOf("]", index);
      const targetEnd = source.indexOf(")", labelEnd);
      if (targetEnd === -1) throw createLineError(lineNumber, "링크의 괄호가 닫히지 않았습니다.");
      const label = source.slice(index + 1, labelEnd);
      const target = source.slice(labelEnd + 2, targetEnd);
      if (!/^#\S+$/.test(target) || label === "")
        throw createLineError(
          lineNumber,
          "링크는 목차용 [제목](#아이디)만 쓸 수 있습니다. 주소는 링크 없이 글자로 써 주세요.",
        );
      const targetId = target.slice(1);
      context.sectionLinks.push({ targetId, lineNumber });
      push({ kind: "sectionLink", text: plainTextFrom(label, lineNumber, context), targetId }, targetEnd + 1);
    } else {
      const rejection = REJECTED_INLINES.find(({ pattern }) => pattern.test(rest));
      if (rejection !== undefined) throw createLineError(lineNumber, rejection.message);
      text += character;
      index += 1;
    }
  }
  if (text !== "") inlines.push({ kind: "text", text });
  return inlines;
}

function isFlankedByText(outside: string, inside: string): boolean {
  return PUNCTUATION.test(inside) && outside !== "" && !/\s/.test(outside) && !PUNCTUATION.test(outside);
}

function plainTextFrom(source: string, lineNumber: number, context: ParseContext): string {
  const inlines = inlinesFrom(source, lineNumber, context);
  if (inlines.some((inline) => inline.kind !== "text"))
    throw createLineError(lineNumber, "제목 줄, 굵은 글자, 링크 글자 안에는 굵게, 백틱, 링크를 쓸 수 없습니다.");
  return plainTextOf(inlines);
}
