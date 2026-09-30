import type { Locator, Page } from "@playwright/test";
import {
  chatAvailability,
  chatPaths,
  chatRouteId,
  clientFrames,
  expect,
  isChatChunk,
  serverFrames,
  test,
  type ChatFrame,
  type ChatSocket,
} from "./fixtures/chat";
import { routeId as unsupportedRouteId } from "./fixtures/mock-data";

const phoneViewport = { width: 390, height: 844 };

test.use({
  viewport: phoneViewport,
  reducedMotion: "reduce",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const history = serverFrames["history.batch"].messages;
const sentBody = clientFrames["message.send"].body;
const LONG_HISTORY_COUNT = 20;
const HALF_SHEET_HEIGHT = 439;
const QUARTER_SHEET_HEIGHT = 220;
const DRAG_START_NUDGE = 8;
const CARD_GAP = 24;
const HISTORY_BATCH_SIZE = 10;
const FRAME_MS = 50;
const STEP_DRAG_PX = 60;
const SMALL_DRAG_PX = 15;

function launcher(page: Page) {
  return page.getByRole("button", { name: `${chatAvailability.displayName} 채팅 열기`, exact: true });
}

function sheet(page: Page) {
  return page.locator("#route-chat-dialog");
}

function chatElements(page: Page) {
  return page.locator("#route-chat-dialog, [aria-controls='route-chat-dialog']");
}

function statusBand(page: Page) {
  return sheet(page).locator("[role='status'][data-tone]");
}

function messageLog(page: Page) {
  return page.getByRole("log", { name: `${chatAvailability.displayName} 채팅 메시지`, exact: true });
}

function messages(page: Page) {
  return messageLog(page).getByRole("article");
}

function sheetHead(page: Page) {
  return sheet(page).locator("header");
}

function sheetTitle(page: Page) {
  return sheet(page).getByRole("heading", { name: `${chatAvailability.displayName} 채팅`, exact: true });
}

function composer(page: Page) {
  return sheet(page).locator("form");
}

function boardCard(page: Page) {
  return page.locator("main > section");
}

async function edges(locator: Locator) {
  const box = await locator.boundingBox();
  if (box === null) throw new Error("요소가 화면에 그려져 있어야 합니다");
  return {
    top: box.y,
    bottom: box.y + box.height,
    left: box.x,
    right: box.x + box.width,
    width: box.width,
    height: box.height,
  };
}

async function shownSheetHeight(page: Page) {
  return (await edges(sheet(page))).height;
}

async function sheetBottom(page: Page) {
  return (await edges(sheet(page))).bottom;
}

async function composerBottom(page: Page) {
  return (await edges(composer(page))).bottom;
}

async function lastMessageBottom(page: Page) {
  return (await edges(messages(page).last())).bottom;
}

async function pixelGap(value: Promise<number>, expected: number) {
  return Math.abs((await value) - expected);
}

function historyFrames(count: number): ChatFrame[] {
  const startedAt = Date.parse(history[0]!.createdAt);
  const saved = Array.from({ length: count }, (_, index) => {
    const template = history[index % history.length]!;
    return {
      ...template,
      id: `68db0000000000000001${String(index).padStart(4, "0")}`,
      body: `${template.body} ${index + 1}`,
      createdAt: new Date(startedAt + index * 1_000).toISOString(),
    };
  });
  return Array.from({ length: Math.ceil(count / HISTORY_BATCH_SIZE) }, (_, index) => ({
    ...serverFrames["history.batch"],
    messages: saved.slice(index * HISTORY_BATCH_SIZE, (index + 1) * HISTORY_BATCH_SIZE),
  }));
}

function sendHistory(socket: ChatSocket, count: number) {
  for (const frame of historyFrames(count)) socket.send(frame);
  socket.send(serverFrames["history.end"]);
}

async function openChatRouteBoard(page: Page, expectedStatus: number) {
  const availability = page.waitForResponse((response) => new URL(response.url()).pathname === chatPaths.availability);
  await page.goto(`/routes/${chatRouteId}`);
  await expect(page.getByRole("heading", { name: chatAvailability.displayName, exact: true })).toBeVisible();
  expect((await availability).status()).toBe(expectedStatus);
}

async function waitForLauncher(page: Page) {
  await expect
    .poll(async () => {
      await page.clock.runFor(100);
      return launcher(page).count();
    })
    .toBe(1);
}

async function waitForSessionStart(page: Page, sockets: ChatSocket[]) {
  await expect
    .poll(async () => {
      await page.clock.runFor(10);
      return sockets.length;
    })
    .toBe(1);
  const socket = sockets[0]!;
  expect(socket.path).toBe(chatPaths.stream);
  await expect
    .poll(() => socket.received)
    .toEqual([{ ...clientFrames["session.start"], clientSessionId: expect.stringMatching(uuidV4) }]);
  await expect(sheet(page)).toHaveAttribute("data-connection", "connecting");
  return socket;
}

async function flushPageTasks(page: Page) {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = () => resolve();
        channel.port2.postMessage(null);
      }),
  );
}

