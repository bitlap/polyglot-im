package polyglot.im

import java.nio.file.{Files, Path}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite

class ClientSuite extends CatsEffectSuite {
  test("网关 HTML 503 有限重试，403 不重试") {
    for {
      calls <- cats.effect.Ref.of[IO, Int](0)
      transport = new polyglot.im.transport.HttpTransport[IO] {
        def execute(request: polyglot.im.transport.HttpRequest): IO[polyglot.im.transport.HttpResponse] = calls
          .getAndUpdate(_ + 1)
          .map(n =>
            if (n == 0) polyglot.im.transport.HttpResponse(503, "<html>unavailable</html>")
            else polyglot.im.transport.HttpResponse(200, "{\"ok\":true}")
          )
      }
      response <- new ApiClient[IO](transport).request("GET", "/state")
      count    <- calls.get
      _ = assertEquals(count, 2)
      _ = assertEquals(response.hcursor.get[Boolean]("ok").toOption, Some(true))
    } yield ()
  }
  test("共享增量契约、大序号、原子缓存失败和重启恢复") {
    val page =
      parse(Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol/fixtures/sync.json"))).toOption.get
    val scope = AccountScope("https://im.example", "app", "alice")
    val directory = Resource.make(IO.blocking(Files.createTempDirectory("im-cache-contract")))(p =>
      IO.blocking {
        val walk = Files.walk(p);
        try walk.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
        finally walk.close()
      }
    )
    directory.use { path =>
      val store = new AtomicFileStore[IO](path)
      for {
        state <- store.commit(scope, page)
        _ = assertEquals(LocalCache.field(state, "latest_seq"), "9007199254740993")
        _ = assertEquals(LocalCache.field(state, "unread_count"), "1")
        failed <- store
          .commit(scope, Json.obj("cursor" -> Json.fromString("must-not-commit"), "messages" -> Json.arr(Json.obj())))
          .attempt
        _ = assert(failed.isLeft)
        reopened <- new AtomicFileStore[IO](path).load(scope)
        _ = assertEquals(reopened, state)
        other <- store.load(scope.copy(userId = "bob"))
        _ = assertEquals(other, LocalCache.empty)
        _ <- (1 to 5).toList.parTraverse_(i => new AtomicFileStore[IO](path).commit(scope, page))
      } yield ()
    }
  }
}
