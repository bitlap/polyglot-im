package polyglot.im.server.ports

/** 用户收件箱、单聊和设备操作的事务仓储集合。 */
trait ClientRepository[F[_]] extends InboxRepository[F] with ConversationRepository[F] with DeviceRepository[F]

/** 服务端可靠存储端口，汇总客户端操作、推送、集群配额和生命周期。 */
trait ImRepository[F[_]]
    extends ClientRepository[F]
    with PushRepository[F]
    with ClusterRepository[F]
    with RepositoryLifecycle[F]
