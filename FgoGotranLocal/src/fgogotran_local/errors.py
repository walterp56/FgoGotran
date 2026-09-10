class StudioError(Exception):
    """An expected, user-facing Studio error."""

    def __init__(self, message: str, status_code: int = 400) -> None:
        super().__init__(message)
        self.status_code = status_code


class ConfigError(StudioError):
    """A configuration validation or persistence error."""

