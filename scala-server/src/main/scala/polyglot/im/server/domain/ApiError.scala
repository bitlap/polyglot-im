package polyglot.im.server.domain

/** 服务端可映射为 HTTP 错误信封的业务异常；保留状态、稳定码和脱敏提示。 */
final case class ApiError(status: Int, code: String, message: String) extends RuntimeException(message)

/** 服务端错误定义的唯一入口；调用方只引用码值、提示或动态模板，不回显原始输入。 */
object ApiError {

  /** 稳定的业务错误码；同一码可用于不同 HTTP 状态和业务场景。 */
  object Code {
    val DeviceSessionClosed    = "DEVICE_SESSION_CLOSED"
    val Forbidden              = "FORBIDDEN"
    val IdempotencyConflict    = "IDEMPOTENCY_CONFLICT"
    val IdentityMismatch       = "IDENTITY_MISMATCH"
    val InternalError          = "INTERNAL_ERROR"
    val InvalidArgument        = "INVALID_ARGUMENT"
    val InvalidCursor          = "INVALID_CURSOR"
    val InvalidTicket          = "INVALID_TICKET"
    val NotFound               = "NOT_FOUND"
    val NotReady               = "NOT_READY"
    val Overloaded             = "OVERLOADED"
    val PayloadTooLarge        = "PAYLOAD_TOO_LARGE"
    val RateLimited            = "RATE_LIMITED"
    val ResetRequired          = "RESET_REQUIRED"
    val Unauthorized           = "UNAUTHORIZED"
    val UnsupportedMessageType = "UNSUPPORTED_MESSAGE_TYPE"
  }

  /** 业务异常及启动配置校验的提示；动态参数仅传受控字段名，不传凭据或配置值。 */
  object Message {
    val CursorAccountMismatch      = "cursor does not belong to this account"
    val ServiceScopeDenied         = "recipient or type is outside service scope"
    val UserIdentityRequired       = "user identity required"
    val IdentityMismatch           = "credential differs from expected account"
    val RoleNotPermitted           = "credential role is not permitted"
    val RateLimited                = "retry after one minute"
    val InvalidSequence            = "invalid sequence"
    val UnsupportedType            = "unsupported type"
    val PayloadObjectRequired      = "payload must be an object"
    val PayloadTooLarge            = "payload exceeds 16 KiB"
    val InvalidNotificationGroup   = "invalid notification group"
    val UnsupportedAction          = "unsupported action"
    val InvalidVersionId           = "invalid version id"
    val InvalidCredential          = "invalid credential"
    val InvalidUserIdentity        = "invalid user identity"
    val ExpiredCredential          = "invalid or expired credential"
    val ExpiredBusinessSession     = "invalid or expired business session"
    val IdempotencyContentConflict = "idempotency key already used with different content"
    val ConversationNotFound       = "conversation not found"
    val PeerRequired               = "direct conversation requires another user"
    val ChatTextRequired           = "only chat.text is supported"
    val InvalidTextLength          = "text must contain 1..2048 characters"
    val TextPayloadRequired        = "only text payload is supported"
    val IdempotencyConflict        = "idempotency key already used"
    val ReadPositionAhead          = "read position exceeds current messages"
    val MessageNotFound            = "message not found"
    val DeviceSessionRevoked       = "device session revoked"
    val ResetRequired              = "cursor expired; obtain a new snapshot"
    val CursorAhead                = "cursor is ahead of server"
    val InvalidMessageIds          = "message_ids requires 1 to 100 unique IDs"
    val DeviceSessionClosed        = "register an active device session before push token updates"
    val InvalidDeliveryCursor      = "invalid delivery cursor"
    val InvalidTicket              = "invalid or expired websocket ticket"
    val Overloaded                 = "retry with backoff"
    val PersistenceNotConfigured   = "IM persistence is not configured"
    val RouteNotImplemented        = "Route not implemented"
    val RequestFailed              = "request failed"
    val InvalidIdentityEncoding    = "invalid expected identity encoding"
    val RequestTooLarge            = "request exceeds 64 KiB"
    val InvalidJson                = "invalid JSON"
    val MessageIdsOnly             = "only message_ids is accepted"
    val MessageIdsArrayRequired    = "message_ids must be a string array"
    val TokenRequired              = "missing token"
    val InvalidApnsToken           = "invalid APNs device token"
    val TicketRequired             = "ticket required"
    val InvalidFrame               = "invalid frame"
    val AcknowledgementAhead       = "ack exceeds delivered position"
    val UnsupportedFrame           = "unsupported websocket frame"

    val PostgresUrlRequired         = "PostgreSQL JDBC URL required"
    val DatabaseUserRequired        = "database user required"
    val InvalidPoolSize             = "database pool size must be positive"
    val InvalidConnectionTimeout    = "database connection timeout must be >= 250 ms"
    val InvalidDatabaseTimeouts     = "invalid database timeouts"
    val InvalidPushParallelism      = "invalid push parallelism"
    val InvalidPushTimeouts         = "invalid push timeouts"
    val InvalidPushLease            = "lease must exceed job timeout by at least five seconds"
    val InvalidRateLimits           = "rate limits must be positive"
    val InvalidInFlightLimit        = "maximum in-flight requests must be positive"
    val InvalidCursorSecret         = "cursor signing secret must be at least 32 bytes"
    val InvalidJwtSecret            = "JWT secrets must have at least 32 bytes"
    val JwtSecretsMustDiffer        = "user and service JWT secrets must differ"
    val IntrospectionClientRequired = "introspection requires HTTP client"
    val InvalidConfigPath           = "invalid config file path"
    val ConfigUsage                 = "usage: run [--config /path/application.conf]"
    val UnreadableConfig            = "cannot read or resolve HOCON configuration; check file, syntax and substitutions"

    /** 列出读取失败的配置字段路径。 */
    def invalidConfigurationFields(path: String): String = "invalid configuration fields: " + path

    /** 描述校验失败的配置字段路径。 */
    def invalidConfigurationField(path: String): String = "invalid configuration field: " + path

    /** 描述缺失的配置字段路径。 */
    def missingConfigurationField(path: String): String = "missing configuration field: " + path

    /** 描述缺少的受控字段名。 */
    def missingField(name: String): String = s"missing $name"

    /** 描述非法的受控字段名。 */
    def invalidField(name: String): String = s"invalid $name"

    /** 描述应为字符串的受控字段名。 */
    def stringRequired(name: String): String = s"$name must be a string"

    /** 描述超长的受控字段名。 */
    def fieldTooLong(name: String): String = s"$name too long"

    /** 描述应为布尔值的受控字段名。 */
    def booleanRequired(name: String): String = s"$name must be a boolean"
  }
}
