import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "@jest/globals";
import { editionsFrom } from "./editionPolicy";

// 레스의 게시 전 검사(check_policy.py)와 같은 정규식이다. 파일 쓰기 훅이 줄표와 가운뎃점을 막아서 코드 번호로 만든다.
const CONFIRM_MARKER = /\[\s*확인\s*필요[^\]\n]*\]?/g;
const FORBIDDEN_TEXTS: readonly { kind: string; pattern: RegExp }[] = [
  { kind: "자리표시자 <...>", pattern: /<(?!\/?[a-z][a-z0-9-]*(?:\s[^<>]*)?\/?>)(?!https?:\/\/|mailto:)[^<>\n]+>/g },
  { kind: "자리표시자 xxx", pattern: /(?<![A-Za-z])[xX]{3,}(?![A-Za-z])/g },
  { kind: "자리표시자 OOO", pattern: /(?<![A-Za-z])O{3,}(?![A-Za-z])/g },
  { kind: "자리표시자 ○○", pattern: /[○◯]{2,}/g },
  { kind: "줄표", pattern: new RegExp(String.fromCharCode(0x2014), "g") },
  { kind: "가운뎃점", pattern: new RegExp(String.fromCharCode(0xb7), "g") },
];

const editionsDirectory = join(__dirname, "editions");
const files = readdirSync(editionsDirectory)
  .filter((fileName) => fileName.endsWith(".md"))
  .sort()
  .map((fileName) => ({ fileName, markdown: readFileSync(join(editionsDirectory, fileName), "utf8") }));

function findingsOf(markdown: string, pattern: RegExp, kind = ""): string[] {
  return markdown
    .split("\n")
    .flatMap((line, index) => Array.from(line.matchAll(pattern), (match) => `${index + 1}: ${kind}${match[0]}`));
}

describe.each(files)("$fileName", ({ fileName, markdown }) => {
  it("확인 필요 표시가 남아 있지 않다", () => {
    expect(findingsOf(markdown, CONFIRM_MARKER)).toEqual([]);
  });

  it("자리표시와 쓰지 않는 문자가 없다", () => {
    expect(FORBIDDEN_TEXTS.flatMap(({ kind, pattern }) => findingsOf(markdown, pattern, `${kind} `))).toEqual([]);
  });

  it("본문의 시행일 줄이 파일 이름과 같다", () => {
    const [year, month, day] = fileName.replace(/\.md$/, "").split("-").map(Number);

    expect(markdown.split("\n")).toContain(`시행일: ${year}년 ${month}월 ${day}일`);
  });
});

it("모든 원고를 판 목록으로 읽을 수 있다", () => {
  expect(() => editionsFrom(files)).not.toThrow();
});
