import 'dart:async';
import 'package:flutter/material.dart';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

/// 单聊列表；从服务器分页补齐快照以外的会话。
class ImConversations extends StatefulWidget {
  const ImConversations({
    super.key,
    required this.client,
    required this.onOpen,
  });
  final SyncClient client;
  final void Function(String conversationId) onOpen;
  @override
  State<ImConversations> createState() => _ImConversationsState();
}

class _ImConversationsState extends State<ImConversations> {
  StreamSubscription<JsonMap>? _changes;
  String? _after;
  bool _more = false, _loading = false;
  Object? _error;
  int _generation = 0;
  @override
  void initState() {
    super.initState();
    _attach();
  }

  void _attach() {
    _changes = widget.client.changes.listen((_) {
      if (mounted) setState(() {});
    });
    unawaited(_load());
  }

  @override
  void didUpdateWidget(ImConversations oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.client != widget.client) {
      ++_generation;
      unawaited(_changes?.cancel());
      _after = null;
      _more = false;
      _loading = false;
      _error = null;
      _attach();
    }
  }

  Future<void> _load({bool older = false}) async {
    if (_loading) return;
    final generation = _generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.client.conversations(
        after: older ? _after : null,
      );
      if (mounted && generation == _generation) {
        setState(() {
          _more = page['has_more'] == true;
          _after = page['next_after'] as String?;
        });
      }
    } catch (error) {
      if (mounted && generation == _generation) setState(() => _error = error);
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  void dispose() {
    ++_generation;
    unawaited(_changes?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final items =
        ((widget.client.cache['conversations'] as Map?)?.values ?? const [])
            .toList()
          ..sort(
            (dynamic a, dynamic b) => (a['conversation_id'] as String)
                .compareTo(b['conversation_id'] as String),
          );
    return Column(
      children: [
        TextButton(
          onPressed: _loading ? null : () => _load(),
          child: const Text('刷新会话'),
        ),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null) Text('$_error'),
        Expanded(
          child: ListView(
            children: [
              for (final dynamic item in items)
                ListTile(
                  title: Text(
                    (item['member_ids'] as List)
                        .where((dynamic id) => id != widget.client.scope.userId)
                        .join(', '),
                  ),
                  trailing: Text('${item['unread_count']}'),
                  onTap: () => widget.onOpen(item['conversation_id'] as String),
                ),
            ],
          ),
        ),
        if (_more)
          TextButton(
            onPressed: _loading ? null : () => _load(older: true),
            child: const Text('加载更多会话'),
          ),
      ],
    );
  }
}
