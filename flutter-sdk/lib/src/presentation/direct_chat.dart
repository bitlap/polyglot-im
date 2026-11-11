import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

/// 文字与 Unicode 表情单聊。宿主负责登录、会话选择和启动实时同步。
class ImDirectChat extends StatefulWidget {
  const ImDirectChat({
    super.key,
    required this.client,
    required this.conversationId,
  });
  final SyncClient client;
  final String conversationId;
  @override
  State<ImDirectChat> createState() => _ImDirectChatState();
}

class _ImDirectChatState extends State<ImDirectChat>
    with WidgetsBindingObserver {
  final _text = TextEditingController();
  StreamSubscription<JsonMap>? _changes;
  Object? _error;
  bool _sending = false, _loading = false, _more = false;
  String? _before, _pendingKey, _pendingText;
  int _generation = 0;
  bool _foreground = true;
  bool _reading = false;
  JsonMap get _conversation =>
      (widget.client.cache['conversations'] as Map?)?[widget.conversationId]
          as JsonMap? ??
      {};
  List<ImMessage> get _messages =>
      ((_conversation['messages'] as Map?)?.values ?? const [])
          .map((m) => ImMessage.fromJson(Map<String, dynamic>.from(m as Map)))
          .toList()
        ..sort(
          (a, b) => BigInt.parse(
            b.conversationSeq,
          ).compareTo(BigInt.parse(a.conversationSeq)),
        );

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    _attach();
  }

  @override
  void didUpdateWidget(ImDirectChat oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.client != widget.client ||
        oldWidget.conversationId != widget.conversationId) {
      ++_generation;
      unawaited(_changes?.cancel());
      _text.clear();
      _pendingKey = null;
      _pendingText = null;
      _reading = false;
      _sending = false;
      _loading = false;
      _error = null;
      _before = null;
      _attach();
    }
  }

  void _attach() {
    _changes = widget.client.changes.listen((_) {
      if (mounted) {
        setState(() {});
        _scheduleRead();
      }
    });
    unawaited(_load());
  }

  Future<void> _load({bool older = false}) async {
    if (_loading) return;
    final generation = _generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.client.conversationHistory(
        widget.conversationId,
        beforeSeq: older ? _before : null,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _more = page['has_more'] == true;
        _before = page['next_before_seq'] as String?;
      });
      _scheduleRead();
    } catch (error) {
      if (mounted && generation == _generation) setState(() => _error = error);
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground) _scheduleRead();
  }

  void _scheduleRead() {
    final generation = _generation;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && generation == _generation) _readVisible();
    });
  }

  void _readVisible() {
    if (!mounted ||
        !_foreground ||
        _reading ||
        ModalRoute.of(context)?.isCurrent == false) {
      return;
    }
    final messages = _messages;
    if (messages.isEmpty) return;
    final seq = messages.first.conversationSeq;
    if (BigInt.parse(seq) <=
        BigInt.parse('${_conversation['read_seq'] ?? '0'}')) {
      return;
    }
    final generation = _generation;
    _reading = true;
    unawaited(
      widget.client
          .markConversationRead(widget.conversationId, seq)
          .then<void>(
            (_) {
              if (mounted && generation == _generation) {
                _reading = false;
                _scheduleRead();
              }
            },
            onError: (Object error, StackTrace stack) {
              if (mounted && generation == _generation) {
                _reading = false;
                setState(() => _error = error);
              }
            },
          ),
    );
  }

  Future<void> _send() async {
    if (_sending || _text.text.trim().isEmpty) return;
    final generation = _generation;
    final text = _text.text;
    // 网络结果不确定时，保持原文重试仍使用同一个键。
    if (_pendingText != text || _pendingKey == null) {
      final random = Random.secure();
      _pendingKey = List.generate(
        16,
        (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
      ).join();
      _pendingText = text;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await widget.client.sendText(
        widget.conversationId,
        clientMessageId: _pendingKey!,
        text: text,
      );
      if (!mounted || generation != _generation) return;
      _text.clear();
      _pendingKey = null;
      _pendingText = null;
      _scheduleRead();
    } catch (error) {
      if (mounted && generation == _generation) setState(() => _error = error);
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _sending = false);
      }
    }
  }

  @override
  void dispose() {
    ++_generation;
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_changes?.cancel());
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final messages = _messages;
    return Column(
      children: [
        if (_error != null)
          Padding(padding: const EdgeInsets.all(8), child: Text('$_error')),
        TextButton(
          onPressed: _loading ? null : () => _load(),
          child: const Text('刷新聊天'),
        ),
        if (_more)
          TextButton(
            onPressed: _loading ? null : () => _load(older: true),
            child: const Text('加载更早消息'),
          ),
        if (_loading) const LinearProgressIndicator(),
        Expanded(
          child: ListView.builder(
            reverse: true,
            itemCount: messages.length,
            itemBuilder: (context, index) {
              final m = messages[index];
              final mine = m.payload['sender_id'] == widget.client.scope.userId;
              final read =
                  mine &&
                  BigInt.parse(m.conversationSeq) <=
                      BigInt.parse('${_conversation['peer_read_seq'] ?? '0'}');
              return Align(
                alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('${m.payload['sender_id']}'),
                        Text('${m.payload['text']}'),
                        if (mine) Text(read ? '已读' : '已发送'),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        SafeArea(
          top: false,
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _text,
                  enabled: !_sending,
                  maxLength: 2048,
                  decoration: const InputDecoration(hintText: '文字或表情 😊'),
                  onSubmitted: (_) => _send(),
                ),
              ),
              TextButton(
                onPressed: _sending ? null : _send,
                child: Text(_sending ? '发送中' : '发送'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
