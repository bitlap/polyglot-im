import 'dart:async';
import 'dart:convert';
import 'package:polyglot_im/polyglot_im.dart';
import 'package:test/test.dart';

class PrefixCodec extends JsonWireCodec {
  const PrefixCodec();
  @override
  String get contentType => 'application/x-test-frame';
  @override
  String encode(JsonMap value) => 'frame:${super.encode(value)}';
  @override
  JsonMap decode(Object value) {
    final text = value is String
        ? value
        : utf8.decode(List<int>.from(value as List));
    if (!text.startsWith('frame:')) {
      throw const FormatException('invalid frame');
    }
    return super.decode(text.substring(6));
  }
}

class RecordingTransport implements HttpTransport {
  HttpRequest? received;
  bool closed = false;
  @override
  Future<HttpResponse> execute(HttpRequest request) async {
    received = request;
    return HttpResponse(
      200,
      utf8.encode(
        const PrefixCodec().encode({
          'code': 200,
          'data': {'ok': true},
        }),
      ),
    );
  }

  @override
  void close() {
    closed = true;
  }
}

class TestRemoteApi implements RemoteApi {
  @override
  String get baseUrl => 'https://im.example';
  bool closed = false;
  @override
  void bindScope(AccountScope scope) {}
  @override
  Future<JsonMap> request(String method, String path, {JsonMap? body}) async {
    if (path == '/session') {
      return {'app_id': 'app', 'user_id': 'alice', 'ticket': 'test-ticket'};
    }
    return {
      'cursor': 'c1',
      'event_position': '1',
      'events': <dynamic>[],
      'has_more': false,
    };
  }

  @override
  void close() {
    closed = true;
  }
}

class TestSession implements SocketSession {
  TestSession(this.persisted);
  final Future<JsonMap> Function() persisted;
  final frames = StreamController<Object?>();
  final ack = Completer<Object>();
  @override
  Future<void> get ready async {}
  @override
  Stream<Object?> get messages => frames.stream;
  @override
  void send(Object value) {
    unawaited(
      persisted().then((cache) {
        expect(cache['event_position'], '2');
        ack.complete(value);
      }),
    );
  }

  @override
  Future<void> close() async {
    if (!frames.isClosed) await frames.close();
  }
}

class TestConnector implements SocketConnector {
  TestConnector(this.session);
  final SocketSession session;
  final connected = Completer<void>();
  @override
  SocketSession connect(Uri uri) {
    expect(uri.scheme, 'wss');
    connected.complete();
    return session;
  }
}

class DelayedSession implements SocketSession {
  final connected = Completer<void>();
  int subscriptions = 0;
  @override
  Future<void> get ready => connected.future;
  @override
  Stream<Object?> get messages {
    subscriptions++;
    return const Stream.empty();
  }

  @override
  void send(Object value) => fail('paused connection must not send');
  @override
  Future<void> close() async {}
}

void main() {
  test('自定义 HTTP 传输和编解码可以替换默认适配器；借用资源由宿主关闭', () async {
    final transport = RecordingTransport();
    final api = HttpApi(
      baseUrl: 'https://im.example',
      tokenProvider: () async => 'test',
      transport: transport,
      codec: const PrefixCodec(),
      retryPolicy: RetryPolicy(maxRetries: 0),
    );
    expect(await api.request('PUT', '/read', body: {'up_to_seq': '7'}), {
      'ok': true,
    });
    expect(
      transport.received!.headers['Content-Type'],
      'application/x-test-frame',
    );
    expect(const PrefixCodec().decode(transport.received!.body!), {
      'up_to_seq': '7',
    });
    api.close();
    expect(transport.closed, isFalse);
    transport.close();
    expect(transport.closed, isTrue);
  });
  test('同步服务使用远端 API、Socket 与存储端口，成功落盘后才确认帧', () async {
    final api = TestRemoteApi();
    final store = VolatileStore();
    const scope = AccountScope(
      environment: 'https://im.example',
      appId: 'app',
      userId: 'alice',
    );
    final session = TestSession(() => store.load(scope));
    final connector = TestConnector(session);
    final client = ImClient(
      api: api,
      scope: scope,
      deviceId: 'ios',
      store: store,
      ownsApi: false,
      socketConnector: connector,
      codec: const PrefixCodec(),
    );
    // Store commit precedes the public update event; inspect the committed state at send time.
    client.startRealtime();
    await connector.connected.future.timeout(const Duration(seconds: 2));
    session.frames.add(
      const PrefixCodec().encode({
        'type': 'sync',
        'data': {'cursor': 'c2', 'event_position': '2', 'events': <dynamic>[]},
      }),
    );
    final ack = await session.ack.future.timeout(const Duration(seconds: 2));
    expect(const PrefixCodec().decode(ack), {'cursor': 'c2'});
    await client.close(logout: false);
    expect(api.closed, isFalse);
  });
  test('暂停期间迟到的连接就绪不能恢复旧实时订阅', () async {
    final session = DelayedSession();
    final connector = TestConnector(session);
    final client = ImClient(
      api: TestRemoteApi(),
      scope: const AccountScope(
        environment: 'https://im.example',
        appId: 'app',
        userId: 'alice',
      ),
      deviceId: 'ios',
      store: VolatileStore(),
      socketConnector: connector,
    );
    client.startRealtime();
    await connector.connected.future.timeout(const Duration(seconds: 2));
    await client.pauseRealtime();
    session.connected.complete();
    await Future<void>.delayed(Duration.zero);
    expect(session.subscriptions, 0);
    await client.close(logout: false);
  });
}
