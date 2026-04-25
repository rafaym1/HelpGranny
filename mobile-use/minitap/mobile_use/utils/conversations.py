from typing import TypeGuard

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, ToolMessage


def is_ai_message(message: BaseMessage) -> TypeGuard[AIMessage]:
    return isinstance(message, AIMessage)


def is_human_message(message: BaseMessage) -> TypeGuard[HumanMessage]:
    return isinstance(message, HumanMessage)


def is_tool_message(message: BaseMessage) -> TypeGuard[ToolMessage]:
    return isinstance(message, ToolMessage)


def is_tool_for_name(tool_message: ToolMessage, name: str) -> bool:
    return tool_message.name == name


def get_screenshot_message_for_llm(screenshot_base64: str):
    prefix = "" if screenshot_base64.startswith("data:image") else "data:image/jpeg;base64,"
    return HumanMessage(
        content=[
            {
                "type": "image_url",
                "image_url": {"url": f"{prefix}{screenshot_base64}"},
            }
        ]
    )
