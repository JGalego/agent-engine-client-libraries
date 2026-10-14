import { create } from "@bufbuild/protobuf";
import { HumanMessage } from "@langchain/core/messages";
import {
  MemorySaver,
  emptyCheckpoint,
  type BaseCheckpointSaver,
  type CheckpointMetadata,
} from "@langchain/langgraph";
import {
  AttemptContextSchema,
  StepActivityEntrySchema,
  type CompleteExecutionCommand,
  type FinalizeStepCommand,
  type StepActivityEntry,
  TenantScopeSchema,
  WorkflowIdentitySchema,
  currentStepOrdinal,
  currentCancelledHistory,
  runWithAttemptContext,
  runWithExecutionContext,
} from "@mongodb-js/agent-engine-runner-shared";
import { describe, expect, it, vi } from "vitest";

import {
  PlatformCheckpointer,
  UnsupportedDurableGraphError,
  OE_STEP_ORDINAL_METADATA_KEY,
  scratchThreadId,
} from "../src/platform_checkpointer.js";
import { channelValuesToStateSnapshot } from "../src/workflow_state.js";

const identity = create(WorkflowIdentitySchema, {
  tenantScope: create(TenantScopeSchema, {
    orgId: "org",
    projectId: "project",
    workspaceId: "workspace",
  }),
  sessionId: "session",
  executionId: "execution",
});

function attempt(
  args: { id?: string; fence?: bigint; previous?: boolean } = {},
) {
  return create(AttemptContextSchema, {
    attemptId: args.id ?? "attempt-1",
    fencingToken: args.fence ?? 7n,
    workflowIdentity: identity,
    ...(args.previous && {
      previousState: channelValuesToStateSnapshot({
        messages: [new HumanMessage({ content: "first", id: "human-1" })],
        count: 1,
      }),
    }),
  });
}

function checkpoint(id: string, values: Record<string, unknown>) {
  return { ...emptyCheckpoint(), id, channel_values: values };
}

const loopMetadata: CheckpointMetadata = {
  source: "loop",
  step: 0,
  parents: {},
};

function clients() {
  const finalizeStep = vi.fn(
    async (_command: FinalizeStepCommand): Promise<StepActivityEntry[]> => [],
  );
  const completeExecution = vi.fn(
    async (_command: CompleteExecutionCommand) => undefined,
  );
  return {
    finalizeStep,
    completeExecution,
    factory: () => ({ finalizeStep, completeExecution }),
  };
}