async function settleAfterLoad(page: Page) {
  await page.waitForLoadState("networkidle");
  await page.clock.runFor(1_000);
  await flushPageTasks(page);
}

async function openReadyChat(page: Page, sockets: ChatSocket[], messageCount = history.length) {
  await openChatRouteBoard(page, 200);
  await waitForLauncher(page);
  await launcher(page).click();
  const socket = await waitForSessionStart(page, sockets);
  socket.send(serverFrames["session.ready"]);
  sendHistory(socket, messageCount);
  await expect(sheet(page)).toHaveAttribute("data-connection", "ready");
  await expect(messages(page)).toHaveCount(messageCount);
  await expect
    .poll(() => messageLog(page).evaluate((log) => log.scrollHeight - log.scrollTop - log.clientHeight))
    .toBeLessThanOrEqual(1);
  return socket;
}

async function dragSheetHead(page: Page, distanceY: number) {
  const title = await edges(sheetTitle(page));
  const x = (title.left + title.right) / 2;
  const pressedY = (title.top + title.bottom) / 2;
  const startY = pressedY + Math.sign(distanceY) * DRAG_START_NUDGE;
  await page.mouse.move(x, pressedY);
  await page.mouse.down();
  await page.mouse.move(x, startY);
  await page.mouse.move(x, startY + distanceY, { steps: 10 });
  await page.clock.runFor(FRAME_MS);
}

async function dropSheetHead(page: Page, distanceY: number) {
  await dragSheetHead(page, distanceY);
  await page.mouse.up();
  await page.clock.runFor(FRAME_MS);
}

async function panelOffsetFromCard(page: Page) {
  const card = await edges(boardCard(page));
  const panel = await edges(sheet(page));
  return Math.max(
    Math.abs(panel.width - card.width),
    Math.abs(panel.top - card.top),
    Math.abs(panel.bottom - card.bottom),
    Math.abs(panel.left - card.right - CARD_GAP),
  );
}

async function sheetOffsetFromCardSides(page: Page) {
  const card = await edges(boardCard(page));
  const panel = await edges(sheet(page));
  return Math.max(Math.abs(panel.left - card.left), Math.abs(panel.right - card.right));
}

test("채팅 대상 노선에서 켜짐 확인이 404면 채팅 화면, 청크, WebSocket이 모두 없다", async ({ page, chat }) => {
  // given
  chat.enabled = false;

  // when
  await openChatRouteBoard(page, 404);
  await settleAfterLoad(page);

  // then
  expect(new Set(chat.requests)).toEqual(new Set([`GET ${chatPaths.availability}`]));
  await expect(chatElements(page)).toHaveCount(0);
  expect(chat.scripts.filter(isChatChunk)).toEqual([]);
  expect(chat.sockets).toEqual([]);
});

