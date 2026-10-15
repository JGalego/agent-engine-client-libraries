"""Hands the runner process to the Java runtime.

The platform starts every agent from its Python entrypoint: it imports the ``app`` named in
``agent.yaml`` and calls ``app.run()``. ``run`` replaces this process with the Java launcher, which
serves the same ports and reads the same environment. The agent's jars are in ``java/lib`` and its
own ``agent.yaml`` is ``java/agent.yaml``.

Two environment variables help when the JVM misbehaves in the sandbox:

* ``JAVA_OPTS``: extra JVM options, such as ``-Xmx512m``.
* ``JAVA_SHIM_NET_PROBE``: a host name. Before starting the JVM, log what the sandbox's DNS returns
  for it and which of its addresses accept a connection.
"""

import os
import shlex
import socket
from pathlib import Path

from agent_engine_sdk import BaseApp

JAVA_DIR = Path(os.environ.get("JAVA_AGENT_DIR", Path.cwd() / "java"))


def probe_network(host: str, port: int = 443) -> None:
    """Prints how ``host`` resolves and connects from this sandbox, and which proxy variables exist."""
    print(f"net-probe: {host}:{port}", flush=True)
    names = sorted(name for name in os.environ if "proxy" in name.lower())
    print(f"net-probe: proxy variables set: {names or 'none'}", flush=True)
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as error:
        print(f"net-probe: lookup failed: {error}", flush=True)
        return
    for family, _, _, _, address in infos:
        label = "IPv6" if family == socket.AF_INET6 else "IPv4"
        try:
            with socket.socket(family, socket.SOCK_STREAM) as sock:
                sock.settimeout(3)
                sock.connect(address)
            print(f"net-probe: {label} {address[0]} connects", flush=True)
        except OSError as error:
            print(f"net-probe: {label} {address[0]} fails: {error}", flush=True)


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

        probe_host = os.environ.get("JAVA_SHIM_NET_PROBE")
        if probe_host:
            probe_network(probe_host)
        os.environ["AGENT_CONFIG"] = str(JAVA_DIR / "agent.yaml")
        options = shlex.split(os.environ.get("JAVA_OPTS", ""))
        classpath = str(JAVA_DIR / "lib" / "*")
        command = [str(JAVA), *options, "-cp", classpath, "com.mongodb.agentengine.runner.Launcher"]
        os.execv(str(JAVA), command)


app = JavaApp("java-agent")
