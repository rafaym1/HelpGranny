import requests
from minitap.mobile_use.utils.logger import get_logger

logger = get_logger(__name__)


def curl_from_request(req: requests.PreparedRequest) -> str:
    """Converts a requests.PreparedRequest object to a valid cURL command string."""
    command = ["curl", f"-X {req.method}"]

    for key, value in req.headers.items():
        command.append(f'-H "{key}: {value}"')

    if req.body:
        body = req.body
        if isinstance(body, bytes):
            body = body.decode("utf-8")
        # Escape single quotes in the body for shell safety
        body = body.replace("'", "'\\''")
        command.append(f"-d '{body}'")

    command.append(f"'{req.url}'")

    return " ".join(command)


def logging_hook(response, *args, **kwargs):
    """Hook to log the request as a cURL command."""
    curl_command = curl_from_request(response.request)
    logger.debug(f"\n--- cURL Command ---\n{curl_command}\n--------------------")


def get_session_with_curl_logging() -> requests.Session:
    """Returns a requests.Session with cURL logging enabled."""
    session = requests.Session()
    session.hooks["response"] = [logging_hook]
    return session