test("서버가 지원하지 않는 노선은 켜짐 확인 뒤 채팅을 숨긴다", async ({ page, api, chat }) => {
  // given
  chat.enabled = true;

  // when
  await page.goto(`/routes/${unsupportedRouteId}`);
  await expect(page.getByRole("heading", { name: api.data.board.route.displayName, exact: true })).toBeVisible();
  await settleAfterLoad(page);

  // then
  expect(new Set(chat.requests)).toEqual(new Set([`GET /api/chat/rooms/${unsupportedRouteId}`]));
  await expect(chatElements(page)).toHaveCount(0);
  expect(chat.scripts.filter(isChatChunk)).toEqual([]);
  expect(chat.sockets).toEqual([]);
});

test("채팅이 켜져 있으면 알약으로 열어 이력을 받고, 보낸 메시지가 ACK 뒤 확정된다", async ({ page, chat }) => {
  chat.enabled = true;
  let socket!: ChatSocket;

  await test.step("켜짐 응답이 오면 채팅 청크를 받아 알약을 그리고, 곧바로 WebSocket을 열어 session.start를 보낸다", async () => {
    await openChatRouteBoard(page, 200);
    await waitForLauncher(page);
    await expect(launcher(page)).toBeVisible();
    await expect(sheet(page)).toHaveAttribute("data-position", "collapsed");
    expect(chat.scripts.filter(isChatChunk)).toHaveLength(1);
    socket = await waitForSessionStart(page, chat.sockets);
  });

  await test.step("알약을 누르면 반 높이로 열리고 연결하는 중과 불러오는 중이 보인다", async () => {
    await launcher(page).click();
    await expect(sheet(page)).toHaveAttribute("data-position", "half");
    await expect(page.getByRole("dialog", { name: `${chatAvailability.displayName} 채팅`, exact: true })).toBeVisible();
    await expect(launcher(page)).toHaveCount(0);
    await expect(statusBand(page)).toHaveText("대화방에 연결하는 중이에요");
    await expect(page.getByLabel("최근 대화를 불러오는 중", { exact: true })).toBeVisible();
  });

  await test.step("session.ready와 이력을 받으면 내 이름과 이력 두 개가 보인다", async () => {
    socket.send(serverFrames["session.ready"]);
    socket.send(serverFrames["history.batch"]);
    socket.send(serverFrames["history.end"]);

    await expect(sheet(page)).toHaveAttribute("data-connection", "ready");
    await expect(statusBand(page)).toHaveCount(0);
    await expect(sheet(page).getByText(`${serverFrames["session.ready"].nickname} 님으로 참여 중`)).toBeVisible();
    await expect(messages(page)).toHaveCount(history.length);
    for (const [index, message] of history.entries()) {
      await expect(messages(page).nth(index)).toContainText(message.body);
    }
    await expect(messages(page).nth(0)).toContainText(history[0]!.nickname);
    await expect(messages(page).nth(1)).not.toContainText(history[1]!.nickname);
  });

  let sent!: ChatFrame;
  await test.step("입력하고 보내면 보내는 중으로 보이고 message.send를 보낸다", async () => {
    const composer = page.getByRole("textbox", { name: "메시지", exact: true });
    await composer.fill(sentBody);
    await page.getByRole("button", { name: "메시지 보내기", exact: true }).click();

    const pending = messages(page).filter({ hasText: "보내는 중" });
    await expect(pending).toHaveCount(1);
    await expect(pending).toContainText(sentBody);
    await expect(composer).toHaveValue("");
    await expect(composer).toBeFocused();
    const sendButton = page.getByRole("button", { name: "메시지 보내기", exact: true });
    await expect(sendButton).toHaveAttribute("aria-disabled", "true");
    await expect(sendButton).not.toHaveAttribute("disabled");
    await expect
      .poll(() => socket.received.slice(1))
      .toEqual([{ ...clientFrames["message.send"], clientMessageId: expect.stringMatching(uuidV4) }]);
    sent = socket.received[1]!;
  });

  await test.step("같은 clientMessageId로 ACK와 created를 받으면 보내는 중이 사라지고 메시지가 확정된다", async () => {
    socket.send({ ...serverFrames["message.ack"], clientMessageId: sent.clientMessageId });
    socket.send(serverFrames["message.created"]);

    await expect(page.getByText("보내는 중", { exact: true })).toHaveCount(0);
    await expect(messages(page)).toHaveCount(history.length + 1);
    await expect(messages(page).last()).toContainText(sentBody);
    await expect(messages(page).last()).not.toContainText("전송 실패");
    expect(chat.sockets).toHaveLength(1);
  });
});

