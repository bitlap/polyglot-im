import 'dart:async';
import 'package:flutter/material.dart';
import 'package:polyglot_im/polyglot_im.dart';

/// 通知列表；宿主负责已认证客户端生命周期和资源权限检查后的业务导航。
class ImInbox extends StatefulWidget {
  /// 绑定当前账号客户端与消息打开回调；资源权限及业务导航由 onOpen 处理。
  const ImInbox({
    super.key,
    required this.client,
    required this.onOpen,
    this.markReadOnOpen = true,
    this.selectionMode = false,
    this.onSelectionDone,
  });

  /// 宿主控制是否多选；每次最多选中 100 条。
  final bool selectionMode;
  final VoidCallback? onSelectionDone;

  /// 打开回调成功后只标记当前通知；宿主自行处理阅读时可禁用。
  final bool markReadOnOpen;
  final SyncClient client;
  final Future<void> Function(ImMessage) onOpen;

  /// 为此组件创建独立状态，订阅和资源由状态生命周期管理。
  @override
  State<ImInbox> createState() => _ImInboxState();
}

/// 维护通知列表订阅、加载状态和组件生命周期。
class _ImInboxState extends State<ImInbox> {
  StreamSubscription<JsonMap>? _subscription;
  bool _loading = true, _more = true, _paging = false;
  String? _before;
  Object? _error;
  final _selected = <String>{};
  bool _busy = false;

  /// 读取此组件当前绑定的账号客户端。
  SyncClient get client => widget.client;

  /// 挂载页面状态、监听客户端缓存并启动首屏读取。
  @override
  void initState() {
    super.initState();
    _subscription = client.changes.listen((_) {
      if (mounted) setState(() {});
    });
    unawaited(_refresh());
  }

