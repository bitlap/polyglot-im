import 'dart:async';
import 'dart:convert';
import 'dart:math';
import '../ports/remote_api.dart';
import '../ports/socket_connector.dart';
import '../ports/wire_codec.dart';
import '../ports/local_store.dart';
import '../common/types.dart';
import '../common/errors.dart';
import '../domain/account_scope.dart';
import '../domain/cache.dart';
import '../domain/message.dart';
import '../config/protocol.dart';

class SyncClient {
  SyncClient({
    required this.api,
    required this.scope,
    required this.deviceId,
    required this.store,
    this.ownsApi = true,
    required this._socketConnector,
    required this._codec,
  }) {
    if (scope.environment.replaceFirst(RegExp(r'/$'), '') != api.baseUrl) {
      throw ArgumentError('scope environment must match baseUrl');
    }
    api.bindScope(scope);
  }
  final RemoteApi api;
  final AccountScope scope;
  final String deviceId;
  final LocalStore store;
  final bool ownsApi;
  final SocketConnector _socketConnector;
  final WireCodec _codec;
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
  SocketSession? _channel;
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
      SocketSession? activeChannel;
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
        final channel = _socketConnector.connect(uri);
        _channel = channel;
        activeChannel = channel;
        await channel.ready;
        _state('online');
        delay = 250;
        await for (final raw in channel.messages) {
          if (_closed || run != _realtimeGeneration) break;
          final page = Map<String, dynamic>.from(
            _codec.decode(raw!)['data'] as Map,
          );
          final previous = jsonEncode(_cache);
          _cache = await store.commit(scope, page);
          if (_closed || run != _realtimeGeneration) break;
          channel.send(_codec.encode({'cursor': page['cursor']}));
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
        await activeChannel?.close();
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
    await _channel?.close();
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