test("requestId·retryAfterMs가 null인 치명 오류 뒤 1008로 닫히면 연결 실패로 두고 스스로 다시 잇지 않는다", async ({
  page,
  chat,
}) => {
  // given
  expect(serverFrames["error.helloRequired"]).toMatchObject({ requestId: null, retryAfterMs: null, fatal: true });
  chat.enabled = true;
  await openChatRouteBoard(page, 200);
  await waitForLauncher(page);
  await launcher(page).click();
  const socket = await waitForSessionStart(page, chat.sockets);

  // when
  socket.send(serverFrames["error.helloRequired"]);
  await socket.close(1008);

  // then
  await expect(sheet(page)).toHaveAttribute("data-connection", "failed");
  await expect(statusBand(page)).toHaveAttribute("data-tone", "error");
  await expect(statusBand(page)).toContainText("대화방에 연결하지 못했어요");
  await expect(sheet(page).getByRole("button", { name: "다시 연결", exact: true })).toHaveCount(1);

  await page.clock.runFor(30_000);
  await flushPageTasks(page);
  expect(await sheet(page).getAttribute("data-connection")).toBe("failed");
  expect(chat.sockets).toHaveLength(1);
});

test("입장 때 저장소 오류(CHAT_UNAVAILABLE, 치명 아님) 뒤 1011로 닫히면 같은 세션 ID로 다시 이어 붙는다", async ({
  page,
  chat,
}) => {
  // given
  expect(serverFrames["error.unavailable"]).toMatchObject({ requestId: null, retryAfterMs: null, fatal: false });
  chat.enabled = true;
  await openChatRouteBoard(page, 200);
  await waitForLauncher(page);
  await launcher(page).click();
  const first = await waitForSessionStart(page, chat.sockets);
  const clientSessionId = (first.received[0] as { clientSessionId: string }).clientSessionId;

  // when
  first.send(serverFrames["session.ready"]);
  first.send(serverFrames["error.unavailable"]);
  await first.close(1011);

  // then
  await expect(sheet(page)).toHaveAttribute("data-connection", "reconnecting");
  await expect
    .poll(async () => {
      await page.clock.runFor(500);
      return chat.sockets.length;
    })
    .toBe(2);
  const second = chat.sockets[1]!;
  await expect.poll(() => second.received).toEqual([{ ...clientFrames["session.start"], clientSessionId }]);

  second.send(serverFrames["session.ready"]);
  second.send(serverFrames["history.batch"]);
  second.send(serverFrames["history.end"]);
  await expect(sheet(page)).toHaveAttribute("data-connection", "ready");
  await expect(messages(page)).toHaveCount(history.length);
  expect(chat.sockets).toHaveLength(2);
});