describe("PlatformCheckpointer", () => {
  it("keeps acknowledged history isolated for concurrent attempts on a shared saver", async () => {
    let release!: () => void;
    const bothActive = new Promise<void>((resolve) => {
      release = resolve;
    });
    const fake = clients();
    fake.finalizeStep.mockImplementation(async (command) => {
      if (command.attemptId === "left") await bothActive;
      else release();
      return [];
    });
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: fake.factory,
    });
    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        Promise.all(
          ["left", "right"].map((id) =>
            runWithAttemptContext(
              attempt({ id, fence: id === "left" ? 7n : 8n }),
              async () => {
                const history = currentCancelledHistory();
                if (history === null) throw new Error("missing history scope");
                const state = channelValuesToStateSnapshot({
                  messages: [new HumanMessage({ content: id, id })],
                });
                const opener = state.messages[0];
                if (opener === undefined) throw new Error("missing opener");
                history.setInput(opener);
                await saver.put(
                  { configurable: { thread_id: "same-thread" } },
                  checkpoint(id, {
                    messages: [new HumanMessage({ content: id, id })],
                  }),
                  loopMetadata,
                  {},
                );
                expect(
                  history.capture()?.messages.map((message) => message.id),
                ).toEqual([id]);
                await saver.releaseScratch(
                  attempt({ id, fence: id === "left" ? 7n : 8n }),
                );
                history.close();
              },
            ),
          ),
        ),
    );
  });

  it("captures only immutable acknowledged root checkpoints, not failed or nested writes", async () => {
    const fake = clients();
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: () => ({
        ...fake.factory(),
        finalizeStepWithAcknowledgement: async (command) => ({
          entries: await fake.finalizeStep(command),
          sourceStateHash: "original-authoritative-hash",
        }),
      }),
    });
    const opener = new HumanMessage({ content: "hello", id: "opener" });
    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        runWithAttemptContext(attempt(), async () => {
          const history = currentCancelledHistory();
          if (history === null) throw new Error("missing history scope");
          const inputState = channelValuesToStateSnapshot({
            messages: [opener],
          });
          const inputOpener = inputState.messages[0];
          if (inputOpener === undefined) throw new Error("missing opener");
          history.setInput(inputOpener);
          const config = { configurable: { thread_id: "session" } };
          const accepted = checkpoint("accepted", {
            messages: [opener],
            value: 1,
          });
          await saver.put(config, accepted, loopMetadata, {});
          const captured = history.capture();
          accepted.channel_values["value"] = 999;
          opener.content = "mutated";
          await saver.put(
            { configurable: { thread_id: "session", checkpoint_ns: "child" } },
            checkpoint("nested", { value: "not-root" }),
            loopMetadata,
            {},
          );
          fake.finalizeStep.mockRejectedValueOnce(
            new Error("transport failed"),
          );
          await expect(
            saver.put(
              config,
              checkpoint("unaccepted", { messages: [], value: 2 }),
              loopMetadata,
              {},
            ),
          ).rejects.toThrow("transport failed");
          expect(history.capture()).toEqual(captured);
          expect(history.capture()?.acceptedStepOrdinal).toBe(1n);
          expect(history.capture()?.sourceStateHash).toBe(
            "original-authoritative-hash",
          );
          expect((await saver.getTuple(config))?.checkpoint.id).toBe(
            "unaccepted",
          );
          expect(fake.completeExecution).not.toHaveBeenCalled();
        }),
    );
  });

  it("keeps an opener-only capture while the first commit's acknowledgement is in flight", async () => {
    // Stop after the first OE commit but before its acknowledgement reaches the
    // runner: no marker is held, so the report is opener-only and OE accepts it
    // for the same attempt/fence.
    const fake = clients();
    let finalizeStarted!: () => void;
    const started = new Promise<void>((resolve) => {
      finalizeStarted = resolve;
    });
    let releaseAck!: () => void;
    const ackPending = new Promise<void>((resolve) => {
      releaseAck = resolve;
    });
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: () => ({
        ...fake.factory(),
        finalizeStepWithAcknowledgement: async (command) => {
          finalizeStarted();
          await ackPending;
          return {
            entries: await fake.finalizeStep(command),
            sourceStateHash: "authoritative-hash",
          };
        },
      }),
    });
    const opener = new HumanMessage({ content: "hello", id: "opener" });
    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        runWithAttemptContext(attempt(), async () => {
          const history = currentCancelledHistory();
          if (history === null) throw new Error("missing history scope");
          const inputState = channelValuesToStateSnapshot({
            messages: [opener],
          });
          const inputOpener = inputState.messages[0];
          if (inputOpener === undefined) throw new Error("missing opener");
          history.setInput(inputOpener);
          const config = { configurable: { thread_id: "session" } };
          const put = saver.put(
            config,
            checkpoint("accepted", { messages: [opener], value: 1 }),
            loopMetadata,
            {},
          );
          await started; // OE commit sent; the acknowledgement has not landed
          const capture = history.capture();
          expect(capture?.openerMessageId).toBe("opener");
          expect(capture?.messages.map((message) => message.id)).toEqual([
            "opener",
          ]);
          expect(capture?.acceptedStepOrdinal).toBe(0n);
          expect(capture?.sourceStateHash).toBeUndefined();
          releaseAck();
          await put;
        }),
    );
  });

  it("preserves the native saver outside an OE attempt", async () => {
    const native = new MemorySaver();
    const saver = new PlatformCheckpointer({
      native: native as BaseCheckpointSaver<string | number>,
    });
    const config = { configurable: { thread_id: "native-thread" } };

    await saver.put(
      config,
      checkpoint("native-1", { value: "native" }),
      loopMetadata,
      {},
    );

    expect((await native.getTuple(config))?.checkpoint.channel_values).toEqual({
      value: "native",
    });
  });

  it("isolates scratch by attempt and fence instead of caller thread id", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    const config = { configurable: { thread_id: "attacker-controlled" } };
    const first = attempt();
    const replacement = attempt({ id: "attempt-2", fence: 8n });

    await runWithAttemptContext(first, () =>
      saver.put(
        config,
        checkpoint("durable-1", { value: "first" }),
        { source: "input", step: -1, parents: {} },
        {},
      ),
    );

    await runWithAttemptContext(replacement, async () => {
      expect(await saver.getTuple(config)).toBeUndefined();
    });
    await runWithAttemptContext(first, async () => {
      expect((await saver.getTuple(config))?.checkpoint.channel_values).toEqual(
        {
          value: "first",
        },
      );
    });
    expect(scratchThreadId(first)).toBe("execution:attempt-1:7");
  });

  it("seeds previous OE state once and releases only that attempt scratch", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    const current = attempt({ previous: true });
    const config = { configurable: { thread_id: "session" } };

    await runWithAttemptContext(current, async () => {
      await saver.seedPreviousState(current, config);
      await saver.seedPreviousState(current, config);
      const tuple = await saver.getTuple(config);
      expect(tuple?.checkpoint.channel_values["count"]).toBe(1);
      expect(
        (tuple?.checkpoint.channel_values["messages"] as HumanMessage[])[0]?.id,
      ).toBe("human-1");
      await saver.releaseScratch(current);
      expect(await saver.getTuple(config)).toBeUndefined();
    });
  });

  it("finalizes root loop checkpoints in order and ignores nested checkpoints", async () => {
    const fake = clients();
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: fake.factory,
    });
    const current = attempt();

    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        runWithAttemptContext(current, async () => {
          await saver.put(
            { configurable: { thread_id: "session" } },
            checkpoint("root-1", { value: 1 }),
            loopMetadata,
            {},
          );
          await saver.put(
            {
              configurable: {
                thread_id: "session",
                checkpoint_ns: "child:node",
              },
            },
            checkpoint("child-1", { value: "child" }),
            loopMetadata,
            {},
          );
          await saver.put(
            { configurable: { thread_id: "session" } },
            checkpoint("root-2", { value: 2 }),
            { ...loopMetadata, step: 1 },
            {},
          );
        }),
    );

    expect(fake.finalizeStep).toHaveBeenCalledTimes(2);
    expect(
      fake.finalizeStep.mock.calls.map(([command]) => command.stepOrdinal),
    ).toEqual([1n, 2n]);
  });

  it("stamps the absolute OE step ordinal on committed root-loop scratch", async () => {
    const fake = clients();
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: fake.factory,
    });

    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        runWithAttemptContext(attempt(), async () => {
          await saver.put(
            { configurable: { thread_id: "session" } },
            checkpoint("root-1", { value: 1 }),
            loopMetadata,
            {},
          );
          await saver.put(
            {
              configurable: {
                thread_id: "session",
                checkpoint_ns: "child:node",
              },
            },
            checkpoint("child-1", { value: "child" }),
            loopMetadata,
            {},
          );

          const root = await saver.getTuple({
            configurable: { thread_id: "session" },
          });
          expect(
            (root?.metadata as Record<string, unknown> | undefined)?.[
              OE_STEP_ORDINAL_METADATA_KEY
            ],
          ).toBe(1);
          const child = await saver.getTuple({
            configurable: {
              thread_id: "session",
              checkpoint_ns: "child:node",
            },
          });
          expect(
            (child?.metadata as Record<string, unknown> | undefined)?.[
              OE_STEP_ORDINAL_METADATA_KEY
            ],
          ).toBeUndefined();
        }),
    );
  });

  it("does not advance the step after unsupported suspension entries", async () => {
    const fake = clients();
    fake.finalizeStep.mockResolvedValueOnce([
      create(StepActivityEntrySchema, {}),
    ]);
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: fake.factory,
    });

    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        runWithAttemptContext(attempt(), async () => {
          expect(currentStepOrdinal()).toBe(1);
          await expect(
            saver.put(
              { configurable: { thread_id: "session" } },
              checkpoint("root-1", { value: 1 }),
              loopMetadata,
              {},
            ),
          ).rejects.toThrow("settled step finalization returned suspension");
          expect(currentStepOrdinal()).toBe(1);
        }),
    );
  });

  it("routes pending writes through fenced attempt scratch", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    const config = {
      configurable: {
        thread_id: "attacker-controlled",
        checkpoint_id: "writes-1",
      },
    };
    const first = attempt();
    const replacement = attempt({ id: "attempt-2", fence: 8n });

    await runWithAttemptContext(first, async () => {
      await saver.put(
        { configurable: { thread_id: "attacker-controlled" } },
        checkpoint("writes-1", {}),
        { source: "input", step: -1, parents: {} },
        {},
      );
      await saver.putWrites(config, [["result", "pending"]], "task-1");
      expect((await saver.getTuple(config))?.pendingWrites).toEqual([
        ["task-1", "result", "pending"],
      ]);
    });
    await runWithAttemptContext(replacement, async () => {
      expect(await saver.getTuple(config)).toBeUndefined();
    });
  });

  it("delegates native versions and rejects durable string versions", () => {
    const native = new MemorySaver();
    const getNextVersion = vi.spyOn(native, "getNextVersion");
    const saver = new PlatformCheckpointer({
      native: native as BaseCheckpointSaver<string | number>,
    });

    expect(saver.getNextVersion(undefined)).toBe(1);
    expect(getNextVersion).toHaveBeenCalledWith(undefined);
    runWithAttemptContext(attempt(), () => {
      expect(() => saver.getNextVersion("1")).toThrow(
        "durable scratch requires numeric channel versions",
      );
      expect(saver.getNextVersion(1)).toBe(2);
    });
  });

  it("publishes terminal state once and propagates stale ownership", async () => {
    const fake = clients();
    fake.completeExecution.mockRejectedValueOnce(
      new Error("stale fencing token"),
    );
    const saver = new PlatformCheckpointer({
      native: null,
      clientFactory: fake.factory,
    });
    const current = attempt();

    await runWithExecutionContext(
      { executionId: "execution", wrapper: null, oeUrl: "http://oe" },
      () =>
        runWithAttemptContext(current, async () => {
          await saver.put(
            { configurable: { thread_id: "session" } },
            checkpoint("terminal", { value: "done" }),
            { source: "input", step: -1, parents: {} },
            {},
          );
          await expect(saver.completeExecution(current)).rejects.toThrow(
            "stale fencing token",
          );
        }),
    );
    expect(fake.completeExecution).toHaveBeenCalledTimes(1);
  });

  it("rejects update-state checkpoints during a durable attempt", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    await runWithAttemptContext(attempt(), async () => {
      await expect(
        saver.put(
          { configurable: { thread_id: "session" } },
          checkpoint("update", {}),
          { source: "update", step: 1, parents: {} },
          {},
        ),
      ).rejects.toBeInstanceOf(UnsupportedDurableGraphError);
    });
  });

  it("rejects durable checkpoint history enumeration without a config", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    await runWithAttemptContext(attempt(), async () => {
      await expect(
        (async () => {
          for await (const _entry of saver.list(
            undefined as unknown as Parameters<typeof saver.list>[0],
          )) {
            // No entries are expected; advancing triggers the rejection.
          }
        })(),
      ).rejects.toThrow(
        /durable scratch history requires the current attempt config/,
      );
    });
  });

  it("scopes durable history enumeration to the fenced attempt scratch", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    const scoped = attempt();
    const config = { configurable: { thread_id: "session" } };
    await runWithAttemptContext(scoped, async () => {
      await saver.put(
        config,
        checkpoint("durable-1", { value: "first" }),
        { source: "input", step: -1, parents: {} },
        {},
      );
      const enumerated: string[] = [];
      for await (const entry of saver.list(config)) {
        enumerated.push(entry.config.configurable?.["thread_id"] as string);
      }
      expect(enumerated).toEqual([scratchThreadId(scoped)]);
    });
  });

  it("never exposes another attempt's scratch through history enumeration", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    await runWithAttemptContext(attempt(), async () => {
      await saver.put(
        { configurable: { thread_id: "session" } },
        checkpoint("durable-1", { value: "first" }),
        { source: "input", step: -1, parents: {} },
        {},
      );
    });
    await runWithAttemptContext(
      attempt({ id: "attempt-2", fence: 8n }),
      async () => {
        const entries: unknown[] = [];
        for await (const entry of saver.list({
          configurable: { thread_id: "session" },
        })) {
          entries.push(entry);
        }
        expect(entries).toEqual([]);
      },
    );
  });

  it("fails rather than falling back when a native leg is unavailable", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    await expect(
      saver.getTuple({ configurable: { thread_id: "native" } }),
    ).rejects.toThrow("requires a MongoDB-backed checkpointer");
  });

  it("requires final scratch before terminal publication", async () => {
    const saver = new PlatformCheckpointer({ native: null });
    await expect(saver.completeExecution(attempt())).rejects.toThrow(
      "completed without final application state",
    );
  });
});
