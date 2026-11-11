import '../common/types.dart';

abstract interface class WireCodec {
  String get contentType;
  Object encode(JsonMap value);
  JsonMap decode(Object value);
}
