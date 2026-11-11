package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

import io.circe.Json

trait DeviceRepository[F[_]] {
  def register(who: Identity, device: String): F[Unit]
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit]
  def patchDevice(
      who: Identity,
      device: String,
      token: Option[String],
      muted: Option[Boolean],
      preview: Option[Boolean]
  ): F[Unit]
  def preferences(who: Identity, device: String): F[Json]
  def logout(who: Identity, device: String): F[Unit]
  def active(who: Identity, device: String): F[Boolean]
  def ack(who: Identity, device: String, value: Long): F[Unit]
  def ticket(who: Identity, device: String): F[String]
  def consumeTicket(token: String): F[(Identity, String)]
}
