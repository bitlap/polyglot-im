/** 比较非负十进制序号，不转 Number，避免 64 位 ID 精度丢失。 */
export function compareSequence(a, b) {
    if (!/^\d+$/.test(a) || !/^\d+$/.test(b)) throw new Error('Invalid sequence')
    a = a.replace(/^0+(?=\d)/, ''); b = b.replace(/^0+(?=\d)/, '')
    return a.length - b.length || (a === b ? 0 : a > b ? 1 : -1)
}
export const emptyCache = () => ({ messages: {}, cursor: null, event_position: '0', latest_seq: '0', read_seq: '0', unread_count: '0' })
