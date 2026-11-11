import 'dart:convert';
import '../../common/types.dart';
import '../../ports/wire_codec.dart';

class JsonWireCodec implements WireCodec {
  const JsonWireCodec();
  @override
  String get contentType => 'application/json';
  @override
  String encode(JsonMap value) => jsonEncode(value);
  @override
  JsonMap decode(Object value) {
    final text = value is String
        ? value
        : utf8.decode(List<int>.from(value as List));
    return Map<String, dynamic>.from(jsonDecode(text) as Map);
  }
}
