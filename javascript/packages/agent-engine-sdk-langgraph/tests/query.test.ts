import { AIMessage, HumanMessage } from "@langchain/core/messages";
import type { MongoDBSaver } from "@langchain/langgraph-checkpoint-mongodb";
import type { MongoClient } from "mongodb";
import { describe, expect, test } from "vitest";
import { LangGraphQueryPlugin } from "../src/query.js";

function fakeClient(): MongoClient {
  return {
    db: () => ({
      collection: () => ({}),
    }),
  } as unknown as MongoClient;
}

function fakeSaver(message: unknown): MongoDBSaver {
  return {
    checkpointCollectionName: "checkpoints",
    checkpointWritesCollectionName: "checkpoint_writes",
    list: async function* () {
      yield {
        checkpoint: {
          ts: "2026-01-02T03:04:05.000Z",
          channel_values: { messages: [message] },
        },
      };
    },
  } as unknown as MongoDBSaver;
}

/** Saver stub that records the thread_id of every `list` call. */
function recordingSaver(requestedThreadIds: string[]): MongoDBSaver {
  return {
    checkpointCollectionName: "checkpoints",
    checkpointWritesCollectionName: "checkpoint_writes",
    list: async function* (config: { configurable?: { thread_id?: string } }) {
      requestedThreadIds.push(config.configurable?.thread_id ?? "");
      // No checkpoints — the assertion is on which keys were queried.
      yield* [];
    },
  } as unknown as MongoDBSaver;
}

describe("LangGraphQueryPlugin", () => {
  test("round-trips tool call args into a JSON-safe shape", async () => {
    const message = new AIMessage({
      content: "",
      tool_calls: [
        {
          id: "tc-json",
          name: "my_tool",
          args: {
            city: "Kyoto",
            visited_at: new Date("2026-01-02T03:04:05.000Z"),
          },
          type: "tool_call",
        },
      ],
    } as unknown as ConstructorParameters<typeof AIMessage>[0]);
    const plugin = new LangGraphQueryPlugin({
      saver: fakeSaver(message),
      client: fakeClient(),
      dbName: "unused",
    });

    const result = await plugin.getMessagesForSession("sess-json");

    expect(result.messages[0]?.tool_calls?.[0]?.args).toEqual({
      city: "Kyoto",
      visited_at: "2026-01-02T03:04:05.000Z",
    });
  });

  test("queries only the workspace-scoped key when a scope is known", async () => {
    const requested: string[] = [];
    const plugin = new LangGraphQueryPlugin({
      saver: recordingSaver(requested),
      client: fakeClient(),
      dbName: "unused",
      workspaceIdResolver: () => "ws-1",
    });

    await plugin.getMessagesForSession("sess-1");

    // The bare unscoped key must never be queried once a scope exists —
    // it is readable/writable by every workspace on the shared store.
    expect(requested).toEqual(["sess-1:ws-1"]);
  });

  test("fails closed only when a resolver signals a genuinely unresolvable scope", async () => {
    const requested: string[] = [];
    const plugin = new LangGraphQueryPlugin({
      saver: recordingSaver(requested),
      client: fakeClient(),
      dbName: "unused",
      // A custom resolver returning null says "I cannot decide" — unlike "",
      // which is a deliberate explicitly-unscoped scope.
      workspaceIdResolver: () => null,
    });

    await expect(plugin.getMessagesForSession("sess-1")).rejects.toThrow(
      /workspace scope unavailable/,
    );
    expect(requested).toEqual([]);
  });

  test("an explicitly unscoped runtime (resolver returns '') reads bare keys", async () => {
    // Local dev / tests: no APP_ID, so writes are bare-keyed and reads must
    // match — this is the normal `agentengine dev up` history path.
    const requested: string[] = [];
    const plugin = new LangGraphQueryPlugin({
      saver: recordingSaver(requested),
      client: fakeClient(),
      dbName: "unused",
      workspaceIdResolver: () => "",
    });

    await plugin.getMessagesForSession("sess-1");

    expect(requested).toEqual(["sess-1"]);
  });
});

// ---------------------------------------------------------------------------
// Cancelled-turn stamps (mirror of Python test_query.py's stamp section)
//
// The write path stamps the turn-opening message's own additional_kwargs with
// `cancelled_turn` (see cancelled_turn.ts). The reader never scans for marker
// checkpoints: the stamp flows through the standard kwargs passthrough, so it
// survives exactly as long as the message itself — independent of the bounded
// checkpoint window.
// ---------------------------------------------------------------------------

function plainTuple(ts: string, messages: unknown[]) {
  return {
    checkpoint: { ts, channel_values: { messages } },
    config: { configurable: { thread_id: "t", checkpoint_ns: "" } },
    metadata: { source: "loop", step: 1, parents: {} },
  };
}

function tupleSaver(tuples: unknown[]): MongoDBSaver {
  return {
    checkpointCollectionName: "checkpoints",
    checkpointWritesCollectionName: "checkpoint_writes",
    list: async function* () {
      yield* tuples;
    },
  } as unknown as MongoDBSaver;
}

function cancelledStamps(result: {
  messages: Array<{ additional_kwargs?: unknown }>;
}): unknown[] {
  return result.messages.map((m) => m.additional_kwargs ?? null);
}

