import type { MouseEvent } from "react";
import {
  plainTextOf,
  type PolicyBlock,
  type PolicyCell,
  type PolicyInline,
  type PolicyListItem,
} from "../policyMarkdown";
import * as styles from "./PolicyBody.css";

export function PolicyBody({ blocks }: { blocks: readonly PolicyBlock[] }) {
  return (
    <div className={styles.body}>
      <Blocks blocks={blocks} />
    </div>
  );
}

function Blocks({ blocks }: { blocks: readonly PolicyBlock[] }) {
  return blocks.map((block, index) => <Block key={index} block={block} />);
}

function Block({ block }: { block: PolicyBlock }) {
  switch (block.kind) {
    case "heading": {
      const Heading = `h${block.level}` as const;
      return (
        <Heading id={block.id} tabIndex={-1}>
          <Inlines content={block.content} />
        </Heading>
      );
    }
    case "paragraph":
      return (
        <p>
          <Inlines content={block.content} />
        </p>
      );
    case "bulletList":
      return (
        <ul>
          <ListItems items={block.items} />
        </ul>
      );
    case "numberedList":
      return (
        <ol start={block.start}>
          <ListItems items={block.items} />
        </ol>
      );
    case "table":
      return <Table header={block.header} rows={block.rows} />;
    case "quote":
      return (
        <blockquote>
          <Blocks blocks={block.blocks} />
        </blockquote>
      );
  }
}

function ListItems({ items }: { items: readonly PolicyListItem[] }) {
  return items.map((item, index) => (
    <li key={index}>
      <Inlines content={item.content} />
      {item.subitems.length > 0 && (
        <ul>
          {item.subitems.map((subitem, subitemIndex) => (
            <li key={subitemIndex}>
              <Inlines content={subitem} />
            </li>
          ))}
        </ul>
      )}
    </li>
  ));
}

function Table({ header, rows }: { header: readonly PolicyCell[]; rows: readonly (readonly PolicyCell[])[] }) {
  return (
    <div
      className={styles.tableScroller}
      role="region"
      tabIndex={0}
      aria-label={`${header.map((cell) => plainTextOf(cell)).join(", ")} 표`}
    >
      <table>
        <thead>
          <tr>
            {header.map((cell, index) => (
              <th key={index} scope="col">
                <Inlines content={cell} />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, index) => (
                <td key={index}>
                  <Inlines content={cell} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Inlines({ content }: { content: readonly PolicyInline[] }) {
  return content.map((inline, index) => <Inline key={index} inline={inline} />);
}

function Inline({ inline }: { inline: PolicyInline }) {
  switch (inline.kind) {
    case "text":
      return inline.text;
    case "strong":
      return <strong>{inline.text}</strong>;
    case "code":
      return <code>{inline.text}</code>;
    case "sectionLink":
      return <SectionLink text={inline.text} targetId={inline.targetId} />;
  }
}

function SectionLink({ text, targetId }: { text: string; targetId: string }) {
  // 주소의 #으로 옮기면 기록이 하나 쌓여서, 뒤로 가기 한 번으로 처리방침을 벗어나지 못한다.
  const showSection = (event: MouseEvent<HTMLAnchorElement>) => {
    const target = document.getElementById(targetId);
    if (target === null || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    target.scrollIntoView();
    target.focus({ preventScroll: true });
  };
  return (
    <a href={`#${targetId}`} onClick={showSection}>
      {text}
    </a>
  );
}
