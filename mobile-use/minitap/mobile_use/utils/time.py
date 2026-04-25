from datetime import datetime


def convert_timestamp_to_str(ts: float) -> str:
    dt = datetime.fromtimestamp(ts)
    return dt.strftime("%Y-%m-%dT%H-%M-%S")
