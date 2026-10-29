import { kstDayOf } from "@/shared/utils/kstDayOf";
import { policyDocumentFrom, type PolicyDocument } from "./policyMarkdown";

export type EffectiveDate = string & { readonly __brand: "EffectiveDate" };

export interface Edition {
  effectiveDate: EffectiveDate;
  document: PolicyDocument;
}

export type Editions = readonly [Edition, ...Edition[]];

export interface EditionFile {
  fileName: string;
  markdown: string;
}

export interface EditionSummary {
  effectiveDate: EffectiveDate;
  lastDay: EffectiveDate | null;
  isCurrent: boolean;
  isUpcoming: boolean;
}

export type EditionView =
  | { kind: "edition"; title: string; edition: Edition; summary: EditionSummary; others: readonly EditionSummary[] }
  | { kind: "unknownDate"; title: string };

const DAY_MS = 24 * 60 * 60_000;

export function editionsFrom(files: readonly EditionFile[]): Editions {
  const [newest, ...older] = files
    .map(editionFrom)
    .sort((left, right) => (left.effectiveDate < right.effectiveDate ? 1 : -1));
  if (newest === undefined)
    throw new Error("처리방침 원고가 하나도 없습니다. editions 폴더에 시행일.md 파일을 넣어 주세요.");
  return [newest, ...older];
}

// 시행일이 오늘이거나 지난 판 가운데 가장 새 판이 지금 판이다. 시행 전인 첫 판은 시행일까지 미리 보여 준다.
export function editionViewFor(editions: Editions, requestedDate: string | undefined, now: number): EditionView {
  const today = kstDayOf(now);
  const current = editions.find((edition) => edition.effectiveDate <= today) ?? editions.at(-1) ?? editions[0];
  const entries = editions.map((edition, index) => {
    const newer = editions[index - 1];
    const summary: EditionSummary = {
      effectiveDate: edition.effectiveDate,
      lastDay: newer === undefined ? null : previousDayOf(newer.effectiveDate),
      isCurrent: edition === current,
      isUpcoming: edition.effectiveDate > today,
    };
    return { edition, summary };
  });
  const shown = entries.find(({ edition }) =>
    requestedDate === undefined ? edition === current : edition.effectiveDate === requestedDate,
  );
  if (shown === undefined) return { kind: "unknownDate", title: current.document.title };
  return {
    kind: "edition",
    title: shown.edition.document.title,
    edition: shown.edition,
    summary: shown.summary,
    others: entries.filter((entry) => entry !== shown).map(({ summary }) => summary),
  };
}

function editionFrom({ fileName, markdown }: EditionFile): Edition {
  const effectiveDate = effectiveDateFrom(fileName.replace(/\.md$/, ""));
  if (!fileName.endsWith(".md") || effectiveDate === null)
    throw new Error(`${fileName}: 파일 이름은 시행일이어야 합니다(예: 2026-11-04.md).`);
  try {
    return { effectiveDate, document: policyDocumentFrom(markdown) };
  } catch (error) {
    throw new Error(`${fileName} ${error instanceof Error ? error.message : String(error)}`, { cause: error });
  }
}

function effectiveDateFrom(text: string): EffectiveDate | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return null;
  const time = Date.parse(`${text}T00:00:00Z`);
  if (Number.isNaN(time) || new Date(time).toISOString().slice(0, 10) !== text) return null;
  return text as EffectiveDate;
}

function previousDayOf(date: EffectiveDate): EffectiveDate {
  return new Date(Date.parse(`${date}T00:00:00Z`) - DAY_MS).toISOString().slice(0, 10) as EffectiveDate;
}
