"""Hands the runner process to the Java runtime.

The platform starts every agent from its Python entrypoint: it imports the ``app`` named in
``agent.yaml`` and calls ``app.run()``. ``run`` replaces this process with the Java launcher, which
serves the same ports and reads the same environment. The agent's jars are in ``java/lib`` and its
own ``agent.yaml`` is ``java/agent.yaml``.
"""

import os
from pathlib import Path

from agent_engine_sdk import BaseApp

JAVA_DIR = Path(os.environ.get("JAVA_AGENT_DIR", Path.cwd() / "java"))


class JavaApp(BaseApp):
    """Satisfies the platform's ``BaseApp`` check; the Java runtime owns tools and the agent."""

    def get_tool_definitions(self):
        return []

    def tools(self):
        return []

    def tool(self, *args, **kwargs):
        return lambda fn: fn

    def entrypoint(self, fn):
        return fn

    def run(self) -> None:
        from jdk4py import JAVA

        os.environ["AGENT_CONFIG"] = str(JAVA_DIR / "agent.yaml")
        classpath = str(JAVA_DIR / "lib" / "*")
        os.execv(str(JAVA), [str(JAVA), "-cp", classpath, "com.mongodb.agentengine.runner.Launcher"])


app = JavaApp("java-agent")
