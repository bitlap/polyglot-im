/** 从已安装 npm 产物连接临时服务；Node 22 适配器只用于验收，不进入小程序包。 */
import fs from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
const configPath = process.argv[2] || ".local-demo/config.json";
const config = JSON.parse(await fs.readFile(configPath, "utf8"));
const entry =
  process.env.POLYGLOT_IM_UNIAPP_ENTRY ||
  path.resolve("uniapp-client/index.js");
const { ImClient, HttpApi, NotificationSender, emptyCache } = await import(
  pathToFileURL(entry)
);
const transport = async ({ url, method, data, header, signal }) => {
  const controller = new AbortController(),
    abort = () => controller.abort();
  signal?.addEventListener("abort", abort);
  const timer = setTimeout(abort, 10000);
  try {
    const response = await fetch(url, {
      method,
      headers: header,
      body: data === undefined ? undefined : JSON.stringify(data),
      signal: controller.signal,
      redirect: "error",
    });
    return {
      statusCode: response.status,
      data: await response.json(),
      header: Object.fromEntries(response.headers),
    };
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", abort);
  }
};
let connections = 0,
  activeSocket;
const socketConnector = ({ url, onOpen, onMessage, onClose, onError }) => {
  connections++;
  const socket = new WebSocket(url);
  activeSocket = socket;
  socket.addEventListener("open", onOpen);
  socket.addEventListener("message", (e) => onMessage(e.data));
  socket.addEventListener("close", onClose);
  socket.addEventListener("error", onError);
  return {
    send: async (data) => socket.send(data),
    close: () => socket.close(),
  };
};
const storage = {
  async load(key) {
    try {
      return JSON.parse(
        await fs.readFile(
          path.join(
            path.dirname(configPath),
            encodeURIComponent(key) + ".json",
          ),
          "utf8",
        ),
      );
    } catch (e) {
      if (e.code !== "ENOENT") throw e;
      return emptyCache();
    }
  },
  async save(key, value) {
    const file = path.join(
      path.dirname(configPath),
      encodeURIComponent(key) + ".json",
    );
    await fs.writeFile(file + ".tmp", JSON.stringify(value));
    await fs.rename(file + ".tmp", file);
  },
  async clear(key) {
    await fs.rm(
      path.join(path.dirname(configPath), encodeURIComponent(key) + ".json"),
      { force: true },
    );
  },
};
const create = (userId, token) =>
  new ImClient({
    baseUrl: config.base_url,
    environment: config.base_url,
    appId: config.app_id,
    userId,
    deviceId: `uniapp-${userId}`,
    tokenProvider: async () => token,
    transport,
    storage,
    socketConnector,
  });
const alice = create(config.user_id, config.user_token),
  bob = create("bob", config.bob_token);
const service = new HttpApi({
  baseUrl: config.base_url,
  tokenProvider: async () => config.service_token,
  transport,
});
const until = async (predicate) => {
  const deadline = Date.now() + 12000;
  while (!predicate()) {
    assert.ok(Date.now() < deadline, "实时状态等待超时");
    await new Promise((r) => setTimeout(r, 20));
  }
};
try {
  await alice.initialize({ realtime: false });
  await bob.initialize();
  await until(
    () =>
      bob.connectionState === "online" &&
      activeSocket?.readyState === WebSocket.OPEN,
  );
  const conversation = await alice.createDirect("bob"),
    cid = conversation.conversation_id;
  const text = "你好 👨‍👩‍👧‍👦 👍🏽",
    clientMessageId = "uniapp-live-chat";
  const first = await alice.sendText(cid, { clientMessageId, text }),
    duplicate = await alice.sendText(cid, { clientMessageId, text });
  assert.equal(first.message_id, duplicate.message_id);
  // 必须通过 WS 更新，等待期间不调用 bob.sync。
  await until(() => !!bob.cache.conversations[cid]?.messages[first.message_id]);
  assert.equal(
    bob.cache.conversations[cid].messages[first.message_id].payload.text,
    text,
  );
  assert.notEqual(bob.chatUnreadCount, "0");
  await bob.markConversationRead(cid, first.conversation_seq);
  assert.equal(bob.chatUnreadCount, "0");
  await alice.sync();
  assert.equal(
    alice.cache.conversations[cid].peer_read_seq,
    first.conversation_seq,
  );
  const history = await alice.conversationHistory(cid, { limit: 10 });
  assert.equal(history.items[0].message_id, first.message_id);
  const list = await alice.conversations();
  assert.ok(list.items.some((c) => c.conversation_id === cid));
  const before = connections;
  activeSocket.close();
  await until(
    () => connections > before && activeSocket.readyState === WebSocket.OPEN,
  );
  bob.pauseRealtime();
  const second = await alice.sendText(cid, {
    clientMessageId: "uniapp-after-pause",
    text: "恢复补拉",
  });
  bob.startRealtime();
  await until(
    () => !!bob.cache.conversations[cid]?.messages[second.message_id],
  );
  const sender = new NotificationSender(service),
    input = {
      recipientId: config.user_id,
      clientMessageId: "uniapp-live-notification",
      type: "task.completed",
      payload: {
        task_id: "uniapp-demo",
        title: "创作完成",
        text: "来自 JS 的通知",
      },
    };
  const n = await sender.send(input);
  assert.equal((await sender.send(input)).message_id, n.message_id);
  await alice.sync();
  assert.equal((await alice.getMessage(n.message_id)).message_id, n.message_id);
  const cursor = alice.cache.cursor;
  await alice.history({ limit: 10 });
  assert.equal(alice.cache.cursor, cursor);
  await alice.markRead();
  assert.equal(alice.unreadCount, "0");
  await alice.setPush(null, { muted: true, preview: false });
  assert.equal((await alice.pushPreferences()).muted, true);
  await alice.updatePushToken(null);
  await alice.close();
  const restored = create(config.user_id, config.user_token);
  await restored.initialize({ realtime: false });
  assert.ok(restored.cache.messages[n.message_id]);
  await restored.close({ logout: true, clearCache: true });
  console.log(
    "PASS: uni-app npm 产物，真实 HTTP/WS 单聊表情、幂等、通知、已读回执、重连、暂停补拉、设备偏好、持久化恢复和退出",
  );
} finally {
  await alice.close();
  await bob.close({ logout: true });
  await service.close();
}