test("입장 때 저장소 오류가 되풀이되면 session.ready를 받아도 간격을 늘려 다시 잇는다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  await openChatRouteBoard(page, 200);
  await waitForLauncher(page);
  await launcher(page).click();
  const first = await waitForSessionStart(page, chat.sockets);
  first.send(serverFrames["session.ready"]);
  first.send(serverFrames["error.unavailable"]);
  await first.close(1011);
  await expect
    .poll(async () => {
      await page.clock.runFor(250);
      return chat.sockets.length;
    })
    .toBe(2);
  const second = chat.sockets[1]!;
  await expect.poll(() => second.received.length).toBe(1);

  // when
  second.send(serverFrames["session.ready"]);
  second.send(serverFrames["error.unavailable"]);
  await second.close(1011);
  await expect(sheet(page)).toHaveAttribute("data-connection", "reconnecting");
  await page.clock.runFor(1_500);
  await flushPageTasks(page);

  // then
  expect(chat.sockets).toHaveLength(2);
  await expect
    .poll(async () => {
      await page.clock.runFor(250);
      return chat.sockets.length;
    })
    .toBe(3);
});

test("머리를 끄는 동안 시트가 손가락을 따라 커지고 입력줄과 마지막 메시지는 제자리에 있으며, 적당히 올리고 놓으면 전체로 붙는다", async ({
  page,
  chat,
}) => {
  // given
  chat.enabled = true;
  await openReadyChat(page, chat.sockets, LONG_HISTORY_COUNT);
  await expect.poll(() => pixelGap(shownSheetHeight(page), HALF_SHEET_HEIGHT)).toBeLessThanOrEqual(1);
  const lastBottom = await lastMessageBottom(page);

  // when
  await dragSheetHead(page, -STEP_DRAG_PX);

  // then
  await expect.poll(() => pixelGap(shownSheetHeight(page), HALF_SHEET_HEIGHT + STEP_DRAG_PX)).toBeLessThanOrEqual(1);
  await expect.poll(() => pixelGap(composerBottom(page), phoneViewport.height)).toBeLessThanOrEqual(1);
  await expect.poll(() => pixelGap(lastMessageBottom(page), lastBottom)).toBeLessThanOrEqual(1);

  // when
  await page.mouse.up();
  await page.clock.runFor(1_000);

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "full");
  await expect.poll(() => pixelGap(shownSheetHeight(page), phoneViewport.height)).toBeLessThanOrEqual(1);
  await expect.poll(() => pixelGap(lastMessageBottom(page), lastBottom)).toBeLessThanOrEqual(1);
});

test("조금만 끌고 놓으면 원래 단계로 돌아간다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  await openReadyChat(page, chat.sockets);

  // when
  await dropSheetHead(page, -SMALL_DRAG_PX);

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "half");
  await expect.poll(() => pixelGap(shownSheetHeight(page), HALF_SHEET_HEIGHT)).toBeLessThanOrEqual(1);
});

test("적당히 내리면 반의 반으로, 한 번 더 내리면 접히며 초점이 알약으로 간다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  await openReadyChat(page, chat.sockets);

  // when
  await dropSheetHead(page, STEP_DRAG_PX);

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "quarter");
  await expect.poll(() => pixelGap(shownSheetHeight(page), QUARTER_SHEET_HEIGHT)).toBeLessThanOrEqual(1);
  await expect.poll(() => pixelGap(composerBottom(page), phoneViewport.height)).toBeLessThanOrEqual(1);

  // when
  await dropSheetHead(page, STEP_DRAG_PX);

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "collapsed");
  await page.clock.runFor(100);
  await expect(launcher(page)).toBeFocused();
});

test("전체에서 적당히 내리면 반으로 돌아가고, 전체에서 손잡이를 누르면 접힌다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  await openReadyChat(page, chat.sockets);
  await dropSheetHead(page, -STEP_DRAG_PX);
  await expect(sheet(page)).toHaveAttribute("data-position", "full");
  await expect(sheet(page)).toHaveAttribute("aria-modal", "true");

  // when
  await dropSheetHead(page, STEP_DRAG_PX);

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "half");
  await expect(sheet(page)).toHaveAttribute("aria-modal", "false");

  // when
  await sheetHead(page).getByRole("button", { name: "채팅 크게 보기", exact: true }).click();
  await sheetHead(page).getByRole("button", { name: "채팅 접기", exact: true }).click();

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "collapsed");
  await page.clock.runFor(100);
  await expect(launcher(page)).toBeFocused();
});

