import { test } from "node:test";
import assert from "node:assert/strict";
import { uniSocketConnector } from "../src/adapters/uni.js";

/** 平台替身只记录原生调用；状态转换仍由真实 SDK 适配器完成。 */
function platform(t) {
  const previous = globalThis.uni;
  const handlers = {}, calls = [], events = [];
  let closeError;
  globalThis.uni = { connectSocket: () => ({
    onOpen: f => { handlers.open = f; },
    onClose: f => { handlers.close = f; },
    onError: f => { handlers.error = f; },
    onMessage: f => { handlers.message = f; },
    close: options => { calls.push(options); if (closeError) throw closeError; },
    send: options => { events.push(["send", options.data]); options.success(); },
  }) };
  t.after(() => { globalThis.uni = previous; });
  const socket = uniSocketConnector({ url: "wss://im.test",
    onOpen: () => events.push(["open"]),
    onMessage: value => events.push(["message", value]),
    onClose: () => events.push(["close"]),
    onError: error => events.push(["error", error]),
  });
  return { socket, handlers, calls, events, failClose: error => { closeError = error; } };
}

test("SocketTask 连接状态与关闭资源所有权", async t => {
  await t.test("打开后收发消息，关闭只调用一次且包含显式关闭参数", async t => {
    const f = platform(t);
    f.handlers.open();
    await f.socket.send("ack");
    f.handlers.message({ data: "frame" });
    f.socket.close(); f.socket.close();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].code, 1000);
    assert.equal(typeof f.calls[0].reason, "string");
    assert.ok(f.calls[0].reason.length > 0);
    assert.deepEqual(f.events, [["open"], ["send", "ack"], ["message", "frame"]]);
    await assert.rejects(f.socket.send("late"), /not open/i);
  });
  for (const terminal of ["close", "error"]) {
    await t.test(`${terminal} 后不再关闭原生任务，忽略重复事件和迟到消息`, async t => {
      const f = platform(t), event = { errMsg: "connection failed" };
      f.handlers[terminal](event);
      f.socket.close(); f.socket.close();
      f.handlers[terminal](event); f.handlers.open(); f.handlers.message({ data: "late" });
      assert.equal(f.calls.length, 0);
      assert.equal(f.events.length, 1);
      assert.equal(f.events[0][0], terminal);
      await assert.rejects(f.socket.send("late"), /not open/i);
    });
  }
  await t.test("连接中取消先等待打开，迟到打开只关闭一次且不通知业务", async t => {
    const f = platform(t);
    await assert.rejects(f.socket.send("early"), /not open/i);
    f.socket.close(); f.socket.close();
    assert.equal(f.calls.length, 0);
    f.handlers.open(); f.handlers.open();
    assert.equal(f.calls.length, 1);
    assert.deepEqual(f.events, []);
    f.handlers.close(); f.handlers.close();
    assert.deepEqual(f.events, [["close"]]);
  });
  await t.test("连接中取消后失败，无须调用原生关闭", async t => {
    const f = platform(t);
    f.socket.close(); f.handlers.error({ errMsg: "domain denied" }); f.handlers.close();
    assert.equal(f.calls.length, 0);
    assert.equal(f.events.length, 1);
    assert.equal(f.events[0][0], "error");
  });
  await t.test("关闭后的消息不再写入业务，原生关闭抛错只报告一次", async t => {
    const f = platform(t), error = new Error("native close failed");
    f.handlers.open(); f.failClose(error);
    assert.doesNotThrow(() => f.socket.close());
    f.socket.close(); f.handlers.message({ data: "late" }); f.handlers.error(error);
    assert.equal(f.calls.length, 1);
    assert.deepEqual(f.events, [["open"], ["error", error]]);
  });
});