/** Saver + client stubs rich enough for the summaries path. */
function summariesHarness(args: {
  messages: unknown[];
  writesPreview?: string;
}): { saver: MongoDBSaver; client: MongoClient } {
  const saver = {
    checkpointCollectionName: "checkpoints",
    checkpointWritesCollectionName: "checkpoint_writes",
    serde: {
      loadsTyped: async () => [
        new HumanMessage({ content: args.writesPreview ?? "", id: "h9" }),
      ],
    },
    list: async function* () {
      yield {
        checkpoint: {
          ts: "2026-01-02T03:04:05.000Z",
          channel_values: { messages: args.messages },
        },
      };
    },
  } as unknown as MongoDBSaver;
  const client = {
    db: () => ({
      collection: (name: string) => ({
        aggregate: () => ({
          toArray: async () =>
            name === "checkpoints"
              ? [
                  {
                    _id: "s1",
                    latest_ts: new Date("2026-01-02T03:04:05.000Z"),
                    first_ts: new Date("2026-01-02T03:04:05.000Z"),
                  },
                ]
              : args.writesPreview === undefined
                ? []
                : [{ _id: "s1", writes: [{ type: "t", value: "v" }] }],
        }),
      }),
    }),
  } as unknown as MongoClient;
  return { saver, client };
}

describe("LangGraphQueryPlugin cancelled-turn stamps", () => {
  test("a stamped user message carries the cancelled_turn kwarg", async () => {
    const plugin = new LangGraphQueryPlugin({
      saver: tupleSaver([
        plainTuple("2026-01-02T03:04:06.000Z", [
          new HumanMessage({ content: "first", id: "h1" }),
          new AIMessage({ content: "first reply", id: "a1" }),
          new HumanMessage({
            content: "stop this",
            id: "h2",
            additional_kwargs: { cancelled_turn: true },
          }),
        ]),
        plainTuple("2026-01-02T03:04:05.000Z", [
          new HumanMessage({ content: "first", id: "h1" }),
          new AIMessage({ content: "first reply", id: "a1" }),
        ]),
      ]),
      client: fakeClient(),
      dbName: "unused",
    });

    const result = await plugin.getMessagesForSession("s1");

    expect(cancelledStamps(result)).toEqual([
      null,
      null,
      { cancelled_turn: true },
    ]);
  });

  test("the stamp survives beyond the checkpoint window", async () => {
    // Only the tip is in the read window and no marker checkpoint is anywhere
    // in reach — the stamp lives on the message, so an old cancelled turn
    // keeps its status while its message is still served.
    const plugin = new LangGraphQueryPlugin({
      saver: tupleSaver([
        plainTuple("2026-01-02T03:04:07.000Z", [
          new HumanMessage({ content: "first", id: "h1" }),
          new AIMessage({ content: "first reply", id: "a1" }),
          new HumanMessage({
            content: "stop this",
            id: "h2",
            additional_kwargs: { cancelled_turn: true },
          }),
          new HumanMessage({ content: "try again", id: "h3" }),
          new AIMessage({ content: "ok this time", id: "a3" }),
        ]),
      ]),
      client: fakeClient(),
      dbName: "unused",
    });

    const result = await plugin.getMessagesForSession("s1");

    expect(cancelledStamps(result)).toEqual([
      null,
      null,
      { cancelled_turn: true },
      null,
      null,
    ]);
  });

  test("unmarked checkpoints carry no cancelled stamp", async () => {
    const plugin = new LangGraphQueryPlugin({
      saver: tupleSaver([
        plainTuple("2026-01-02T03:04:05.000Z", [
          new HumanMessage({ content: "hi", id: "h1" }),
        ]),
      ]),
      client: fakeClient(),
      dbName: "unused",
    });

    const result = await plugin.getMessagesForSession("s1");

    expect(cancelledStamps(result)).toEqual([null]);
  });

  test("a subgraph-namespace stamp does not leak onto the served user role", async () => {
    // The reader passes kwargs through verbatim; the user-role gating lives in
    // the UI normalizer. A non-user message carrying the kwarg is served as-is.
    const plugin = new LangGraphQueryPlugin({
      saver: tupleSaver([
        plainTuple("2026-01-02T03:04:05.000Z", [
          new HumanMessage({ content: "first", id: "h1" }),
          new AIMessage({
            content: "reply",
            id: "a1",
            additional_kwargs: { cancelled_turn: true },
          }),
        ]),
      ]),
      client: fakeClient(),
      dbName: "unused",
    });

    const result = await plugin.getMessagesForSession("s1");

    expect(cancelledStamps(result)).toEqual([null, { cancelled_turn: true }]);
    expect(result.messages[1]?.role).toBe("assistant");
  });
});

describe("LangGraphQueryPlugin summary previews", () => {
  test("preview falls back to authoritative messages when no writes preview exists", async () => {
    // A first prompt rescued by a cancelled-turn commit exists only in the
    // synthetic tip's channel values — the writes-derived preview pipeline has
    // nothing for it, so the preview comes from the authoritative list the
    // count already loaded.
    const { saver, client } = summariesHarness({
      messages: [
        new HumanMessage({
          content: "rescued first prompt",
          id: "h1",
          additional_kwargs: { cancelled_turn: true },
        }),
      ],
    });
    const plugin = new LangGraphQueryPlugin({
      saver,
      client,
      dbName: "unused",
    });

    const result = await plugin.getSummariesForSessions(["s1"]);

    expect(result.sessions[0]?.message_count).toBe(1);
    expect(result.sessions[0]?.first_message_preview).toBe(
      "rescued first prompt",
    );
  });

  test("the writes-derived preview wins over the fallback", async () => {
    // Normal sessions keep the checkpoint_writes-derived preview — the
    // authoritative-list fallback only fills sessions the pipeline missed.
    const { saver, client } = summariesHarness({
      messages: [new HumanMessage({ content: "from checkpoints", id: "h1" })],
      writesPreview: "from writes",
    });
    const plugin = new LangGraphQueryPlugin({
      saver,
      client,
      dbName: "unused",
    });

    const result = await plugin.getSummariesForSessions(["s1"]);

    expect(result.sessions[0]?.first_message_preview).toBe("from writes");
  });
});