test("휴대폰 시트 머리의 조절 버튼은 손잡이 하나로, 반의 반에서 누르면 반, 전체 순으로 커지고 다시 열면 반 높이다", async ({
  page,
  chat,
}) => {
  // given
  chat.enabled = true;
  await openReadyChat(page, chat.sockets);
  await expect.poll(async () => (await edges(sheet(page))).width).toBe(phoneViewport.width);
  await expect(sheetHead(page).getByRole("button")).toHaveCount(1);
  await dropSheetHead(page, STEP_DRAG_PX);
  await expect(sheet(page)).toHaveAttribute("data-position", "quarter");

  // when
  await sheetHead(page).getByRole("button", { name: "채팅 크게 보기", exact: true }).click();

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "half");

  // when
  await sheetHead(page).getByRole("button", { name: "채팅 크게 보기", exact: true }).click();

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "full");
  await expect(sheetHead(page).getByRole("button")).toHaveCount(1);

  // when
  await sheetHead(page).getByRole("button", { name: "채팅 접기", exact: true }).click();
  await page.clock.runFor(100);
  await launcher(page).click();

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "half");
  await expect.poll(() => pixelGap(shownSheetHeight(page), HALF_SHEET_HEIGHT)).toBeLessThanOrEqual(1);
});

test("입력칸을 눌러도 시트 높이와 위치가 그대로다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  await openReadyChat(page, chat.sockets);

  // when
  await page.getByRole("textbox", { name: "메시지", exact: true }).click();
  await page.clock.runFor(500);

  // then
  await expect(sheet(page)).toHaveAttribute("data-position", "half");
  await expect.poll(() => pixelGap(shownSheetHeight(page), HALF_SHEET_HEIGHT)).toBeLessThanOrEqual(1);
});

test("연결 상태 띠가 끼었다 빠져도 마지막 메시지 위치가 그대로다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  const first = await openReadyChat(page, chat.sockets, LONG_HISTORY_COUNT);
  const lastBottom = await lastMessageBottom(page);

  // when
  await first.close(1001);

  // then
  await expect(sheet(page)).toHaveAttribute("data-connection", "reconnecting");
  await expect(statusBand(page)).toHaveCount(1);
  await expect.poll(() => pixelGap(lastMessageBottom(page), lastBottom)).toBeLessThanOrEqual(1);

  // when
  await expect
    .poll(async () => {
      await page.clock.runFor(500);
      return chat.sockets.length;
    })
    .toBe(2);
  const second = chat.sockets[1]!;
  await expect.poll(() => second.received.length).toBe(1);
  second.send(serverFrames["session.ready"]);
  sendHistory(second, LONG_HISTORY_COUNT);

  // then
  await expect(statusBand(page)).toHaveText("다시 연결됐어요");
  await expect.poll(() => pixelGap(lastMessageBottom(page), lastBottom)).toBeLessThanOrEqual(1);

  // when
  await page.clock.runFor(2_000);

  // then
  await expect(statusBand(page)).toHaveCount(0);
  await expect.poll(() => pixelGap(lastMessageBottom(page), lastBottom)).toBeLessThanOrEqual(1);
});

