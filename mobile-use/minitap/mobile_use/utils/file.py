import json
import re
from typing import IO


def strip_json_comments(text: str) -> str:
    text = re.sub(r"//.*?$", "", text, flags=re.MULTILINE)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return text


def load_jsonc(file: IO) -> dict:
    return json.loads(strip_json_comments(file.read()))
