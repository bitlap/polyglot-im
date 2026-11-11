package polyglot.im.server.ports

trait ClientRepository[F[_]] extends InboxRepository[F] with ConversationRepository[F] with DeviceRepository[F]
trait ImRepository[F[_]]
    extends ClientRepository[F]
    with PushRepository[F]
    with ClusterRepository[F]
    with RepositoryLifecycle[F]
