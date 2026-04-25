from minitap.mobile_use.sdk.builders.agent_config_builder import AgentConfigBuilder
from minitap.mobile_use.sdk.builders.task_request_builder import TaskRequestCommonBuilder


class BuildersWrapper:
    @property
    def AgentConfig(self) -> AgentConfigBuilder:
        return AgentConfigBuilder()

    @property
    def TaskDefaults(self) -> TaskRequestCommonBuilder:
        return TaskRequestCommonBuilder()


Builders = BuildersWrapper()
