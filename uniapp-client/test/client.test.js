import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { ImClient, compareSequence } from "../index.js";
const fixture = JSON.parse(
  fs.readFileSync(
    new URL("../../protocol/fixtures/sync.json", import.meta.url),
  ),
);
const success = (data) => ({
  statusCode: 200,
  data: { code: 200, message: "success", data },
});
const create = (options) =>
  new ImClient({
    baseUrl: "https://im.example.test",
    environment: "https://business.example.test",
    appId: "app",
    userId: "alice",
    deviceId: "device",
    tokenProvider: async () => "opaque",
    ...options,
  });
test("共享大序号样例、原子落盘后 ack、单航班同步及凭证轮换", async () => {
  let saved,
    token = "first",
    syncs = 0;
  const headers = [];
  const client = create({
    tokenProvider: async () => token,
    storage: {
      load: () => ({ messages: {}, event_position: "0" }),
      save: (_, value) => {
        saved = structuredClone(value);
      },
    },
    transport: async (request) => {
      headers.push(request.header);
      if (request.url.endsWith("/ack")) {
        assert.equal(saved.cursor, fixture.cursor);
        return success({ ok: true });
      }
      syncs++;
      return success(fixture);
    },
  });
  await Promise.all([client.sync(), client.sync()]);
  assert.equal(syncs, 1);
  assert.equal(client.messages[0].conversation_seq, "9007199254740993");
  token = "rotated";
  await client.sync();
  assert.equal(headers.at(-1).Authorization, "Bearer rotated");
  assert.equal(compareSequence("9007199254740993", "9007199254740992"), 1);
  assert.equal(saved.read_seq, fixture.read_seq);
  await client.close();
});
test("落盘失败不确认收取，也不推进内存游标", async () => {
  let acks = 0;
  const client = create({
    storage: {
      save: () => {
        throw new Error("quota");
      },
    },
    transport: async (request) => {
      if (request.url.endsWith("/ack")) acks++;
      return success(fixture);
    },
  });
  await assert.rejects(client.sync(), /quota/);
  assert.equal(acks, 0);
  assert.equal(client.cache.cursor, null);
});
test("账号隔离和关闭后迟到回包不会写入新账号", async () => {
  let finish,
    writes = 0;
  const client = create({
    storage: { save: () => writes++ },
    transport: () => new Promise((resolve) => (finish = resolve)),
  });
  const task = client.sync();
  await new Promise((resolve) => setImmediate(resolve));
  await client.close();
  finish(success(fixture));
  await assert.rejects(task);
  assert.equal(writes, 0);
  assert.notEqual(client.key, create({ userId: "bob" }).key);
  assert.notEqual(
    client.key,
    create({ environment: "https://staging.test" }).key,
  );
});
test("401、游标重置、历史分页不推进增量游标", async () => {
  let reset = false;
  const client = create({
    storage: { save() {} },
    transport: async (request) => {
      if (request.url.includes("/sync?") && !reset) {
        reset = true;
        return { statusCode: 410, data: { error_code: "RESET_REQUIRED" } };
      }
      if (request.url.includes("/messages?"))
        return success({ items: [], has_more: false });
      if (request.url.endsWith("/ack")) return success({ ok: true });
      return success(fixture);
    },
  });
  client.cache.cursor = "stale";
  await client.sync();
  assert.equal(reset, true);
  await client.history("9007199254740993");
  assert.equal(client.cache.cursor, fixture.cursor);
  client.transport = async () => ({
    statusCode: 401,
    data: { error_code: "UNAUTHORIZED", message: "expired" },
  });
  await assert.rejects(client.sync(), (error) => error.status === 401);
});

test("401 最多刷新一次且不改变预期账号", async () => {
  let refreshes = 0,
    calls = 0;
  const client = create({
    refreshToken: async () => {
      refreshes++;
    },
    transport: async (request) => {
      calls++;
      assert.equal(request.header["X-IM-User-ID"], "alice");
      return { statusCode: 401, data: { error_code: "UNAUTHORIZED" } };
    },
  });
  await assert.rejects(
    client.request("PUT", "/read", { up_to_seq: "1" }),
    (error) => error.status === 401,
  );
  assert.equal(refreshes, 1);
  assert.equal(calls, 2);
});


test("已读水位覆盖缓存与历史补页，不清除新通知", async () => {
  const { applyPage, emptyCache } = await import("../src/domain/cache.js");
  const message = fixture.events[0].data;
  let state = applyPage(emptyCache(), structuredClone(fixture));
  state = applyPage(state, {read_seq: message.conversation_seq, unread_count: "0"});
  assert.equal(state.messages[message.message_id].is_read, true);
  state = applyPage(state, {messages: [message,
    {...message, message_id: "older", conversation_seq: "9007199254740992", is_read: false},
    {...message, message_id: "newer", conversation_seq: "9007199254740994", is_read: false}], unread_count: "1"});
  assert.equal(state.messages[message.message_id].is_read, true);
  assert.equal(state.messages.older.is_read, true);
  assert.equal(state.messages.newer.is_read, false);
  assert.equal(state.unread_count, "1");
});
