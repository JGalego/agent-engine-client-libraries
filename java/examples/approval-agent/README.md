# approval-agent

A Java agent that pauses for human approval, which Atlas Agent Engine calls human-in-the-loop (HITL).
It needs no model: the example is about the suspend and resume cycle.

| File | Purpose |
|---|---|
| [`ApprovalApp.java`](src/main/java/com/example/approval/ApprovalApp.java) | The `RunnableApp`, with a review tool that throws `SuspendException` |
| [`ApprovalAgent.java`](src/main/java/com/example/approval/ApprovalAgent.java) | Calls the tool on the first turn and acts on the decision on the resumed turn |
| [`agent.yaml`](agent.yaml) | Names the app: `com.example.approval.ApprovalApp:app` |
| [`Dockerfile`](Dockerfile) | Packages the agent and runner into a runner image |

## How it works

1. The first turn calls `request_review`. The tool throws `SuspendException` with a reason and a
   context for the reviewer, and the runtime reports the execution to the Orchestration Engine
   (OE) as `suspended`.
2. A reviewer resumes the execution with a decision.
3. The OE dispatches the execution again with `resume` set. The decision is in
   `RequestContext.resumeData()`, under the `human_review` key of the resume request.

## Run it in the local stack

Build and start the stack as in [`hello-agent`](../hello-agent/README.md), using this image
(`approval-java-agent:dev`) in the override file. Then:

```bash
curl -X POST http://localhost:3000/invoke -H 'Content-Type: application/json' \
  -d '{"message": "Delete the 2023 archive", "user_id": "ana"}'
# {"execution_id":"<id>","status":"suspended","suspend_reason":"awaiting_human_review",
#  "suspend_context":{"task_description":"Delete the 2023 archive","allowed_decisions":["approve","reject"]}, ...}

curl -X POST "http://localhost:3000/resume/<id>?org_id=<org>&project_id=<project>&workspace_id=<workspace>" \
  -H 'Content-Type: application/json' -d '{"human_review": {"decision": "approve"}}'
```

The ids are in the `ORG_ID`, `PROJECT_ID` and `APP_ID` variables of the `app` container. On the
managed platform the same resume is
[`POST /executions/{id}/resume`](https://www.mongodb.com/docs/agentengine/deploy/human-in-the-loop/)
with a flat `{"decision": "approve"}` body.
