import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ImClient,
  RetryPolicy,
  NotificationSender,
  applyPage,
  emptyCache,
  uniTransport,
  uniSocketConnector,
  uniStorage,
} from "../index.js";
const success = (data) => ({ statusCode: 200, data: { code: 200, data } });
const message = (id = "m", seq = "1", cid = "chat") => ({
  schema_version: 1,
  message_id: id,
  conversation_id: cid,
  conversation_seq: seq,
  type: "chat.text",
  payload: { text: "你好 👨‍👩‍👧‍👦 👍🏽" },
  created_at: "2026-11-12T00:00:00Z",
});
const page = {
  cursor: "c1",
  event_position: "1",
  latest_seq: "0",
  read_seq: "0",
  chat_unread_count: "1",
  has_more: false,
  conversations: [
    { conversation_id: "chat", revision: "1", messages: [message()] },
  ],
};
const create = (options) =>
  new ImClient({
    baseUrl: "https://im.test",
    environment: "biz",
    appId: "app",
    userId: "alice",
    deviceId: "d",
    tokenProvider: async () => "opaque",
    storage: { load: emptyCache, save() {} },
    retry: new RetryPolicy({ initialDelay: 0, jitter: 0 }),
    ...options,
  });
const flush = () => new Promise((resolve) => setImmediate(resolve));
async function until(predicate) {
  for (let i = 0; i < 100 && !predicate(); i++)
    await new Promise((r) => setTimeout(r, 5));
  assert.ok(predicate());
}
test("大水位、单聊版本、旧历史合并、未知类型和独立未读", () => {
  let cache = applyPage(emptyCache(), {
    ...page,
    latest_seq: "9007199254740993",
    read_seq: "9007199254740990",
    messages: [
      { ...message("n", "9007199254740993", "notice"), type: "future.unknown" },
    ],
  });
  assert.equal(cache.unread_count, "3");
  assert.equal(cache.chat_unread_count, "1");
  cache = applyPage(cache, {
    conversations: [
      { conversation_id: "chat", revision: "0", messages: [message("older")] },
    ],
    read_seq: "1",
  });
  assert.equal(cache.conversations.chat.revision, "1");
  assert.equal(Object.keys(cache.conversations.chat.messages).length, 2);
  assert.equal(cache.read_seq, "9007199254740990");
  assert.throws(
    () =>
      applyPage(cache, {
        conversations: [
          {
            conversation_id: "chat",
            revision: "2",
            messages: [message("bad", "1", "other")],
          },
        ],
      }),
    /mismatch/,
  );
  assert.throws(
    () =>
      applyPage(emptyCache(), {
        messages: [{ ...message(), conversation_seq: 9007199254740993 }],
      }),
    /格式/,
  );
});
test("完整单聊、历史、读回执、通知读取和推送接口保持相同协议", async () => {
  const calls = [];
  const c = create({
    transport: async (r) => {
      calls.push(r);
      const path = r.url.split("/api/v1/im")[1];
      if (
        path === "/conversations/direct" ||
        (path.endsWith("/read") && path.startsWith("/conversations/"))
      )
        return success(page.conversations[0]);
      if (path.startsWith("/conversations?"))
        return success({ items: page.conversations });
      if (path.startsWith("/conversations/chat/messages?"))
        return success({
          items: [message()],
          conversation: page.conversations[0],
        });
      if (path === "/conversations/chat/messages" || path === "/messages/m")
        return success(message());
      if (path.includes("/preferences"))
        return success({ muted: true, preview: false });
      if (path.startsWith("/messages?"))
        return success({
          items: [{ ...message("n", "1", "notice"), type: "future" }],
        });
      return success(
        path.endsWith("/ack") ||
          path.includes("/push-token") ||
          path === "/read"
          ? { ok: true }
          : page,
      );
    },
  });
  await c.createDirect("bob");
  await c.conversations({ after: "x/y", limit: 20 });
  await c.conversationHistory("chat", { beforeSeq: "9007199254740993" });
  await c.sendText("chat", { clientMessageId: "stable", text: "你好 👨‍👩‍👧‍👦 👍🏽" });
  await c.markConversationRead("chat", "1");
  assert.deepEqual(await c.getMessage("m"), message());
  await c.history({ beforeSeq: "2", limit: 10 });
  assert.equal(c.cache.cursor, "c1");
  await c.markRead();
  await c.updatePushToken("native-token");
  assert.equal((await c.pushPreferences()).muted, true);
  await c.setPush(null, { muted: true });
  assert.equal(c.chatUnreadCount, "1");
  assert.equal(c.unreadCount, "0");
  const send = calls.find((r) => r.data?.client_message_id);
  assert.equal(send.data.payload.text, "你好 👨‍👩‍👧‍👦 👍🏽");
  assert.ok(calls.some((r) => r.url.includes("after=x%2Fy")));
  await assert.rejects(c.sendText("chat", { text: "x" }), /stable/);
  await c.close();
});
test("重试冻结请求内容、遵循 Retry-After，403/冲突不重试，401并发合并刷新", async () => {
  let attempts = 0,
    refreshes = 0;
  const bodies = [],
    delays = [];
  const c = create({
    refreshToken: async () => {
      refreshes++;
      await flush();
    },
    transport: async (r) => {
      attempts++;
      bodies.push(structuredClone(r.data));
      r.data.value = "mutated";
      if (attempts === 1)
        return { statusCode: 503, header: { "Retry-After": "0.01" } };
      return success({ ok: true });
    },
  });
  c.wait = async (ms) => delays.push(ms);
  await c.request("POST", "/notifications", {
    value: "frozen",
    client_message_id: "same",
  });
  assert.deepEqual(bodies[0], bodies[1]);
  assert.equal(delays[0], 10);
  let token = "old";
  c.tokenProvider = async () => token;
  c.refreshToken = async () => {
    refreshes++;
    await flush();
    token = "new";
  };
  c.transport = async (r) =>
    r.header.Authorization === "Bearer old"
      ? { statusCode: 401 }
      : success({ ok: true });
  await Promise.all([
    c.request("GET", "/snapshot"),
    c.request("GET", "/snapshot"),
  ]);
  assert.equal(refreshes, 1);
  for (const status of [403, 409]) {
    let n = 0;
    c.transport = async () => {
      n++;
      return { statusCode: status };
    };
    await assert.rejects(c.request("GET", "/snapshot"));
    assert.equal(n, 1);
  }
  await c.close();
});
test("关闭取消重试等待和默认 uni.request；借入传输不被关闭", async () => {
  let transportCloses = 0;
  const transport = async () => ({ statusCode: 503 });
  transport.close = () => transportCloses++;
  const c = create({
    transport,
    retry: new RetryPolicy({ initialDelay: 30000, jitter: 0 }),
  });
  const waiting = c.request("GET", "/snapshot");
  await flush();
  await c.close();
  await assert.rejects(waiting, /关闭/);
  assert.equal(transportCloses, 0);
  let aborted = 0;
  globalThis.uni = { request: () => ({ abort: () => aborted++ }) };
  const active = create({ transport: uniTransport });
  const pending = active.request("GET", "/snapshot");
  await flush();
  await active.close();
  await assert.rejects(pending);
  assert.equal(aborted, 1);
  const owned = create({ transport, ownsTransport: true });
  await owned.close();
  assert.equal(transportCloses, 1);
  delete globalThis.uni;
});
test("实时落盘后 ack、重复启动单连接、暂停丢弃旧帧、恢复 HTTP 补拉", async () => {
  const sessions = [],
    sent = [],
    saved = [];
  let requests = 0;
  const c = create({
    storage: {
      load: emptyCache,
      save: (_, v) => saved.push(structuredClone(v)),
    },
    transport: async (r) => {
      requests++;
      return success(
        r.url.endsWith("/session")
          ? { app_id: "app", user_id: "alice", ticket: "short-lived" }
          : r.url.endsWith("/ack")
            ? { ok: true }
            : page,
      );
    },
    socketConnector: (options) => {
      const s = {
        ...options,
        send: async (raw) => {
          assert.equal(saved.at(-1).cursor, JSON.parse(raw).cursor);
          sent.push(raw);
        },
        close() {},
      };
      sessions.push(s);
      return s;
    },
  });
  await c.initialize();
  c.startRealtime();
  await until(() => sessions.length === 1);
  assert.ok(sessions[0].url.startsWith("wss://im.test/"));
  assert.ok(!sessions[0].url.includes("opaque"));
  sessions[0].onOpen();
  sessions[0].onMessage(
    JSON.stringify({
      type: "sync",
      data: { ...page, cursor: "c2", event_position: "2" },
    }),
  );
  await until(() => sent.length === 1);
  assert.equal(c.cache.cursor, "c2");
  c.pauseRealtime();
  sessions[0].onMessage(
    JSON.stringify({
      type: "sync",
      data: { ...page, cursor: "late", event_position: "3" },
    }),
  );
  await flush();
  assert.equal(c.cache.cursor, "c2");
  const before = requests;
  c.startRealtime();
  await until(() => sessions.length === 2);
  assert.ok(requests > before);
  sessions[1].onClose();
  await until(() => sessions.length === 3);
  await c.close();
});
test("实时存储配额失败不确认；拥塞关闭后通过 HTTP 恢复", async () => {
  let socket,
    acks = 0,
    fail = false,
    closes = 0;
  const errors = [];
  const c = create({
    onError: (e) => errors.push(e),
    storage: {
      load: emptyCache,
      save() {
        if (fail) throw new Error("quota");
      },
    },
    transport: async (r) =>
      success(
        r.url.endsWith("/session")
          ? { app_id: "app", user_id: "alice", ticket: "t" }
          : r.url.endsWith("/ack")
            ? {}
            : page,
      ),
    socketConnector: (options) => {
      socket = {
        ...options,
        send: () => {
          acks++;
        },
        close: () => {
          closes++;
        },
      };
      return socket;
    },
  });
  await c.initialize();
  await until(() => !!socket);
  fail = true;
  socket.onMessage(
    JSON.stringify({
      type: "sync",
      data: { ...page, cursor: "lost", event_position: "2" },
    }),
  );
  await until(() => errors.length === 1);
  assert.equal(acks, 0);
  assert.equal(c.cache.cursor, "c1");
  assert.ok(closes > 0);
  c.pauseRealtime();
  fail = false;
  c.startRealtime();
  const old = socket;
  await until(() => socket !== old);
  for (let i = 0; i < 33; i++)
    socket.onMessage(JSON.stringify({ type: "sync", data: page }));
  await until(() => errors.length === 2);
  assert.match(errors[1].message, /overflow/);
  await c.close();
});
test("uni SocketTask 适配器与存储配额错误直接传播", async () => {
  const handlers = {},
    values = {};
  let wire,
    closed = false;
  globalThis.uni = {
    connectSocket: () => ({
      onOpen: (f) => (handlers.open = f),
      onMessage: (f) => (handlers.message = f),
      onError: (f) => (handlers.error = f),
      onClose: (f) => (handlers.close = f),
      send: (o) => {
        wire = o.data;
        o.success();
      },
      close: () => {
        closed = true;
      },
    }),
    getStorageSync: (k) => values[k],
    setStorageSync: (k, v) => {
      values[k] = v;
    },
    removeStorageSync: (k) => delete values[k],
  };
  let received;
  const socket = uniSocketConnector({
    url: "wss://im.test",
    onOpen() {},
    onMessage: (v) => (received = v),
    onError() {},
    onClose() {},
  });
  handlers.message({ data: "frame" });
  assert.equal(received, "frame");
  await socket.send("ack");
  assert.equal(wire, "ack");
  socket.close();
  assert.ok(closed);
  uniStorage.save("x", page);
  assert.equal(uniStorage.load("x"), page);
  uniStorage.clear("x");
  assert.equal(uniStorage.load("x").cursor, null);
  uni.setStorageSync = () => {
    throw new Error("quota");
  };
  assert.throws(() => uniStorage.save("x", page), /quota/);
  delete globalThis.uni;
});
test("可信发送端复用服务 API 及稳定业务幂等键", async () => {
  let request;
  const sender = new NotificationSender({
    request: async (...args) => {
      request = args;
      return message();
    },
  });
  await sender.send({
    recipientId: "alice",
    clientMessageId: "event",
    type: "task.completed",
    payload: { text: "done" },
  });
  assert.equal(request[1], "/notifications");
  assert.equal(request[2].client_message_id, "event");
  await assert.rejects(sender.send({ recipientId: "alice" }), /stable/);
});

