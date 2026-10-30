import { describe, expect, it } from "@jest/globals";
import { policyDocumentFrom, type PolicyBlock } from "./policyMarkdown";

function blocksFrom(...lines: string[]): readonly PolicyBlock[] {
  return policyDocumentFrom(["# 개인정보 처리방침", "", ...lines].join("\n")).blocks;
}

function parsingOf(...lines: string[]) {
  return () => blocksFrom(...lines);
}

describe("policyDocumentFrom", () => {
  it("첫 줄의 # 제목을 문서 제목으로 읽고 본문 블록에는 넣지 않는다", () => {
    const document = policyDocumentFrom("# 개인정보 처리방침\n\n시행일: 2026년 11월 4일\n");

    expect(document.title).toBe("개인정보 처리방침");
    expect(document.blocks).toEqual([
      { kind: "paragraph", content: [{ kind: "text", text: "시행일: 2026년 11월 4일" }] },
    ]);
  });

  it("윈도우 편집기가 붙이는 BOM과 CRLF 줄바꿈이 있어도 같게 읽는다", () => {
    const byteOrderMark = String.fromCharCode(0xfeff);

    expect(policyDocumentFrom(`${byteOrderMark}# 개인정보 처리방침\r\n\r\n본문\r\n`)).toEqual({
      title: "개인정보 처리방침",
      blocks: [{ kind: "paragraph", content: [{ kind: "text", text: "본문" }] }],
    });
  });

  it("첫 줄에 # 제목이 없거나 # 제목이 또 나오면 그 줄 번호로 알린다", () => {
    expect(() => policyDocumentFrom("본문")).toThrow("1번째 줄");
    expect(parsingOf("# 두 번째 제목")).toThrow("3번째 줄");
  });

  it("제목 아이디를 GitHub와 같은 규칙으로 만들고, 같은 아이디는 -1, -2를 붙인다", () => {
    const ids = blocksFrom(
      "## 10. 자동 수집 장치의 설치, 운영과 거부",
      "### 2-1. 동의 없이 처리하는 항목과 법적 근거",
      "#### Amplitude로 보내는 정보",
      "## 2-5. 설문 응답 (선택)",
      "## 목차",
      "## 목차",
      "## 목차",
    ).map((block) => (block.kind === "heading" ? `${block.level} ${block.id}` : block.kind));

    expect(ids).toEqual([
      "2 10-자동-수집-장치의-설치-운영과-거부",
      "3 2-1-동의-없이-처리하는-항목과-법적-근거",
      "4 amplitude로-보내는-정보",
      "2 2-5-설문-응답-선택",
      "2 목차",
      "2 목차-1",
      "2 목차-2",
    ]);
  });

  it("목차를 시작 번호가 있는 번호 목록과 제목 링크로 읽는다", () => {
    const [toc] = blocksFrom(
      "1. [개인정보의 처리 목적](#1-개인정보의-처리-목적)",
      "2. [처리하는 개인정보의 항목](#2-처리하는-개인정보의-항목)",
      "",
      "## 1. 개인정보의 처리 목적",
      "",
      "## 2. 처리하는 개인정보의 항목",
    );

    expect(toc).toEqual({
      kind: "numberedList",
      start: 1,
      items: [
        {
          content: [{ kind: "sectionLink", text: "개인정보의 처리 목적", targetId: "1-개인정보의-처리-목적" }],
          subitems: [],
        },
        {
          content: [{ kind: "sectionLink", text: "처리하는 개인정보의 항목", targetId: "2-처리하는-개인정보의-항목" }],
          subitems: [],
        },
      ],
    });
  });

  it("목차 링크가 가리키는 제목이 없으면 그 링크의 줄 번호로 알린다", () => {
    expect(parsingOf("## 1. 개인정보의 처리 목적", "", "1. [목적](#1-처리-목적)")).toThrow(
      "5번째 줄: 목차 링크 #1-처리-목적에 맞는 제목이 없습니다.",
    );
  });

  it("번호를 건너뛰면 쓴 번호와 보이는 번호가 달라지므로 그 줄 번호로 알린다", () => {
    expect(blocksFrom("3. 셋째", "4. 넷째")).toMatchObject([{ kind: "numberedList", start: 3 }]);
    expect(parsingOf("1. 첫째", "3. 셋째")).toThrow("4번째 줄");
  });

  it("굵은 머리말로 시작하는 목록 항목과 두 칸 들여쓴 하위 항목을 읽는다", () => {
    expect(
      blocksFrom(
        "- **기기와 브라우저 정보.** 기기 ID와 `User-Agent`를 보냅니다.",
        "- **자동 이용 기록.** 아래 기록을 보냅니다.",
        "  - 세션 시작과 끝",
        "  - 파일 다운로드",
      ),
    ).toEqual([
      {
        kind: "bulletList",
        items: [
          {
            content: [
              { kind: "strong", text: "기기와 브라우저 정보." },
              { kind: "text", text: " 기기 ID와 " },
              { kind: "code", text: "User-Agent" },
              { kind: "text", text: "를 보냅니다." },
            ],
            subitems: [],
          },
          {
            content: [
              { kind: "strong", text: "자동 이용 기록." },
              { kind: "text", text: " 아래 기록을 보냅니다." },
            ],
            subitems: [[{ kind: "text", text: "세션 시작과 끝" }], [{ kind: "text", text: "파일 다운로드" }]],
          },
        ],
      },
    ]);
  });

  it("표의 칸에 든 코드, 확인 필요 표시, 주소, 메일, 빈 칸을 그대로 읽는다", () => {
    expect(
      blocksFrom(
        "| 이름 | 만드는 곳 | 목적 | 담는 정보 | 보관 기간 |",
        "| --- | --- | --- | --- | --- |",
        "| `__Host-sb_at` | 연어버스 서버 | 로그인 상태 확인 | [확인 필요: 만료 시각] | 15분 |",
        "| 연락처 | privacy@amplitude.com | https://www.apple.com/legal/privacy/contact/ | 미국 | |",
      ),
    ).toEqual([
      {
        kind: "table",
        header: ["이름", "만드는 곳", "목적", "담는 정보", "보관 기간"].map((text) => [{ kind: "text", text }]),
        rows: [
          [
            [{ kind: "code", text: "__Host-sb_at" }],
            [{ kind: "text", text: "연어버스 서버" }],
            [{ kind: "text", text: "로그인 상태 확인" }],
            [{ kind: "text", text: "[확인 필요: 만료 시각]" }],
            [{ kind: "text", text: "15분" }],
          ],
          [
            [{ kind: "text", text: "연락처" }],
            [{ kind: "text", text: "privacy@amplitude.com" }],
            [{ kind: "text", text: "https://www.apple.com/legal/privacy/contact/" }],
            [{ kind: "text", text: "미국" }],
            [],
          ],
        ],
      },
    ]);
  });

  it("칸 수가 머리 줄과 다른 표 줄은 그 줄 번호로 알린다", () => {
    expect(parsingOf("| 목적 | 하는 일 |", "| --- | --- |", "| 회원 관리 |")).toThrow("5번째 줄");
  });

  it("인용 안의 문단과 번호 목록을 읽고, 이어 쓴 줄은 한 문단으로 합친다", () => {
    expect(
      blocksFrom(
        "> [확인 필요: Google Fonts 처리 방법. 레스가 고름]",
        ">",
        "> 1. 글꼴을 연어버스 서버에 둡니다.",
        "> 2. 이 표에 Google LLC 열을 더합니다.",
        "",
        "첫 줄",
        "둘째 줄",
      ),
    ).toEqual([
      {
        kind: "quote",
        blocks: [
          { kind: "paragraph", content: [{ kind: "text", text: "[확인 필요: Google Fonts 처리 방법. 레스가 고름]" }] },
          {
            kind: "numberedList",
            start: 1,
            items: [
              { content: [{ kind: "text", text: "글꼴을 연어버스 서버에 둡니다." }], subitems: [] },
              { content: [{ kind: "text", text: "이 표에 Google LLC 열을 더합니다." }], subitems: [] },
            ],
          },
        ],
      },
      { kind: "paragraph", content: [{ kind: "text", text: "첫 줄\n둘째 줄" }] },
    ]);
  });

  it.each([
    ["다섯째 단계 제목", ["##### 다섯째"], 3],
    ["코드 블록", ["```", "코드", "```"], 3],
    ["* 목록", ["* 항목"], 3],
    ["가로줄", ["---"], 3],
    ["기울임", ["글자 *기울임* 글자"], 3],
    ["밑줄 기울임", ["글자 _기울임_ 글자"], 3],
    ["취소선", ["글자 ~~취소~~ 글자"], 3],
    ["물결표 범위", ["15~20초"], 3],
    ["그림", ["![그림](a.png)"], 3],
    ["바깥 주소 링크", ["[연어버스](https://www.salmonbus.com)"], 3],
    ["HTML 태그", ["<b>굵게</b>"], 3],
    ["꺾쇠 주소", ["<https://www.salmonbus.com>"], 3],
    ["HTML 문자 참조", ["A&amp;B"], 3],
    ["역슬래시", ["| 가 | 나 |", "| --- | --- |", "| 가 \\| 나 | 다 |"], 5],
    ["네 칸 들여쓰기", ["    들여쓴 줄"], 3],
    ["두 단계 하위 목록", ["- 항목", "  - 하위", "    - 그 아래"], 5],
    ["표의 정렬 표시", ["| 가 | 나 |", "| :--- | --- |"], 4],
    ["목록 바로 아래 글", ["- 항목", "이어지는 글"], 4],
    ["문단 바로 아래 목록", ["문단", "- 항목"], 4],
    ["표 바로 아래 글", ["| 가 |", "| --- |", "| 나 |", "다음 글"], 6],
    ["굵게 뒤에 바로 붙은 글자", ["**정보.**뒤"], 3],
  ])("%s는 쓸 수 없어서 그 줄 번호로 알린다", (_name, lines, lineNumber) => {
    expect(parsingOf(...lines)).toThrow(`${lineNumber}번째 줄: `);
  });
});
