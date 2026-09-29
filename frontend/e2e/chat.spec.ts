import type { Page } from "@playwright/test";
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
import { routeId as nonChatRouteId } from "./fixtures/mock-data";

test.use({
  viewport: { width: 390, height: 844 },
  reducedMotion: "reduce",
  screenshot: "only-on-failure",
  trace: "retain-on-failure",
});

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const history = serverFrames["history.batch"].messages;
const sentBody = clientFrames["message.send"].body;

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

function messages(page: Page) {
  return page
    .getByRole("log", { name: `${chatAvailability.displayName} 채팅 메시지`, exact: true })
    .getByRole("article");
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
  await expect.poll(() => sockets.length).toBe(1);
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

test("채팅 대상이 아닌 노선에서는 켜짐 확인을 요청하지 않는다", async ({ page, api, chat }) => {
  // given
  chat.enabled = true;

  // when
  await page.goto(`/routes/${nonChatRouteId}`);
  await expect(page.getByRole("heading", { name: api.data.board.route.displayName, exact: true })).toBeVisible();
  await settleAfterLoad(page);

  // then
  expect(chat.requests).toEqual([]);
  await expect(chatElements(page)).toHaveCount(0);
  expect(chat.scripts.filter(isChatChunk)).toEqual([]);
  expect(chat.sockets).toEqual([]);
});

test("채팅이 켜져 있으면 알약으로 열어 이력을 받고, 보낸 메시지가 ACK 뒤 확정된다", async ({ page, chat }) => {
  chat.enabled = true;
  let socket!: ChatSocket;

  await test.step("켜짐 응답이 오면 채팅 청크를 받아 알약을 그리지만 WebSocket은 열지 않는다", async () => {
    await openChatRouteBoard(page, 200);
    await waitForLauncher(page);
    await expect(launcher(page)).toBeVisible();
    await expect(sheet(page)).toHaveAttribute("data-position", "collapsed");
    await expect(sheet(page)).toHaveAttribute("data-connection", "idle");
    expect(chat.scripts.filter(isChatChunk)).toHaveLength(1);
    expect(chat.sockets).toEqual([]);
  });

  await test.step("알약을 누르면 절반 시트로 열리고 session.start를 보낸다", async () => {
    await launcher(page).click();
    await expect(sheet(page)).toHaveAttribute("data-position", "half");
    await expect(page.getByRole("dialog", { name: `${chatAvailability.displayName} 채팅`, exact: true })).toBeVisible();
    await expect(launcher(page)).toHaveCount(0);
    await expect(statusBand(page)).toHaveText("대화방에 연결하는 중이에요");
    await expect(page.getByLabel("최근 대화를 불러오는 중", { exact: true })).toBeVisible();
    socket = await waitForSessionStart(page, chat.sockets);
  });

  await test.step("session.ready와 이력을 받으면 내 이름과 이력 두 개가 보인다", async () => {
    socket.send(serverFrames["session.ready"]);
    socket.send(serverFrames["history.batch"]);
    socket.send(serverFrames["history.end"]);

    await expect(sheet(page)).toHaveAttribute("data-connection", "ready");
    await expect(statusBand(page)).toHaveCount(0);
    await expect(sheet(page).getByText(`내 이름 ${serverFrames["session.ready"].nickname}`)).toBeVisible();
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
