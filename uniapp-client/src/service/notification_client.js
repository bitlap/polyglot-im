import { compareSequence, emptyCache } from '../domain/cache.js'

/** 通知同步核心；只依赖注入的传输、存储和凭证端口，账号切换必须关闭旧实例。 */
export class NotificationClient {
    constructor({ baseUrl, appId, userId, environment, deviceId, tokenProvider, refreshToken, transport, storage, onChange = () => {}, onError = () => {} }) {
        this.baseUrl = baseUrl.replace(/\/+$/, '')
        if (!/^https:\/\/[^/?#]+(?:\/[^?#]*)?$/.test(this.baseUrl) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(?:\/[^?#]*)?$/.test(this.baseUrl)) throw new Error('消息服务地址不安全')
        if (!appId || !userId || !deviceId || !environment) throw new Error('Missing account scope')
        Object.assign(this, { appId, userId, deviceId, tokenProvider, refreshToken, transport, storage, onChange, onError })
        this.key = `im.v1.${encodeURIComponent(JSON.stringify([environment, this.baseUrl, appId, userId]))}`
        this.cache = emptyCache(); this.closed = false; this.timer = null; this.syncing = null; this.serial = Promise.resolve()
    }
    /** 绑定预期身份，每次动态读取宿主凭证，拒绝账号切换后的迟到回包。 */
    async request(method, path, data, authRetried = false) {
        if (this.closed) throw new Error('消息账号已关闭')
        const token = await this.tokenProvider()
        if (!token || this.closed) throw new Error('请重新登录')
        const response = await this.transport({ url: `${this.baseUrl}/api/v1/im${path}`, method, data, timeout: 10000,
            header: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`,
                'X-IM-App-ID': encodeURIComponent(this.appId), 'X-IM-User-ID': encodeURIComponent(this.userId) } })
        if (this.closed) throw new Error('消息账号已关闭')
        if (response.statusCode === 401 && this.refreshToken && !authRetried) {
            await this.refreshToken()
            return this.request(method, path, data, true)
        }
        const body = response.data
        if (response.statusCode < 200 || response.statusCode >= 300 || body?.code !== 200) {
            const error = new Error(body?.message || '消息请求失败'); error.status = response.statusCode; error.code = body?.error_code; throw error
        }
        if (!body.data || typeof body.data !== 'object') throw new Error('消息响应格式错误')
        return body.data
    }
    /** 从隔离缓存恢复，校验服务器身份并建立当前设备会话。 */
    async initialize() {
        this.cache = await this.storage.load(this.key)
        if (this.closed) return
        const session = await this.request('POST', '/session', { device_id: this.deviceId })
        if (session.app_id !== this.appId || session.user_id !== this.userId) throw new Error('消息身份不匹配')
        await this.sync()
    }
    /** 串行原子保存整个缓存；落盘失败不推进内存游标或发送 ack。 */
    commit(update) {
        const task = this.serial.catch(() => {}).then(async () => {
            if (this.closed) throw new Error('消息账号已关闭')
            const next = update(JSON.parse(JSON.stringify(this.cache)))
            await this.storage.save(this.key, next)
            if (this.closed) return
            this.cache = next; this.onChange(this)
        })
        this.serial = task; return task
    }
    /** 合并通知增量；忽略单聊，保留独立通知已读水位。 */
    async apply(page, reset = false) {
        await this.commit(current => {
            if (page.event_position && compareSequence(page.event_position, current.event_position) < 0) return current
            const next = reset ? emptyCache() : current
            const messages = [...(page.messages || []), ...(page.events || []).filter(e => e.type === 'message.created').map(e => e.data)]
            for (const message of messages) {
                if (message.type?.startsWith("chat.")) continue
                if (message.schema_version !== 1 || !message.message_id || !/^\d+$/.test(message.conversation_seq)) throw new Error('消息格式错误')
                next.messages[message.message_id] = message
            }
            for (const key of ['cursor', 'event_position', 'latest_seq', 'read_seq', 'unread_count']) if (page[key] !== undefined) next[key] = page[key]
            // 有界缓存；完整历史由服务端分页获取。
            next.messages = Object.fromEntries(Object.values(next.messages).sort((a,b) => compareSequence(b.conversation_seq,a.conversation_seq)).slice(0,500).map(m => [m.message_id,m]))
            return next
        })
    }
    /** 单航班同步，游标失效时恢复快照；仅落盘后确认收取。 */
    sync() {
        if (this.syncing) return this.syncing
        this.syncing = (async () => {
            let page
            do {
                let reset = !this.cache.cursor
                try { page = await this.request('GET', reset ? '/snapshot' : `/sync?cursor=${encodeURIComponent(this.cache.cursor)}`) }
                catch (error) {
                    if (error.code !== 'RESET_REQUIRED') throw error
                    page = await this.request('GET', '/snapshot'); reset = true
                }
                await this.apply(page, reset)
                if (this.cache.cursor) await this.request('PUT', `/devices/${encodeURIComponent(this.deviceId)}/ack`, { cursor: this.cache.cursor })
            } while (page.has_more && !this.closed)
        })().finally(() => { this.syncing = null })
        return this.syncing
    }
    /** 读取历史，不改变同步游标。 */
    async history(beforeSeq) {
        const page = await this.request('GET', `/messages?limit=50${beforeSeq ? `&before_seq=${encodeURIComponent(beforeSeq)}` : ''}`)
        await this.apply({ messages: page.items }); return page
    }
    /** 只把用户已展示的范围设为已读；不会将收取确认当作阅读。 */
    async markRead(sequence) {
        await this.request('PUT', '/read', { up_to_seq: sequence }); await this.sync()
    }
    /** 在前台定时补拉，失败指数退避；后台暂停，避免持续耗电。 */
    startPolling(interval = 20000) {
        this.stopPolling(); const generation = this.pollGeneration; let delay = interval
        const poll = async () => {
            try { await this.sync(); delay = interval } catch (error) { delay = Math.min(delay * 2, 120000); this.onError(error) }
            if (!this.closed && this.polling && generation === this.pollGeneration) this.timer = setTimeout(poll, delay)
        }
        this.polling = true; this.timer = setTimeout(poll, interval)
    }
    /** 暂停轮询，不清除可靠缓存。 */
    stopPolling() { this.pollGeneration = (this.pollGeneration || 0) + 1; this.polling = false; clearTimeout(this.timer); this.timer = null }
    /** 退出前撤销设备会话；撤销失败时保留实例供宿主重试。 */
    async close({ logout = false } = {}) {
        this.stopPolling()
        if (logout && !this.closed) await this.request('DELETE', `/devices/${encodeURIComponent(this.deviceId)}/session`)
        this.closed = true; await this.serial.catch(() => {})
    }
    /** 返回按通知序号倒序排列的缓存。 */
    get messages() { return Object.values(this.cache.messages).sort((a,b) => compareSequence(b.conversation_seq,a.conversation_seq)) }
}
