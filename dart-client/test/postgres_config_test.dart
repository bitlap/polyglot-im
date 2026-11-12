import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';
import 'package:polyglot_im_dart_client/postgres.dart';
import 'package:postgres/postgres.dart' as pg;
import 'package:test/test.dart';

void main() {
  late pg.Pool<void> pool;
  const scope = AccountScope(
    environment: 'https://im.example',
    appId: 'app',
    userId: 'alice',
  );
  setUp(() {
    // 池惰性连接；配置拒绝测试不建立网络连接。
    pool = pg.Pool<void>.withEndpoints([
      pg.Endpoint(host: '127.0.0.1', port: 1, database: 'unused'),
    ]);
  });
  tearDown(() => pool.close());

  test('在连接前拒绝不明确的 namespace、schema 和无限锁等待', () {
    for (final namespace in ['', ' ', 'x' * 201]) {
      expect(
        () => PostgresStore(pool, namespace: namespace),
        throwsArgumentError,
      );
    }
    expect(
      () => PostgresStore(
        pool,
        namespace: 'x',
        schema: 'public;DROP SCHEMA public',
      ),
      throwsArgumentError,
    );
    expect(
      () => PostgresStore(pool, namespace: 'x', lockTimeout: Duration.zero),
      throwsArgumentError,
    );
  });
  test('关闭借用存储不关闭宿主池，重复关闭安全且禁止复用存储', () async {
    final store = PostgresStore(pool, namespace: 'device');
    await store.close();
    await store.close();
    expect(pool.isOpen, isTrue);
    expect(() => store.load(scope), throwsStateError);
    expect(() => store.commit(scope, {}), throwsStateError);
    expect(() => store.clear(scope), throwsStateError);
  });
  test('自有池工厂拒绝缺失协议、主机或数据库的 URL', () async {
    for (final url in [
      '',
      'postgresql://',
      'postgresql://localhost',
      'file:///tmp/db',
      'localhost/db',
    ]) {
      await expectLater(
        PostgresStore.open(url, namespace: 'device'),
        throwsArgumentError,
      );
    }
  });
}
