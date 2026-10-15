# Java platform shim

The managed platform builds and starts Python and TypeScript agents only. This shim lets it start a
Java agent: a tiny Python package whose `run()` replaces the process with the JVM. The JRE comes from
the `jdk4py` wheel; the agent's jars ship in the source tree.

Don't edit this directory to deploy an agent; run `scripts/package-java-agent.sh`, which copies it.
