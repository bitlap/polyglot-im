import 'dart:async';
import 'package:flutter/material.dart';
import 'package:polyglot_im_flutter/polyglot_im_flutter.dart';

void main() {
  runApp(const MaterialApp(home: InboxExample()));
}

class InboxExample extends StatefulWidget {
  const InboxExample({super.key});
  @override
  State<InboxExample> createState() => _InboxExampleState();
}

class _InboxExampleState extends State<InboxExample> {
  ImClient? _client;
  SqliteStore? _store;
  IosPushBinding? _push;
  Object? _error;
  @override
  void initState() {
    super.initState();
    unawaited(_connect());
  }

  Future<void> _open(ImMessage message) async {
    if (message.type == 'chat.text' && _client != null) {
      await _chat(message.conversationId);
      return;
    }
    // 真实 App 按允许的 action.type 调用业务 API 鉴权，然后导航。
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('${message.payload['title'] ?? '消息详情'}'),
        content: Text('${message.payload['text'] ?? message.type}'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  Future<void> _chat(String id) => Navigator.of(context).push<void>(
    MaterialPageRoute(
      builder: (_) => Scaffold(
        appBar: AppBar(title: const Text('单聊')),
        body: ImDirectChat(client: _client!, conversationId: id),
      ),
    ),
  );

  Future<void> _startChat() async {
    var peer = '';
    final id = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('发起单聊'),
        content: TextField(
          onChanged: (value) => peer = value,
          decoration: const InputDecoration(labelText: '对方用户 ID'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, peer.trim()),
            child: const Text('打开'),
          ),
        ],
      ),
    );
    if (id == null || id.isEmpty || _client == null) return;
    try {
      final conversation = await _client!.createDirect(id);
      if (mounted) await _chat(conversation['conversation_id'] as String);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  Future<void> _connect() async {
    const base = String.fromEnvironment(
      'IM_BASE_URL',
      defaultValue: 'http://127.0.0.1:8090',
    );
    const app = String.fromEnvironment('IM_APP_ID', defaultValue: 'demo');
    const user = String.fromEnvironment('IM_USER_ID', defaultValue: 'alice');
    const token = String.fromEnvironment('IM_USER_TOKEN');
    if (token.isEmpty) {
      setState(() => _error = '请通过 --dart-define-from-file 提供本地测试配置');
      return;
    }
    try {
      final store = await SqliteStore.openDefault();
      _store = store;
      final client = ImClient(
        api: HttpApi(baseUrl: base, tokenProvider: () async => token),
        scope: const AccountScope(environment: base, appId: app, userId: user),
        deviceId: await store.installationId(),
        store: store,
      );
      _client = client;
      await client.initialize();
      client.startPolling();
      if (!mounted) {
        await client.close(logout: false);
        await store.close();
        return;
      }
      _push = IosPushBinding(
        client: client,
        onOpen: (message) => unawaited(_open(message)),
        onError: (error, stack) {
          if (mounted) setState(() => _error = error);
        },
      );
      await _push!.attach();
      if (mounted) setState(() {});
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  Future<void> _disposeResources() async {
    await _push?.dispose();
    await _client?.close(logout: false);
    await _store?.close();
  }

  @override
  void dispose() {
    unawaited(_disposeResources());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('消息中心'),
      actions: [
        IconButton(
          onPressed: _client == null ? null : _startChat,
          icon: const Icon(Icons.chat),
          tooltip: '单聊',
        ),
        TextButton(
          onPressed: _push == null
              ? null
              : () async {
                  final allowed = await _push!.requestPermission();
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        content: Text(allowed ? '已授权通知' : '未开启通知，站内消息仍可用'),
                      ),
                    );
                  }
                },
          child: const Text('开启提醒'),
        ),
      ],
    ),
    body: Column(
      children: [
        if (_error != null)
          Padding(padding: const EdgeInsets.all(16), child: Text('$_error')),
        Expanded(
          child: _client == null
              ? const Center(child: Text('等待登录和初始化'))
              : DefaultTabController(
                  length: 2,
                  child: Column(
                    children: [
                      const TabBar(
                        tabs: [
                          Tab(text: '通知'),
                          Tab(text: '单聊'),
                        ],
                      ),
                      Expanded(
                        child: TabBarView(
                          children: [
                            ImInbox(client: _client!, onOpen: _open),
                            ImConversations(
                              client: _client!,
                              onOpen: (id) => unawaited(_chat(id)),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
        ),
      ],
    ),
  );
}
