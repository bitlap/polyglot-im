import { test } from "node:test";
import assert from "node:assert/strict";
import { NotificationClient } from "../src/service/notification_client.js";

/** 使用可控连接和时钟验证资源释放，不访问真实网络或账号。 */
function realtime(t, connector) {
  const sockets = [], errors = [], timers = new Map();
  let nextTimer = 0;
  t.mock.method(globalThis, "setTimeout", (run, delay) => {
    const id = ++nextTimer; timers.set(id, { run, delay }); return id;
  });
  t.mock.method(globalThis, "clearTimeout", id => timers.delete(id));
  const client = new NotificationClient({
    baseUrl: "https://im.test", appId: "app", userId: "user", deviceId: "device", environment: "test",
    socketConnector: options => {
      const socket = { ...options, closes: 0, close() { this.closes++; }, send: async () => {} };
      sockets.push(socket); connector?.(socket); return socket;
    }, onError: error => errors.push(error),
  });
  let syncs = 0;
  client.sync = async () => { syncs++; };
  client.request = async () => ({ app_id: "app", user_id: "user", ticket: "synthetic" });
  t.after(() => client.pauseRealtime());
  return { client, sockets, errors, timers, get syncs() { return syncs; },
    retry() {
      const [id, timer] = [...timers].find(([, value]) => value.delay < 10000) || [];
      assert.ok(timer, "expected reconnect timer"); timers.delete(id); timer.run();
    },
  };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test("实时连接的终态、暂停和恢复", async t => {
  await t.test("远端关闭不再主动关闭，重复关闭事件只安排一次重连", async t => {
    const f = realtime(t);
    f.client.startRealtime(); f.client.startRealtime(); await flush();
    assert.equal(f.sockets.length, 1);
    const old = f.sockets[0]; old.onOpen(); old.onClose(); old.onClose();
    assert.equal(old.closes, 0); assert.equal(f.timers.size, 1);
    assert.equal(f.client.connectionState, "retrying");
    f.retry(); await flush(); assert.equal(f.sockets.length, 2); assert.equal(f.syncs, 2);
    f.sockets[1].onOpen(); old.onError(new Error("late"));
    assert.equal(f.client.connectionState, "online"); assert.equal(f.errors.length, 0);
  });
  await t.test("连接错误清理一次，随后关闭事件不重复清理或重连", async t => {
    const f = realtime(t); f.client.startRealtime(); await flush();
    const socket = f.sockets[0], error = new Error("connect failed");
    socket.onError(error); socket.onClose(); socket.onError(error);
    assert.equal(socket.closes, 1); assert.deepEqual(f.errors, [error]); assert.equal(f.timers.size, 1);
    f.client.pauseRealtime(); assert.equal(f.timers.size, 0); assert.equal(socket.closes, 1);
  });
  await t.test("暂停清理一次并进入 idle，迟到事件不重连，恢复建立新连接", async t => {
    const f = realtime(t); f.client.startRealtime(); await flush();
    const old = f.sockets[0];
    f.client.pauseRealtime(); f.client.pauseRealtime(); old.onError(new Error("late")); old.onClose(); old.onOpen();
    assert.equal(old.closes, 1); assert.equal(f.timers.size, 0); assert.deepEqual(f.errors, []);
    assert.equal(f.client.connectionState, "idle");
    f.client.startRealtime(); await flush(); assert.equal(f.sockets.length, 2);
    f.sockets[1].onOpen(); assert.equal(f.client.connectionState, "online");
  });
  await t.test("同步触发关闭的连接器不再次关闭终态连接", async t => {
    const f = realtime(t, socket => socket.onClose());
    f.client.startRealtime(); await flush();
    assert.equal(f.sockets[0].closes, 0); assert.equal(f.timers.size, 1);
  });
  await t.test("同步报错的连接器返回后只清理一次", async t => {
    const error = new Error("synchronous connection failure");
    const f = realtime(t, socket => socket.onError(error));
    f.client.startRealtime(); await flush();
    assert.equal(f.sockets[0].closes, 1); assert.equal(f.timers.size, 1);
    assert.deepEqual(f.errors, [error]);
  });
  await t.test("超时释放当前连接并只安排一次重连", async t => {
    const f = realtime(t); f.client.startRealtime(); await flush();
    const [id, timer] = [...f.timers].find(([, value]) => value.delay === 10000);
    f.timers.delete(id); timer.run(); f.sockets[0].onError(new Error("late"));
    assert.equal(f.sockets[0].closes, 1); assert.equal(f.timers.size, 1);
    assert.match(f.errors[0].message, /timeout/);
  });
});
