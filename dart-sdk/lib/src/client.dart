import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import 'package:web_socket_channel/web_socket_channel.dart';
import 'message.dart';

typedef JsonMap = Map<String, dynamic>;

const apiPrefix = '/api/v1/im';

class ImException implements Exception {
  const ImException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;
  @override
  String toString() => 'ImException($status, $code): $message';
}

class AccountScope {
  const AccountScope({
    required this.environment,
    required this.appId,
    required this.userId,
  });
  final String environment, appId, userId;
  String get key =>
      jsonEncode([environment.replaceFirst(RegExp(r'/$'), ''), appId, userId]);
}

JsonMap emptyCache() => {
  'messages': <String, dynamic>{},
  'cursor': null,
  'event_position': '0',
  'latest_seq': '0',
  'read_seq': '0',
  'unread_count': '0',
  'chat_unread_count': '0',
};

JsonMap applyPage(JsonMap current, JsonMap page, {bool reset = false}) {
  if (BigInt.parse('${page['event_position'] ?? current['event_position']}') <
      BigInt.parse('${current['event_position']}')) {
    return current;
  }
  final result = reset
      ? emptyCache()
      : jsonDecode(jsonEncode(current)) as JsonMap;
  final messages = result['messages'] as JsonMap;
  void add(dynamic value) {
    final message = ImMessage.fromJson(Map<String, dynamic>.from(value as Map));
    messages[message.messageId] = message.toJson();
  }

  for (final value in (page['messages'] as List? ?? const [])) {
    add(value);
  }
  for (final event in (page['events'] as List? ?? const [])) {
    if (event['type'] == 'message.created') add(event['data']);
  }
  final conversations =
      result.putIfAbsent('conversations', () => <String, dynamic>{}) as JsonMap;
  final patches = <dynamic>[
    ...page['conversations'] as List? ?? const [],
    for (final event in page['events'] as List? ?? const [])
      if (event['type'] == 'conversation.updated') event['data'],
  ];
  for (final patch in patches) {
    final cid = patch['conversation_id'] as String;
    final previous =
        conversations[cid] as JsonMap? ??
        {'revision': '0', 'messages': <String, dynamic>{}};
    final saved = Map<String, dynamic>.from(previous['messages'] as Map);
    for (final value in patch['messages'] as List? ?? const []) {
      final message = ImMessage.fromJson(
        Map<String, dynamic>.from(value as Map),
      );
      if (message.conversationId != cid) {
        throw const FormatException('message conversation mismatch');
      }
      saved[message.messageId] = message.toJson();
    }
    final metadata =
        BigInt.parse('${previous['revision']}') >
            BigInt.parse('${patch['revision']}')
        ? previous
        : Map<String, dynamic>.from(patch as Map);
    conversations[cid] = {...metadata, 'messages': saved};
  }
  for (final name in [
    'cursor',
    'event_position',
    'latest_seq',
    'read_seq',
    'unread_count',
    'chat_unread_count',
    'conversation_id',
  ]) {
    if (page.containsKey(name)) result[name] = page[name];
  }
  for (final key in ['latest_seq', 'read_seq']) {
    if (!reset &&
        BigInt.parse('${current[key]}') > BigInt.parse('${result[key]}')) {
      result[key] = current[key];
    }
  }
  result['unread_count'] =
      (BigInt.parse('${result['latest_seq']}') -
              BigInt.parse('${result['read_seq']}'))
          .toString();
  return result;
}

abstract interface class LocalStore {
  Future<JsonMap> load(AccountScope scope);
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  });
  Future<void> clear(AccountScope scope);
}

/// 测试或显式选择的易失缓存；生产 App 应注入 Flutter SDK 的 SQLite 实现。
class VolatileStore implements LocalStore {
  final _values = <String, JsonMap>{};
  @override
  Future<JsonMap> load(AccountScope scope) async =>
      _values[scope.key] ?? emptyCache();
  @override
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  }) async =>
      _values[scope.key] = applyPage(await load(scope), page, reset: reset);
  @override
  Future<void> clear(AccountScope scope) async {
    _values.remove(scope.key);
  }
}

