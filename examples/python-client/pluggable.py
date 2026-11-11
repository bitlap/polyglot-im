"""显式传入适配器；资源由调用方的 async with 管理。"""

from polyglot_im import (
    ApiClient,
    AuthenticatedTransport,
    HttpTransport,
    ImClient,
    JsonCodec,
    LocalStore,
    Scope,
    SocketConnector,
    TokenProvider,
    WireCodec,
)


def create_client(
    scope: Scope,
    device_id: str,
    transport: HttpTransport,
    tokens: TokenProvider,
    socket_connector: SocketConnector,
    store: LocalStore,
    codec: WireCodec | None = None,
) -> ImClient:
    selected = codec if codec is not None else JsonCodec()
    return ImClient(
        ApiClient(AuthenticatedTransport(transport, tokens), codec=selected),
        scope.environment,
        scope,
        device_id,
        store,
        socket_connector=socket_connector,
        codec=selected,
    )
