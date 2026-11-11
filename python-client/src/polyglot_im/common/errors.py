class ImError(Exception):
    """IM 请求失败，保存 HTTP 状态、稳定业务错误码及可展示的错误信息。"""

    def __init__(self, status: int, code: str, message: str):
        """保存 HTTP 状态、稳定业务码和错误说明，供重试与界面分类处理。"""
        super().__init__(message)
        self.status, self.code = status, code
