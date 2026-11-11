import '../common/types.dart';

/// HTTP 正文和实时帧的编解码端口，同一连接链路应使用同一实现。
abstract interface class WireCodec {
  /// 返回此编解码器产生的 HTTP 正文媒体类型。
  String get contentType;

  /// 将协议对象编码为传输格式，保留字符串序号与原始业务载荷。
  Object encode(JsonMap value);

  /// 解析传输正文；格式或协议结构不合法时返回或抛出解码错误。
  JsonMap decode(Object value);
}
