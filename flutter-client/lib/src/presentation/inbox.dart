import 'dart:async';
import 'package:flutter/material.dart';
import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';

/// 通知列表；宿主负责已认证客户端生命周期和资源权限检查后的业务导航。
class ImInbox extends StatefulWidget {
  /// 绑定当前账号客户端与消息打开回调；资源权限及业务导航由 onOpen 处理。
  const ImInbox({super.key, required this.client, required this.onOpen});
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
    if (oldWidget.client != client) {
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
      if (mounted && identical(client, active)) {
        await active.markRead(message.conversationSeq);
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('内容不存在或暂时无法访问')));
      }
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
    return Column(
      children: [
        Row(
          children: [
            Expanded(child: Text('未读 ${client.unreadCount}')),
            TextButton(onPressed: _readAll, child: const Text('全部已读')),
          ],
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
                          BigInt.parse(message.conversationSeq) > read;
                      return ListTile(
                        leading: Icon(
                          unread
                              ? Icons.mark_email_unread_outlined
                              : Icons.drafts_outlined,
                        ),
                        title: Text('${message.payload['title'] ?? '消息提醒'}'),
                        subtitle: Text(
                          '${message.payload['text'] ?? message.type}\n${DateTime.parse(message.createdAt).toLocal()}',
                        ),
                        isThreeLine: true,
                        onTap: () => _open(message),
                      );
                    },
                  ),
                ),
        ),
      ],
    );
  }
}