test("보이는 영역 높이가 바뀌면 시트와 입력줄 바닥이 새 바닥에 붙고 반 높이도 새 높이로 다시 잡는다", async ({
  page,
  chat,
}) => {
  // given
  const shorterHeight = 780;
  const shorterHalfHeight = 406;
  chat.enabled = true;
  await openReadyChat(page, chat.sockets);

  // when
  await page.setViewportSize({ width: phoneViewport.width, height: shorterHeight });

  // then
  await expect.poll(() => pixelGap(sheetBottom(page), shorterHeight)).toBeLessThanOrEqual(1);
  await expect.poll(() => pixelGap(composerBottom(page), shorterHeight)).toBeLessThanOrEqual(1);
  await expect.poll(() => pixelGap(shownSheetHeight(page), shorterHalfHeight)).toBeLessThanOrEqual(1);
});

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1280, height: 800 },
]) {
  test.describe(`${viewport.width}px 화면`, () => {
    test.use({ viewport });

    test("패널이 목록 카드 오른쪽 24px 옆에 카드와 같은 폭·위·아래로 열리고, 조절 버튼은 접기 하나다", async ({
      page,
      chat,
    }) => {
      // given
      chat.enabled = true;

      // when
      await openReadyChat(page, chat.sockets);

      // then
      await expect.poll(() => panelOffsetFromCard(page)).toBeLessThanOrEqual(1);
      await expect(sheetHead(page).getByRole("button")).toHaveCount(1);
      await expect(sheetHead(page).getByRole("button", { name: "채팅 접기", exact: true })).toBeVisible();
    });
  });
}

test.describe("1024px 화면", () => {
  test.use({ viewport: { width: 1024, height: 768 } });

  test("시트 좌우가 목록 카드 좌우 선과 같다", async ({ page, chat }) => {
    // given
    chat.enabled = true;

    // when
    await openReadyChat(page, chat.sockets);

    // then
    await expect.poll(() => sheetOffsetFromCardSides(page)).toBeLessThanOrEqual(1);
  });
});

test("이름을 받기 전에 연결에 실패하면 불러오는 중 표시와 이름 준비 문구를 거둔다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  await openChatRouteBoard(page, 200);
  await waitForLauncher(page);
  await launcher(page).click();
  const socket = await waitForSessionStart(page, chat.sockets);
  await expect(sheet(page).getByText("익명 이름을 준비하고 있어요", { exact: true })).toBeVisible();

  // when
  socket.send(serverFrames["error.helloRequired"]);
  await socket.close(1008);

  // then
  await expect(sheet(page)).toHaveAttribute("data-connection", "failed");
  await expect(sheet(page).getByLabel("최근 대화를 불러오는 중", { exact: true })).toHaveCount(0);
  await expect(sheet(page).getByText("익명 이름을 준비하고 있어요", { exact: true })).toHaveCount(0);
});

test("너무 빨리 보내 거절돼도 그 메시지와 실패 표시가 목록 바닥에 가려지지 않는다", async ({ page, chat }) => {
  // given
  chat.enabled = true;
  const socket = await openReadyChat(page, chat.sockets, LONG_HISTORY_COUNT);
  await page.getByRole("textbox", { name: "메시지", exact: true }).fill(sentBody);
  await page.getByRole("button", { name: "메시지 보내기", exact: true }).click();
  await expect.poll(() => socket.received.length).toBe(2);
  const sent = socket.received[1] as { clientMessageId: string };

  // when
  socket.send({ ...serverFrames["error.rateLimited"], requestId: sent.clientMessageId });

  // then
  await expect(messages(page).last()).toContainText("전송 실패");
  await expect
    .poll(async () => (await lastMessageBottom(page)) - (await edges(messageLog(page))).bottom)
    .toBeLessThanOrEqual(1);
});

test.describe("1440px 화면에서 접었을 때", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("알약은 열릴 패널의 왼쪽 아래, 목록 카드 바닥선에 놓인다", async ({ page, chat }) => {
    // given
    chat.enabled = true;
    await openReadyChat(page, chat.sockets);

    // when
    await sheetHead(page).getByRole("button", { name: "채팅 접기", exact: true }).click();

    // then
    await expect
      .poll(async () => {
        const card = await edges(boardCard(page));
        const pill = await edges(launcher(page));
        return Math.max(Math.abs(pill.left - card.right - CARD_GAP), Math.abs(pill.bottom - card.bottom));
      })
      .toBeLessThanOrEqual(1);
  });
});

