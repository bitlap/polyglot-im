package polyglot.im.server

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** 初始化服务：只有存活探针，未接入数据库与 IM 业务路由。 */
object Main {
  def main(args: Array[String]): Unit = {
    val host     = sys.env.getOrElse("POLYGLOT_IM_HOST", "127.0.0.1")
    val port     = sys.env.getOrElse("POLYGLOT_IM_PORT", "8090").toInt
    val server   = HttpServer.create(new InetSocketAddress(host, port), 0)
    val executor = Executors.newFixedThreadPool(4)
    server.setExecutor(executor)
    server.createContext("/", exchange => handle(exchange))
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.stop(1)
      executor.shutdown()
    }))
    server.start()
    println(s"polyglot-im bootstrap listening on http://$host:$port; IM routes are not implemented")
  }

  private def handle(exchange: HttpExchange): Unit = {
    val (status, body) = (exchange.getRequestMethod, exchange.getRequestURI.getPath) match {
      case ("GET", "/health/live")  => (200, """{"status":"alive","stage":"bootstrap"}""")
      case ("GET", "/health/ready") => (503, """{"code":"NOT_READY","message":"IM persistence is not configured"}""")
      case _                        => (404, """{"code":"NOT_FOUND","message":"Route not implemented"}""")
    }
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val output = exchange.getResponseBody
    try output.write(bytes)
    finally {
      output.close()
      exchange.close()
    }
  }
}
