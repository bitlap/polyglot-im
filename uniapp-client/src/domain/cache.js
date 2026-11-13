/** 比较十进制序号，始终保留字符串，兼容没有 BigInt 的小程序引擎。 */
export function compareSequence(a, b) {
  if (
    typeof a !== "string" ||
    typeof b !== "string" ||
    !/^\d+$/.test(a) ||
    !/^\d+$/.test(b)
  )
    throw new Error("Invalid sequence");
  a = a.replace(/^0+(?=\d)/, "");
  b = b.replace(/^0+(?=\d)/, "");
  return a.length - b.length || (a === b ? 0 : a > b ? 1 : -1);
}
/** 精确计算非负序号差，不经过 Number。 */
export function subtractSequence(a, b) {
  if (compareSequence(a, b) < 0) throw new Error("Invalid read watermark");
  const digits = a.split("");
  let borrow = 0;
  for (let i = digits.length - 1, j = b.length - 1; i >= 0; i--, j--) {
    let n = Number(digits[i]) - Number(j >= 0 ? b[j] : 0) - borrow;
    borrow = n < 0 ? 1 : 0;
    digits[i] = String(n < 0 ? n + 10 : n);
  }
  return digits.join("").replace(/^0+(?=\d)/, "");
}
/** 校验公共消息信封；未知消息类型和 payload 原样保留。 */
export function validateMessage(value) {
  if (
    !value ||
    value.schema_version !== 1 ||
    typeof value.message_id !== "string" ||
    !value.message_id ||
    typeof value.conversation_id !== "string" ||
    !value.conversation_id ||
    typeof value.type !== "string" ||
    !value.type ||
    typeof value.conversation_seq !== "string" ||
    !/^[1-9]\d*$/.test(value.conversation_seq) ||
    typeof value.created_at !== "string" ||
    !value.created_at.endsWith("Z") ||
    !Number.isFinite(Date.parse(value.created_at))
  )
    throw new Error("消息格式错误");
  if (["is_read", "is_deleted"].some(key => value[key] !== undefined && typeof value[key] !== "boolean") ||
      (value.state_version !== undefined && (typeof value.state_version !== "string" || !/^[0-9]+$/.test(value.state_version))))
    throw new Error("Invalid notification state");
  return value;
}
/** 新账号初始缓存；通知和单聊分别统计。 */
export const emptyCache = () => ({
  messages: {},
  conversations: {},
  cursor: null,
  event_position: "0",
  latest_seq: "0",
  read_seq: "0",
  unread_count: "0",
  chat_unread_count: "0",
});
/** 合并增量或历史，旧会话版本可补齐历史消息但不能回退摘要。 */
export function applyPage(current, page, reset = false) {
  const position = page.history_position ?? page.event_position;
  if (
    position !== undefined &&
    compareSequence(position, current.event_position) < 0
  )
    return current;
  const next = reset ? emptyCache() : current;
  const events = page.events || [];
  for (const message of [
    ...(page.messages || []),
    ...events.filter((e) => ["message.created", "notification.updated"].includes(e.type)).map((e) => e.data),
  ]) {
    validateMessage(message);
    const previous = next.messages[message.message_id];
    if (compareSequence(previous?.state_version ?? "0", message.state_version ?? "0") <= 0)
      next.messages[message.message_id] = message;
  }
  next.conversations ||= {};
  for (const patch of [
    ...(page.conversations || []),
    ...events
      .filter((e) => e.type === "conversation.updated")
      .map((e) => e.data),
  ]) {
    if (typeof patch.conversation_id !== "string" || !patch.conversation_id)
      throw new Error("Invalid conversation");
    const previous = next.conversations[patch.conversation_id] || {
      revision: "0",
      messages: {},
    };
    const messages = { ...previous.messages };
    for (const message of patch.messages || []) {
      validateMessage(message);
      if (message.conversation_id !== patch.conversation_id)
        throw new Error("message conversation mismatch");
      messages[message.message_id] = message;
    }
    const metadata =
      compareSequence(previous.revision, patch.revision) > 0 ? previous : patch;
    next.conversations[patch.conversation_id] = { ...metadata, messages };
  }
  for (const key of [
    "cursor",
    "event_position",
    "latest_seq",
    "read_seq",
    "unread_count",
    "chat_unread_count",
    "conversation_id",
  ]) {
    if (page[key] === undefined) continue;
    if (
      !reset &&
      ["latest_seq", "read_seq"].includes(key) &&
      compareSequence(next[key], page[key]) > 0
    )
      continue;
    next[key] = page[key];
  }
  if (page.unread_count === undefined && (page.latest_seq !== undefined || page.read_seq !== undefined))
    next.unread_count = subtractSequence(next.latest_seq, next.read_seq);
  return next;
}