test("四种语言共用消息、单聊、审核和 HTTP 信封样例", async () => {
  const { readFile } = await import("node:fs/promises");
  const { validateMessage } = await import("../index.js");
  const fixture = async (name) =>
    JSON.parse(
      await readFile(
        new URL(`../../protocol/fixtures/${name}.json`, import.meta.url),
        "utf8",
      ),
    );
  for (const name of ["message", "direct-message", "review-message"]) {
    const value = await fixture(name);
    assert.deepEqual(validateMessage(JSON.parse(JSON.stringify(value))), value);
  }
  const ok = await fixture("http-success"),
    error = await fixture("http-error");
  const c = create({ transport: async () => ({ statusCode: 200, data: ok }) });
  assert.deepEqual(await c.request("GET", "/messages/example"), ok.data);
  c.transport = async () => ({ statusCode: 409, data: error });
  await assert.rejects(
    c.request("GET", "/snapshot"),
    (e) => e.code === error.error_code,
  );
  for (const path of [
    "//other.test",
    "/../session",
    "/%2e%2e/session",
    "/bad\\path",
  ])
    await assert.rejects(c.request("GET", path), /Invalid API path/);
  await c.close();
});

test("暂停等待中的实时启动不再创建后台会话", async () => {
  let release,
    sessions = 0,
    sockets = 0;
  const c = create({
    transport: async (r) => {
      if (r.url.endsWith("/session")) {
        sessions++;
        return success({ app_id: "app", user_id: "alice", ticket: "t" });
      }
      if (r.url.endsWith("/ack")) return success({});
      return new Promise((resolve) => (release = () => resolve(success(page))));
    },
    socketConnector: () => {
      sockets++;
      throw new Error("unexpected");
    },
  });
  c.startRealtime();
  await flush();
  c.pauseRealtime();
  release();
  await c.sync();
  await flush();
  assert.equal(sessions, 0);
  assert.equal(sockets, 0);
  await c.close();
});
