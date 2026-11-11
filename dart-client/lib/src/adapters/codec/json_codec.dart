import 'dart:convert';
import '../../common/types.dart';
import '../../ports/wire_codec.dart';

/// JSON 消息编解码实现；HTTP 使用对象信封，实时连接使用独立同步帧。
class JsonWireCodec implements WireCodec {
  /// 创建无状态的 JSON 编解码器，可在同一连接链路复用。
  const JsonWireCodec();

  /// 返回此编解码器产生的 HTTP 正文媒体类型。
  @override
  String get contentType => 'application/json';

  /// 将协议对象编码为传输格式，保留字符串序号与原始业务载荷。
  @override
  String encode(JsonMap value) => jsonEncode(value);

  /// 解析传输正文；格式或协议结构不合法时返回或抛出解码错误。
  @override
  JsonMap decode(Object value) {
    final text = value is String
        ? value
        : utf8.decode(List<int>.from(value as List));
    return Map<String, dynamic>.from(jsonDecode(text) as Map);
  }
}
