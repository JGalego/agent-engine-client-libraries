/**
 * The runtime that serves an agent on the Atlas Agent Engine.
 *
 * <p>The platform's orchestration engine (OE) dispatches executions to an <em>agent runtime</em>
 * (AER) over HTTP and routes tool calls to a <em>tool server</em>. This package implements both
 * servers, the launcher that starts them for an agent, and the client the runtime uses to report
 * back to the OE. An agent plugs in by providing a {@link
 * com.mongodb.agentengine.runner.RunnableApp}.
 */
package com.mongodb.agentengine.runner;