test("채팅을 열지 않아도 남이 보낸 새 메시지가 오면 알약에 안 읽은 수와 마지막 메시지가 살짝 보인다", async ({
  page,
  chat,
}) => {
  // given
  chat.enabled = true;
  await openChatRouteBoard(page, 200);
  await waitForLauncher(page);
  const socket = await waitForSessionStart(page, chat.sockets);
  socket.send(serverFrames["session.ready"]);
  sendHistory(socket, history.length);
  await expect(sheet(page)).toHaveAttribute("data-connection", "ready");
  await expect(launcher(page)).not.toContainText(history[0]!.body);
  const incoming = { ...serverFrames["message.created"].message, id: "68db00000000000000009001", authorId: "other" };

  // when
  socket.send({ ...serverFrames["message.created"], message: incoming });

  // then
  const unreadLauncher = page.getByRole("button", {
    name: `${chatAvailability.displayName} 채팅 열기, 안 읽은 메시지 1개`,
    exact: true,
  });
  await expect(unreadLauncher).toBeVisible();
  await expect(unreadLauncher).toContainText(incoming.body);
});

for (const [routeId, displayName] of [
  ["204000070", "9007"],
  ["234000886", "9300"],
  ["233000392", "6011"],
  ["227000038", "3000"],
  ["228000184", "5600"],
  ["234000051", "3500"],
]) {
  test(`${displayName} 노선도 서버 응답으로 채팅을 열고 메시지를 보낸다`, async ({ page, api, chat }) => {
    await page.route("**/api/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      const prefix = `/api/v1/routes/${routeId}`;
      if (path === `/api/chat/rooms/${routeId}`) {
        await route.fulfill({
          status: 200,
          headers: { "Cache-Control": "no-store" },
          json: { ...chatAvailability, routeId, displayName },
        });
      } else if (path === `${prefix}/board` || path === `${prefix}/vehicles`) {
        const now = await page.evaluate(() => Date.now());
        await route.fulfill({
          status: 200,
          headers: { Date: new Date(now).toUTCString(), "Cache-Control": "public, max-age=60" },
          json:
            path === `${prefix}/board`
              ? { ...api.data.board, route: { ...api.data.board.route, id: routeId, displayName } }
              : { ...api.data.vehicles, routeId },
        });
      } else {
        await route.fallback();
      }
    });

    await page.goto(`/routes/${routeId}`);
    const button = page.getByRole("button", { name: `${displayName} 채팅 열기`, exact: true });
    await expect
      .poll(async () => {
        await page.clock.runFor(100);
        return button.count();
      })
      .toBe(1);
    await expect(button).toBeVisible();
    await expect
      .poll(async () => {
        await page.clock.runFor(10);
        return chat.sockets.length;
      })
      .toBe(1);
    const socket = chat.sockets[0]!;
    expect(socket.path).toBe(`/api/chat/rooms/${routeId}/stream`);
    await expect
      .poll(async () => {
        await page.clock.runFor(10);
        return socket.received.length;
      })
      .toBe(1);
    expect(socket.received[0]).toMatchObject({ v: 1, type: "session.start" });
    socket.send(serverFrames["session.ready"]);
    socket.send(serverFrames["history.end"]);
    await expect(sheet(page)).toHaveAttribute("data-connection", "ready");
    await button.click();
    await page.getByRole("textbox", { name: "메시지", exact: true }).fill(sentBody);
    await page.getByRole("button", { name: "메시지 보내기", exact: true }).click();
    await expect.poll(() => socket.received.length).toBe(2);
    const sent = socket.received[1] as { clientMessageId: string; body: string };
    expect(sent.body).toBe(sentBody);
    socket.send({ ...serverFrames["message.ack"], clientMessageId: sent.clientMessageId });
    await expect(page.getByRole("log", { name: `${displayName} 채팅 메시지`, exact: true })).toContainText(sentBody);
    await expect(sheet(page).getByText("보내는 중", { exact: true })).toHaveCount(0);
  });
}