class HttpApi {
  HttpApi({
    required String baseUrl,
    required this.tokenProvider,
    this.refreshAuth,
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : baseUrl = baseUrl.replaceFirst(RegExp(r'/$'), ''),
       _http = client ?? http.Client(),
       _ownsHttp = client == null {
    final uri = Uri.parse(this.baseUrl);
    if (!['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.userInfo.isNotEmpty) {
      throw ArgumentError('absolute HTTP(S) baseUrl required');
    }
  }
  final String baseUrl;
  final Future<String> Function() tokenProvider;
  final Future<void> Function()? refreshAuth;
  final http.Client _http;
  final bool _ownsHttp;
  final Duration timeout;
  AccountScope? _scope;
  void bindScope(AccountScope scope) {
    if (_scope != null && _scope!.key != scope.key) {
      throw StateError('HTTP API already bound to another account');
    }
    _scope = scope;
  }

  Future<void>? _refreshing;
  bool _closed = false;

  Future<JsonMap> request(String method, String path, {JsonMap? body}) async {
    var attempt = 0;
    var refreshed = false;
    while (true) {
      if (_closed) throw StateError('HTTP API closed');
      final token = await tokenProvider();
      if (token.trim().isEmpty ||
          token.contains('\r') ||
          token.contains('\n')) {
        throw StateError('invalid bearer credential');
      }
      http.Response response;
      try {
        final request = http.Request(
          method,
          Uri.parse('$baseUrl$apiPrefix$path'),
        );
        request.headers.addAll({
          'Authorization': 'Bearer $token',
          'Content-Type': 'application/json',
          if (_scope != null) 'X-IM-App-ID': Uri.encodeComponent(_scope!.appId),
          if (_scope != null)
            'X-IM-User-ID': Uri.encodeComponent(_scope!.userId),
        });
        if (body != null) request.body = jsonEncode(body);
        response = await http.Response.fromStream(
          await _http.send(request).timeout(timeout),
        );
      } on http.ClientException {
        if (attempt >= 3) rethrow;
        await Future<void>.delayed(
          Duration(
            milliseconds: 250 * (1 << attempt++) + Random().nextInt(100),
          ),
        );
        continue;
      } on TimeoutException {
        if (attempt >= 3) rethrow;
        await Future<void>.delayed(
          Duration(
            milliseconds: 250 * (1 << attempt++) + Random().nextInt(100),
          ),
        );
        continue;
      }
      JsonMap data;
      try {
        data = jsonDecode(response.body) as JsonMap;
      } on FormatException {
        if (response.statusCode >= 200 && response.statusCode < 300) rethrow;
        data = {};
      }
      if (response.statusCode >= 200 && response.statusCode < 300) {
        if (data['code'] is int) {
          if (data['code'] != 200 || data['data'] is! Map<String, dynamic>) {
            throw const FormatException('invalid API response envelope');
          }
          return data['data'] as JsonMap;
        }
        return data;
      }
      if (response.statusCode == 401 && refreshAuth != null && !refreshed) {
        await (_refreshing ??= refreshAuth!().whenComplete(() {
          _refreshing = null;
        }));
        refreshed = true;
        continue;
      }
      if ([429, 500, 502, 503, 504].contains(response.statusCode) &&
          attempt < 3) {
        final seconds =
            int.tryParse(response.headers['retry-after'] ?? '') ?? 0;
        await Future<void>.delayed(
          Duration(
            milliseconds:
                max(min(seconds, 30) * 1000, 250 * (1 << attempt++)) +
                Random().nextInt(100),
          ),
        );
        continue;
      }
      throw ImException(
        response.statusCode,
        data['error_code'] as String? ??
            (data['code'] is String ? data['code'] as String : 'HTTP_ERROR'),
        data['message'] as String? ?? 'request failed',
      );
    }
  }

  void close() {
    _closed = true;
    if (_ownsHttp) _http.close();
  }
}

class NotificationSender {
  const NotificationSender(this.api);
  final HttpApi api;
  Future<ImMessage> send({
    required String recipientId,
    required String clientMessageId,
    required String type,
    required JsonMap payload,
  }) async {
    if (clientMessageId.isEmpty) {
      throw ArgumentError('stable clientMessageId required');
    }
    return ImMessage.fromJson(
      await api.request(
        'POST',
        '/notifications',
        body: {
          'recipient_id': recipientId,
          'client_message_id': clientMessageId,
          'type': type,
          'payload': payload,
        },
      ),
    );
  }
}

class ImClient {
  ImClient({
    required this.api,
    required this.scope,
    required this.deviceId,
    required this.store,
    this.ownsApi = true,
    WebSocketChannel Function(Uri)? socketFactory,
  }) : _socketFactory = socketFactory ?? WebSocketChannel.connect {
    if (scope.environment.replaceFirst(RegExp(r'/$'), '') != api.baseUrl) {
      throw ArgumentError('scope environment must match baseUrl');
    }
    api.bindScope(scope);
  }
  final HttpApi api;
  final AccountScope scope;
  final String deviceId;
  final LocalStore store;
  final bool ownsApi;
  final WebSocketChannel Function(Uri) _socketFactory;
  final _updates = StreamController<JsonMap>.broadcast();
  final _states = StreamController<String>.broadcast();
  JsonMap _cache = emptyCache();
  JsonMap get cache => _cache;
  Stream<JsonMap> get changes => _updates.stream;
  Stream<String> get connectionStates => _states.stream;
  String connectionState = 'idle';
  bool _closed = false;
  int _generation = 0, _realtimeGeneration = 0;
  Future<JsonMap>? _syncing;
  Future<void>? _running;
  WebSocketChannel? _channel;
  Timer? _poll;
  void _check([int? generation]) {
    if (_closed || generation != null && generation != _generation) {
      throw StateError('account client closed');
    }
  }

  void _state(String value) {
    if (!_closed) {
      connectionState = value;
      _states.add(value);
    }
  }

  void _publish() {
    if (!_closed) _updates.add(_cache);
  }

  List<ImMessage> get messages =>
      (_cache['messages'] as JsonMap).values
          .map((m) => ImMessage.fromJson(Map<String, dynamic>.from(m as Map)))
          .toList()
        ..sort(
          (a, b) => BigInt.parse(
            b.conversationSeq,
          ).compareTo(BigInt.parse(a.conversationSeq)),
        );
  BigInt get unreadCount => BigInt.parse('${_cache['unread_count']}');
  BigInt get chatUnreadCount =>
      BigInt.parse('${_cache['chat_unread_count'] ?? '0'}');

  Future<JsonMap> _session() async {
    final generation = _generation;
    final result = await api.request(
      'POST',
      '/session',
      body: {'device_id': deviceId},
    );
    _check(generation);
    if (result['app_id'] != scope.appId || result['user_id'] != scope.userId) {
      throw const ImException(
        403,
        'IDENTITY_MISMATCH',
        'credential and account scope differ',
      );
    }
    return result;
  }

  Future<JsonMap> initialize({bool realtime = true}) async {
    _check();
    _cache = await store.load(scope);
    _check();
    _publish();
    await _session();
    await sync();
    if (realtime) startRealtime();
    return _cache;
  }

  Future<JsonMap> sync() {
    _check();
    return _syncing ??= _sync().whenComplete(() {
      _syncing = null;
    });
  }

  Future<JsonMap> _sync() async {
    final generation = _generation;
    _state('syncing');
    try {
      do {
        JsonMap page;
        var reset = false;
        try {
          if (_cache['cursor'] == null) {
            page = await api.request('GET', '/snapshot');
            reset = true;
          } else {
            page = await api.request(
              'GET',
              '/sync?${Uri(queryParameters: {'cursor': _cache['cursor'] as String}).query}',
            );
          }
        } on ImException catch (error) {
          if (error.code != 'RESET_REQUIRED') rethrow;
          page = await api.request('GET', '/snapshot');
          reset = true;
        }
        _check(generation);
        _cache = await store.commit(scope, page, reset: reset);
        _check(generation);
        _publish();
        if (page['has_more'] != true) break;
      } while (true);
      _state('online');
      return _cache;
    } on ImException catch (error) {
      _state(error.status == 401 ? 'authRequired' : 'retrying');
      rethrow;
    } catch (_) {
      _state('retrying');
      rethrow;
    }
  }

  Future<JsonMap> history({String? beforeSeq, int limit = 50}) async {
    final generation = _generation;
    _check();
    final query = Uri(
      queryParameters: {'limit': '$limit', 'before_seq': ?beforeSeq},
    ).query;
    final page = await api.request('GET', '/messages?$query');
    _check(generation);
    _cache = await store.commit(scope, {'messages': page['items']});
    _check(generation);
    _publish();
    return page;
  }

  Future<void> _commitConversations(List<dynamic> patches) async {
    final generation = _generation;
    _check();
    _cache = await store.commit(scope, {'conversations': patches});
    _check(generation);
    _publish();
  }

  Future<JsonMap> createDirect(String peerUserId) async {
    final generation = _generation;
    _check();
    final result = await api.request(
      'POST',
      '/conversations/direct',
      body: {'peer_user_id': peerUserId},
    );
    _check(generation);
    await _commitConversations([result]);
    return result;
  }

  Future<JsonMap> conversations({String? after, int limit = 50}) async {
    final generation = _generation;
    _check();
    final query = Uri(
      queryParameters: {'after': after ?? '', 'limit': '$limit'},
    ).query;
    final result = await api.request('GET', '/conversations?$query');
    _check(generation);
    await _commitConversations(result['items'] as List);
    return result;
  }

  Future<JsonMap> conversationHistory(
    String id, {
    String? beforeSeq,
    int limit = 50,
  }) async {
    final generation = _generation;
    _check();
    final query = Uri(
      queryParameters: {'limit': '$limit', 'before_seq': ?beforeSeq},
    ).query;
    final result = await api.request(
      'GET',
      '/conversations/${Uri.encodeComponent(id)}/messages?$query',
    );
    _check(generation);
    await _commitConversations([result['conversation']]);
    return result;
  }

  Future<ImMessage> sendText(
    String id, {
    required String clientMessageId,
    required String text,
  }) async {
    final generation = _generation;
    _check();
    if (clientMessageId.isEmpty) {
      throw ArgumentError('stable clientMessageId required');
    }
    final result = await api.request(
      'POST',
      '/conversations/${Uri.encodeComponent(id)}/messages',
      body: {
        'client_message_id': clientMessageId,
        'type': 'chat.text',
        'payload': {'text': text},
      },
    );
    _check(generation);
    await sync();
    return ImMessage.fromJson(result);
  }

  Future<JsonMap> markConversationRead(String id, String upToSeq) async {
    final generation = _generation;
    _check();
    final result = await api.request(
      'PUT',
      '/conversations/${Uri.encodeComponent(id)}/read',
      body: {'up_to_seq': upToSeq},
    );
    _check(generation);
    await _commitConversations([result]);
    await sync();
    return result;
  }

  Future<ImMessage> getMessage(String id) async {
    final generation = _generation;
    _check();
    final message = await api.request(
      'GET',
      '/messages/${Uri.encodeComponent(id)}',
    );
    _check(generation);
    return ImMessage.fromJson(message);
  }

  Future<JsonMap> markRead([String? upToSeq]) async {
    final generation = _generation;
    _check();
    final target = upToSeq ?? '${_cache['latest_seq']}';
    await api.request('PUT', '/read', body: {'up_to_seq': target});
    _check(generation);
    return sync();
  }

  Future<void> updatePushToken(String? token) async {
    _check();
    await api.request(
      'PUT',
      '/devices/${Uri.encodeComponent(deviceId)}/push-token',
      body: {'token': token},
    );
  }

  Future<JsonMap> pushPreferences() => api.request(
    'GET',
    '/devices/${Uri.encodeComponent(deviceId)}/preferences',
  );

  Future<void> setPush(
    String? token, {
    bool muted = false,
    bool preview = false,
  }) async {
    _check();
    await api.request(
      'PUT',
      '/devices/${Uri.encodeComponent(deviceId)}/push-token',
      body: {'token': token, 'muted': muted, 'preview': preview},
    );
  }

  void startRealtime() {
    _check();
    if (_running != null) return;
    final run = ++_realtimeGeneration;
    _running = _realtime(run).whenComplete(() {
      if (run == _realtimeGeneration) _running = null;
    });
  }

  Future<void> _realtime(int run) async {
    var delay = 250;
    while (!_closed && run == _realtimeGeneration) {
      WebSocketChannel? activeChannel;
      try {
        await sync();
        final session = await _session();
        if (_closed || run != _realtimeGeneration) break;
        final base = Uri.parse(api.baseUrl);
        final uri = base.replace(
          scheme: base.scheme == 'https' ? 'wss' : 'ws',
          path: '${base.path}$apiPrefix/ws',
          queryParameters: {
            'ticket': session['ticket'] as String,
            'cursor': _cache['cursor'] as String,
          },
        );
        _state('connecting');
        final channel = _socketFactory(uri);
        _channel = channel;
        activeChannel = channel;
        await channel.ready;
        _state('online');
        delay = 250;
        await for (final raw in channel.stream) {
          if (_closed || run != _realtimeGeneration) break;
          final page = Map<String, dynamic>.from(
            (jsonDecode(raw as String) as JsonMap)['data'] as Map,
          );
          final previous = jsonEncode(_cache);
          _cache = await store.commit(scope, page);
          if (_closed || run != _realtimeGeneration) break;
          channel.sink.add(jsonEncode({'cursor': page['cursor']}));
          if (jsonEncode(_cache) != previous) _publish();
        }
      } on ImException catch (error) {
        if (error.status == 401 || error.code == 'IDENTITY_MISMATCH') {
          _state('authRequired');
          break;
        }
        _state('retrying');
      } catch (_) {
        if (!_closed) _state('retrying');
      } finally {
        await activeChannel?.sink.close();
        if (identical(_channel, activeChannel)) _channel = null;
      }
      if (!_closed && run == _realtimeGeneration) {
        await Future<void>.delayed(
          Duration(milliseconds: delay + Random().nextInt(100)),
        );
        delay = min(delay * 2, 30000);
      }
    }
  }

  void startPolling([Duration interval = const Duration(seconds: 30)]) {
    _check();
    _poll?.cancel();
    _poll = Timer.periodic(interval, (_) {
      if (!_closed) unawaited(sync().catchError((Object error) => _cache));
    });
  }

  Future<void> pauseRealtime() async {
    ++_realtimeGeneration;
    await _channel?.sink.close();
    _running = null;
  }

  Future<void> close({bool logout = true, bool clearCache = false}) async {
    if (_closed) return;
    _closed = true;
    ++_generation;
    _poll?.cancel();
    await pauseRealtime();
    try {
      if (logout) {
        await api.request(
          'DELETE',
          '/devices/${Uri.encodeComponent(deviceId)}/session',
        );
      }
    } finally {
      if (ownsApi) api.close();
      if (clearCache) await store.clear(scope);
      await _updates.close();
      await _states.close();
    }
  }
}
