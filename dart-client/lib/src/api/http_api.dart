import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import '../ports/remote_api.dart';
import '../ports/http_transport.dart';
import '../ports/wire_codec.dart';
import '../common/types.dart';
import '../common/errors.dart';
import '../domain/account_scope.dart';
import '../config/protocol.dart';
import '../config/retry_policy.dart';
import '../adapters/codec/json_codec.dart';
import '../adapters/transport/package_http_transport.dart';

/// 校验配置源并执行 HTTP API 请求；支持注入传输，明确区分借用与自有资源。
class HttpApi implements RemoteApi {
  /// 校验 baseUrl 并选择 HTTP 传输；注入同类传输的两种方式不能同时指定。
  HttpApi({
    required String baseUrl,
    required this.tokenProvider,
    this.refreshAuth,
    http.Client? client,
    HttpTransport? transport,
    this.codec = const JsonWireCodec(),
    RetryPolicy? retryPolicy,
    this.timeout = const Duration(seconds: 15),
  }) : baseUrl = baseUrl.replaceFirst(RegExp(r'/$'), ''),
       _transport =
           transport ??
           PackageHttpTransport(
             client ?? http.Client(),
             ownsClient: client == null,
           ),
       _ownsTransport = transport == null,
       retryPolicy = retryPolicy ?? RetryPolicy() {
    if (client != null && transport != null) {
      throw ArgumentError("choose client or transport");
    }
    final uri = Uri.parse(this.baseUrl);
    if (!['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.userInfo.isNotEmpty) {
      throw ArgumentError('absolute HTTP(S) baseUrl required');
    }
  }
  @override
  final String baseUrl;
  final Future<String> Function() tokenProvider;
  final Future<void> Function()? refreshAuth;
  final HttpTransport _transport;
  final bool _ownsTransport;
  final WireCodec codec;
  final RetryPolicy retryPolicy;
  final Duration timeout;
  AccountScope? _scope;

  /// 绑定当前账号期望身份，后续请求由服务端核对真实凭证。
  @override
  void bindScope(AccountScope scope) {
    if (_scope != null && _scope!.key != scope.key) {
      throw StateError('HTTP API already bound to another account');
    }
    _scope = scope;
  }

  Future<void>? _refreshing;
  bool _closed = false;

  /// 请求相对 IM API 路径并返回解包后的对象；临时错误有限重试，认证刷新最多一次。
  @override
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
      HttpResponse response;
      try {
        final value = body == null ? null : codec.encode(body);
        final encoded = value == null
            ? null
            : value is String
            ? utf8.encode(value)
            : List<int>.from(value as List);
        response = await _transport
            .execute(
              HttpRequest(
                method,
                Uri.parse('$baseUrl$apiPrefix$path'),
                headers: {
                  'Authorization': 'Bearer $token',
                  'Content-Type': codec.contentType,
                  if (_scope != null)
                    'X-IM-App-ID': Uri.encodeComponent(_scope!.appId),
                  if (_scope != null)
                    'X-IM-User-ID': Uri.encodeComponent(_scope!.userId),
                },
                body: encoded,
              ),
            )
            .timeout(timeout);
      } on TransportException {
        if (attempt >= retryPolicy.maxRetries) rethrow;
        await Future<void>.delayed(
          Duration(
            milliseconds:
                retryPolicy.initialDelay.inMilliseconds * (1 << attempt++) +
                (retryPolicy.jitterMilliseconds == 0
                    ? 0
                    : Random().nextInt(retryPolicy.jitterMilliseconds)),
          ),
        );
        continue;
      } on TimeoutException {
        if (attempt >= retryPolicy.maxRetries) rethrow;
        await Future<void>.delayed(
          Duration(
            milliseconds:
                retryPolicy.initialDelay.inMilliseconds * (1 << attempt++) +
                (retryPolicy.jitterMilliseconds == 0
                    ? 0
                    : Random().nextInt(retryPolicy.jitterMilliseconds)),
          ),
        );
        continue;
      }
      JsonMap data;
      try {
        data = codec.decode(response.body);
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
          attempt < retryPolicy.maxRetries) {
        final seconds =
            int.tryParse(response.headers['retry-after'] ?? '') ?? 0;
        await Future<void>.delayed(
          Duration(
            milliseconds:
                max(
                  min(seconds, retryPolicy.maxRetryAfter.inSeconds) * 1000,
                  retryPolicy.initialDelay.inMilliseconds * (1 << attempt++),
                ) +
                (retryPolicy.jitterMilliseconds == 0
                    ? 0
                    : Random().nextInt(retryPolicy.jitterMilliseconds)),
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

  /// 关闭自有的 HTTP 传输；调用方注入的借用传输不在此关闭。
  @override
  void close() {
    _closed = true;
    if (_ownsTransport) _transport.close();
  }
}
