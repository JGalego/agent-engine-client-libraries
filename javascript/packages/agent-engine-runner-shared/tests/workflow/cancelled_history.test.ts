import { create, fromJson } from "@bufbuild/protobuf";
import { createHash } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import {
  AttemptContextSchema,
  StateSnapshotSchema,
  WorkflowIdentitySchema,
  WorkflowMessageSchema,
  CancelledHistoryState,
  cancelledHistoryReport,
  cancelledHistoryMessagesHash,
  FinalizeStepCommandSchema,
  currentCancelledHistory,
  runWithAttemptContext,
} from "../../src/workflow/index.js";
import { WorkflowClient } from "../../src/workflow/client.js";

function message(id: string, content: string) {
  return fromJson(WorkflowMessageSchema, {
    id,
    content,
    role: "MESSAGE_ROLE_USER",
  });
}

describe("cancelled history capture", () => {
  it("retains authoritative FinalizeStep acknowledgement hashes without changing the entries API", async () => {
    const hash = "a".repeat(64);
    const fetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            entries: [],
            source_state_hash: hash,
          }),
          { status: 200 },
        ),
    );
    const client = new WorkflowClient("http://localhost", { fetch });
    const command = create(FinalizeStepCommandSchema);
    expect(await client.finalizeStepWithAcknowledgement(command)).toEqual({
      entries: [],
      sourceStateHash: hash,
    });
    expect(await client.finalizeStep(command)).toEqual([]);
  });

  it("owns the acknowledged snapshot and returns detached display copies", () => {
    const history = new CancelledHistoryState();
    const opener = message("original", "hello");
    history.setInput(opener);
    expect(history.capture()?.acceptedStepOrdinal).toBe(0n);
    expect(history.capture()?.sourceStateHash).toBeUndefined();
    const state = create(StateSnapshotSchema, {
      messages: [opener, message("accepted", "settled")],
      properties: { count: 1 },
    });
    const hash = "acknowledged-source-hash";
    history.acknowledge(state, 2n, hash);
    if (state.properties === undefined || state.messages[0] === undefined)
      throw new Error("missing test state");
    state.properties["count"] = 999;
    state.messages[0].id = "mutated";
    const first = history.capture();
    if (first?.messages[0] === undefined) throw new Error("missing capture");
    first.messages[0].id = "caller-mutation";
    expect(history.capture()).toMatchObject({
      openerMessageId: "original",
      acceptedStepOrdinal: 2n,
      sourceStateHash: hash,
      messages: [{ id: "original" }, { id: "accepted" }],
    });
    history.close();
    history.acknowledge(state, 3n);
    expect(history.capture()).toBeNull();
  });

  it("projects an unacknowledged capture to the opener only", () => {
    // OE's replacement-before-replay fallback requires an opener-only report.
    const history = new CancelledHistoryState();
    history.setInput(message("opener", "hello"));
    const capture = history.capture();
    expect(capture?.messages.map((item) => item.id)).toEqual(["opener"]);
    expect(capture?.acceptedStepOrdinal).toBe(0n);
    expect(capture?.sourceStateHash).toBeUndefined();
  });

  it("restores the immutable opener without altering the acknowledged checkpoint or hash", () => {
    const history = new CancelledHistoryState();
    history.setInput(message("opener", "hello"));
    const state = create(StateSnapshotSchema, {
      messages: [message("result", "done")],
    });
    history.acknowledge(state, 1n, "original-acknowledgement");
    expect(history.capture()?.messages.map((item) => item.id)).toEqual([
      "opener",
      "result",
    ]);
    expect(history.capture()?.openerMessageId).toBe("opener");
    expect(history.capture()?.sourceStateHash).toBe("original-acknowledgement");
    expect(state.messages.map((item) => item.id)).toEqual(["result"]);
  });

  it("isolates concurrent attempts and closes detached holders", async () => {
    let release!: () => void;
    const barrier = new Promise<void>((resolve) => {
      release = resolve;
    });
    let detached: CancelledHistoryState | null = null;
    await Promise.all(
      ["a", "b"].map((id) =>
        runWithAttemptContext(
          create(AttemptContextSchema, { attemptId: id, fencingToken: 1n }),
          async () => {
            const history = currentCancelledHistory();
            if (history === null) throw new Error("missing history scope");
            history.setInput(message(id, id));
            if (id === "a") {
              detached = history;
              await barrier;
            } else release();
            expect(currentCancelledHistory()?.capture()?.openerMessageId).toBe(
              id,
            );
          },
        ),
      ),
    );
    expect((detached as CancelledHistoryState | null)?.capture()).toBeNull();
    expect(currentCancelledHistory()).toBeNull();
  });

  it("coalesces reporting so scratch cleanup waits for in-flight delivery", async () => {
    const history = new CancelledHistoryState();
    let resolve!: () => void;
    const blocked = new Promise<void>((r) => {
      resolve = r;
    });
    history.reporter = vi.fn(() => blocked);
    const first = history.reportBeforeCleanup();
    expect(history.reportBeforeCleanup()).toBe(first);
    await Promise.resolve();
    expect(history.reporter).toHaveBeenCalledTimes(1);
    resolve();
    await first;
  });

  it("posts snake_case protobuf JSON and decimal strings without precision loss", async () => {
    const attempt = create(AttemptContextSchema, {
      attemptId: "attempt",
      fencingToken: 9007199254740993n,
      workflowIdentity: create(WorkflowIdentitySchema, {
        executionId: "execution",
        sessionId: "session",
      }),
    });
    const report = cancelledHistoryReport(attempt, {
      openerMessageId: "opener",
      messages: [
        fromJson(WorkflowMessageSchema, {
          role: "MESSAGE_ROLE_USER",
          id: "opener",
          content: "héllo<&>\u2028\u2029",
          additional_kwargs: {
            fraction: 0.1,
            small: 1e-7,
            large: 1e21,
            "10": "ten",
            "2": "two",
            "𐀀": "value",
          },
        }),
      ],
      acceptedStepOrdinal: 0n,
    });
    const fetch = vi.fn(async (_input: unknown, init?: RequestInit) => {
      const body = String(init?.body);
      const rawMessages = body.slice(
        body.indexOf('"messages":') + '"messages":'.length,
        -1,
      );
      expect(rawMessages).toBe(report.messagesJson);
      const decoded = JSON.parse(body) as Record<string, unknown>;
      expect(decoded["messagesJson"]).toBeUndefined();
      expect(decoded["messages"]).toEqual(JSON.parse(report.messagesJson));
      expect(decoded["payload_hash"]).toBe(
        createHash("sha256").update(rawMessages).digest("hex"),
      );
      return new Response(null, { status: 204 });
    });
    const client = new WorkflowClient("http://localhost", {
      fetch: fetch as typeof globalThis.fetch,
    });
    await client.reportCancelledHistory(report);
    expect(String(fetch.mock.calls[0]?.[0])).toBe(
      "http://localhost/executor/cancelled-history",
    );
    expect(report).toMatchObject({
      workflow_identity: { execution_id: "execution", session_id: "session" },
      fencing_token: "9007199254740993",
      accepted_step_ordinal: "0",
      base_session_revision: "0",
      messages: [
        {
          role: "MESSAGE_ROLE_USER",
          content: "héllo<&>\u2028\u2029",
          id: "opener",
        },
      ],
      payload_hash: cancelledHistoryMessagesHash(report.messagesJson),
    });
    report.messages.length = 0;
    await client.reportCancelledHistory(report);
  });

  it("bounds an oversized tool result to fit OE decode limits", () => {
    // A 300 KiB tool result finalizes fine but exceeds OE's 256 KiB per-message
    // bound; the projection must still produce an acceptable report.
    const opener = message("opener", "hello");
    const huge = fromJson(WorkflowMessageSchema, {
      role: "MESSAGE_ROLE_USER",
      id: "tool-1",
      content: "x".repeat(300 << 10),
    });
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [opener, huge],
        acceptedStepOrdinal: 3n,
        sourceStateHash: "hash-3",
      },
    );
    expect(report.messages).toHaveLength(2);
    const [first, second] = report.messages;
    expect((first as { id?: string }).id).toBe("opener");
    expect((second as { role?: string }).role).toBe("MESSAGE_ROLE_ASSISTANT");
    expect(JSON.stringify(second)).toContain("omitted");
    expect(Buffer.byteLength(report.messagesJson, "utf8")).toBeLessThanOrEqual(
      1 << 20,
    );
    expect(report.accepted_step_ordinal).toBe("3");
    expect(report.source_state_hash).toBe("hash-3");
  });

  it("caps the projection at the message-count limit", () => {
    const opener = message("opener", "hello");
    const filler = Array.from({ length: 1500 }, (_value, index) =>
      message(`m${index}`, "x"),
    );
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [opener, ...filler],
        acceptedStepOrdinal: 0n,
      },
    );
    expect(report.messages.length).toBeLessThanOrEqual(1000);
    expect((report.messages[0] as { id?: string }).id).toBe("opener");
    expect((report.messages[1] as { role?: string }).role).toBe(
      "MESSAGE_ROLE_ASSISTANT",
    );
    expect((report.messages.at(-1) as { id?: string }).id).toBe("m1499");
  });

  it("leaves a small transcript unchanged", () => {
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [message("opener", "hello"), message("a1", "world")],
        acceptedStepOrdinal: 0n,
      },
    );
    expect(report.messages.map((item) => (item as { id?: string }).id)).toEqual(
      ["opener", "a1"],
    );
  });

  it("marks a suspension capture provisional without changing the payload hash", () => {
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [message("opener", "hello")],
        acceptedStepOrdinal: 0n,
      },
      true,
    );
    expect(report.provisional).toBe(true);
    expect(report.payload_hash).toBe(
      cancelledHistoryMessagesHash(report.messagesJson),
    );
  });

  it("measures UTF-8 bytes for multibyte content", () => {
    const opener = message("opener", "hello");
    const cjk = message("tool-1", "雪".repeat(100_000));
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [opener, cjk],
        acceptedStepOrdinal: 0n,
      },
    );
    expect(report.messages).toHaveLength(2);
    expect((report.messages[0] as { id?: string }).id).toBe("opener");
    expect((report.messages[1] as { role?: string }).role).toBe(
      "MESSAGE_ROLE_ASSISTANT",
    );
    expect(Buffer.byteLength(report.messagesJson, "utf8")).toBeLessThanOrEqual(
      1 << 20,
    );
  });

  it("accounts for Go's HTML escaping in the per-row bound", () => {
    const opener = message("opener", "hello");
    const html = message("tool-1", "<p>x</p>".repeat(10_000));
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [opener, html],
        acceptedStepOrdinal: 0n,
      },
    );
    expect(report.messages).toHaveLength(2);
    for (const row of report.messages) {
      const serialized = JSON.stringify(row);
      const count = (needle: string): number =>
        serialized.split(needle).length - 1;
      const domain =
        Buffer.byteLength(serialized, "utf8") +
        5 * (count("<") + count(">") + count("&"));
      expect(domain).toBeLessThanOrEqual(256 << 10);
    }
  });

  it("shrinks an oversized opener while keeping its identity", () => {
    const opener = message("opener", "x".repeat(300 << 10));
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [opener],
        acceptedStepOrdinal: 0n,
      },
    );
    const first = report.messages[0] as {
      id?: string;
      role?: string;
      content?: unknown;
    };
    // Exactly one bounded USER row: the opener keeps its identity, no separate
    // completed-work marker, so the report stays opener-only.
    expect(report.messages).toHaveLength(1);
    expect(first.id).toBe("opener");
    expect(first.role).toBe("MESSAGE_ROLE_USER");
    expect(JSON.stringify(first.content)).toContain("omitted");
    expect(Buffer.byteLength(report.messagesJson, "utf8")).toBeLessThanOrEqual(
      1 << 20,
    );
  });

  it("budgets a large opener against an over-budget tail", () => {
    const opener = message("opener", "x".repeat(200 << 10));
    const tail = Array.from({ length: 20 }, (_value, index) =>
      message(`m${index}`, "y".repeat(60 << 10)),
    );
    const report = cancelledHistoryReport(
      create(AttemptContextSchema, { attemptId: "attempt", fencingToken: 1n }),
      {
        openerMessageId: "opener",
        messages: [opener, ...tail],
        acceptedStepOrdinal: 0n,
      },
    );
    expect(Buffer.byteLength(report.messagesJson, "utf8")).toBeLessThanOrEqual(
      1 << 20,
    );
    expect((report.messages[0] as { id?: string }).id).toBe("opener");
  });
});