  /// 客户端或会话变化时解绑旧订阅，忽略旧请求并加载新状态。
  @override
  void didUpdateWidget(covariant ImInbox oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectionMode != widget.selectionMode) _selected.clear();
    if (oldWidget.client != client) {
      _selected.clear();
      _busy = false;
      unawaited(_subscription?.cancel());
      _subscription = client.changes.listen((_) {
        if (mounted) setState(() {});
      });
      _before = null;
      _more = true;
      unawaited(_refresh());
    }
  }

  Future<void> _refresh() async {
    final active = client;
    try {
      await active.sync();
      final page = await active.history();
      if (mounted && identical(client, active)) {
        setState(() {
          _error = null;
          _more = page['has_more'] == true;
          _before = page['next_before_seq'] as String?;
        });
      }
    } catch (error) {
      if (mounted && identical(client, active)) setState(() => _error = error);
    } finally {
      if (mounted && identical(client, active)) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _next() async {
    if (_paging || !_more) return;
    final active = client;
    setState(() => _paging = true);
    try {
      final page = await active.history(beforeSeq: _before);
      if (mounted && identical(client, active)) {
        setState(() {
          _more = page['has_more'] == true;
          _before = page['next_before_seq'] as String?;
          _error = null;
        });
      }
    } catch (error) {
      if (mounted && identical(client, active)) setState(() => _error = error);
    } finally {
      if (mounted && identical(client, active)) setState(() => _paging = false);
    }
  }

  Future<void> _readAll() async {
    try {
      await client.markRead();
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  Future<void> _open(ImMessage message) async {
    final active = client;
    try {
      await widget.onOpen(message);
    } catch (_) {
      if (mounted && identical(client, active)) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('内容不存在或暂时无法访问')));
      }
      return;
    }
    if (mounted && identical(client, active) && widget.markReadOnOpen) {
      try {
        await active.markMessagesRead([message.messageId]);
      } catch (_) {
        if (mounted && identical(client, active)) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('标记已读失败，请重试')));
        }
      }
    }
  }

  void _toggle(String id) {
    if (_busy) return;
    if (!_selected.contains(id) && _selected.length >= 100) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('每次最多选择 100 条消息')));
      return;
    }
    setState(() {
      if (!_selected.remove(id)) _selected.add(id);
    });
  }

  Future<void> _operate(List<String> ids, bool delete) async {
    if (_busy || ids.isEmpty) return;
    final active = client;
    setState(() => _busy = true);
    try {
      if (delete) {
        await active.deleteMessages(ids);
      } else {
        await active.markMessagesRead(ids);
      }
      if (mounted && identical(client, active)) {
        setState(() => _selected.clear());
        if (widget.selectionMode) widget.onSelectionDone?.call();
      }
    } catch (_) {
      if (mounted && identical(client, active)) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            behavior: SnackBarBehavior.floating,
            margin: EdgeInsets.fromLTRB(
              16,
              0,
              16,
              widget.selectionMode ? 88 : 16,
            ),
            content: Text(delete ? '删除失败，请重试' : '标记已读失败，请重试'),
          ),
        );
      }
    } finally {
      if (mounted && identical(client, active)) setState(() => _busy = false);
    }
  }

  /// 解除订阅并释放本组件持有的资源，迟到回调不再更新界面。
  @override
  void dispose() {
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  /// 根据当前缓存、加载与错误状态构建页面，不直接执行数据库操作。
  @override
  Widget build(BuildContext context) {
    final messages = client.messages;
    final read = BigInt.parse('${client.cache['read_seq']}');
    _selected.retainAll(messages.map((m) => m.messageId));
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  widget.selectionMode
                      ? '已选择 ${_selected.length} 条'
                      : '未读 ${client.unreadCount}',
                ),
              ),
              if (widget.selectionMode)
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => setState(() {
                          final ids = messages
                              .take(100)
                              .map((m) => m.messageId)
                              .toSet();
                          if (_selected.containsAll(ids)) {
                            _selected.clear();
                          } else {
                            _selected
                              ..clear()
                              ..addAll(ids);
                          }
                        }),
                  child: Text(
                    _selected.isNotEmpty &&
                            _selected.length == messages.take(100).length
                        ? '取消全选'
                        : '全选',
                  ),
                )
              else
                TextButton(
                  onPressed: _busy ? null : _readAll,
                  child: const Text('全部已读'),
                ),
            ],
          ),
        ),
        if (_error != null)
          MaterialBanner(
            content: const Text('消息加载失败，已保留本地内容'),
            actions: [TextButton(onPressed: _refresh, child: const Text('重试'))],
          ),
        Expanded(
          child: _loading && messages.isEmpty
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: messages.isEmpty
                        ? 1
                        : messages.length + (_more ? 1 : 0),
                    itemBuilder: (context, index) {
                      if (messages.isEmpty) {
                        return const Padding(
                          padding: EdgeInsets.all(48),
                          child: Center(child: Text('暂无消息')),
                        );
                      }
                      if (index == messages.length) {
                        return TextButton(
                          onPressed: _paging ? null : _next,
                          child: Text(_paging ? '加载中…' : '加载更多'),
                        );
                      }
                      final message = messages[index];
                      final unread =
                          BigInt.parse(message.conversationSeq) > read &&
                          !message.isRead;
                      final tile = _NotificationTile(
                        message: message,
                        unread: unread,
                        onTap: () {
                          if (!_busy) {
                            if (widget.selectionMode) {
                              _toggle(message.messageId);
                            } else {
                              unawaited(_open(message));
                            }
                          }
                        },
                      );
                      if (widget.selectionMode) {
                        return Row(
                          children: [
                            Checkbox(
                              value: _selected.contains(message.messageId),
                              onChanged: _busy
                                  ? null
                                  : (_) => _toggle(message.messageId),
                            ),
                            Expanded(child: tile),
                          ],
                        );
                      }
                      return _NotificationActions(
                        key: ValueKey((client, message.messageId)),
                        enabled: !_busy,
                        unread: unread,
                        onRead: () => _operate([message.messageId], false),
                        onDelete: () => _operate([message.messageId], true),
                        child: tile,
                      );
                    },
                  ),
                ),
        ),
        if (widget.selectionMode)
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _busy || _selected.isEmpty
                          ? null
                          : () => _operate(_selected.toList(), false),
                      child: Text('已读（${_selected.length}）'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: _busy || _selected.isEmpty
                          ? null
                          : () => _operate(_selected.toList(), true),
                      child: Text(_busy ? '处理中…' : '删除（${_selected.length}）'),
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

/// 水平手势只展开操作区；远端成功后才由同步状态移除列表项。
class _NotificationActions extends StatefulWidget {
  const _NotificationActions({
    super.key,
    required this.child,
    required this.enabled,
    required this.unread,
    required this.onRead,
    required this.onDelete,
  });
  final Widget child;
  final bool enabled, unread;
  final VoidCallback onRead, onDelete;
  @override
  State<_NotificationActions> createState() => _NotificationActionsState();
}

/// 保持每条消息的展开状态，垂直滚动仍由列表处理。
class _NotificationActionsState extends State<_NotificationActions> {
  bool _open = false;
  double _drag = 0;
  @override
  Widget build(BuildContext context) => ClipRect(
    child: Stack(
      children: [
        if (_open)
          Positioned.fill(
            child: Padding(
              padding: const EdgeInsets.only(right: 16, top: 6, bottom: 6),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  SizedBox(
                    width: 72,
                    child: TextButton(
                      onPressed: widget.enabled && widget.unread
                          ? () {
                              setState(() => _open = false);
                              widget.onRead();
                            }
                          : null,
                      child: const Text('已读', maxLines: 1),
                    ),
                  ),
                  SizedBox(
                    width: 72,
                    child: TextButton(
                      onPressed: widget.enabled
                          ? () {
                              setState(() => _open = false);
                              widget.onDelete();
                            }
                          : null,
                      style: TextButton.styleFrom(
                        foregroundColor: Theme.of(context).colorScheme.error,
                      ),
                      child: const Text('删除'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        GestureDetector(
          onHorizontalDragStart: widget.enabled ? (_) => _drag = 0 : null,
          onHorizontalDragUpdate: widget.enabled
              ? (event) => _drag += event.delta.dx
              : null,
          onHorizontalDragEnd: widget.enabled
              ? (event) {
                  if (_drag.abs() > 20 ||
                      (event.primaryVelocity ?? 0).abs() > 300) {
                    setState(() => _open = _drag < 0);
                  }
                }
              : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 160),
            transform: Matrix4.translationValues(_open ? -144 : 0, 0, 0),
            child: widget.child,
          ),
        ),
      ],
    ),
  );
}

/// 所有通知共用同一布局，图标与单行标题居中对齐，正文和时间与卡片左边缘对齐。
class _NotificationTile extends StatelessWidget {
  const _NotificationTile({
    required this.message,
    required this.unread,
    required this.onTap,
  });
  final ImMessage message;
  final bool unread;
  final VoidCallback onTap;

  /// 按宿主主题绘制统一通知卡片，图标不再相对整段正文垂直居中。
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final time = DateTime.parse(message.createdAt).toLocal();
    String two(int value) => value.toString().padLeft(2, '0');
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Material(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Container(
                      width: 32,
                      height: 32,
                      decoration: BoxDecoration(
                        color: colors.primaryContainer,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Icon(
                        Icons.mail_outline_rounded,
                        size: 20,
                        color: colors.onPrimaryContainer,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        '${message.payload['title'] ?? '消息提醒'}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    if (unread) ...[
                      const SizedBox(width: 8),
                      Semantics(
                        label: '未读',
                        child: Container(
                          width: 7,
                          height: 7,
                          decoration: BoxDecoration(
                            color: colors.primary,
                            shape: BoxShape.circle,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${message.payload['text'] ?? '点击查看消息详情'}',
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: colors.onSurfaceVariant,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '${time.year}-${two(time.month)}-${two(time.day)} ${two(time.hour)}:${two(time.minute)}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
