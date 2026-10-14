/** Attempt-local display history, never a workflow replay checkpoint. */

import { createHash } from "node:crypto";
import {
  create,
  fromJson,
  fromJsonString,
  toJson,
  toJsonString,
  type JsonValue,
} from "@bufbuild/protobuf";
import { ValueSchema } from "@bufbuild/protobuf/wkt";
import {
  MessageRole,
  StateSnapshotSchema,
  WorkflowMessageSchema,
  type StateSnapshot,
  type WorkflowMessage,
} from "../generated/workflow/v1/state_pb.js";
import {
  WorkflowIdentitySchema,
  type WorkflowIdentity,
} from "../generated/workflow/v1/common_pb.js";
import type { AttemptContext } from "../generated/workflow/v1/runtime_pb.js";

/** @internal Executor adapter capability. */
export interface CancelledHistoryCapture {
  openerMessageId: string;
  messages: WorkflowMessage[];
  acceptedStepOrdinal: bigint;
  sourceStateHash?: string;
}

/** @internal Executor adapter capability. */
export interface CancelledHistoryProvider {
  captureCancelledHistory(): CancelledHistoryCapture | null;
}

/** @internal Executor callback wire contract. */
export interface CancelledHistoryReport {
  workflow_identity: JsonValue;
  attempt_id: string;
  fencing_token: string;
  opener_message_id: string;
  messages: JsonValue[];
  messagesJson: string;
  accepted_step_ordinal: string;
  source_state_hash?: string;
  base_session_revision: string;
  payload_hash: string;
  /** Set for a capture reported while the execution is suspended. */
  provisional?: boolean;
}

/** @internal Hash the exact UTF-8 bytes retained for the messages wire member. */
export function cancelledHistoryMessagesHash(messagesJson: string): string {
  return createHash("sha256").update(messagesJson, "utf8").digest("hex");
}

/** @internal Keep hashed messages bytes verbatim across request serialization. */
export function encodeCancelledHistoryReport(
  report: CancelledHistoryReport,
): string {
  const fields = Object.fromEntries(
    Object.entries(report).filter(
      ([key]) => key !== "messages" && key !== "messagesJson",
    ),
  );
  const envelope = JSON.stringify(fields);
  return `${envelope.slice(0, -1)},"messages":${report.messagesJson}}`;
}

/** @internal OE decode bounds for the cancelled-history endpoint. Keep in step
 * with internal/domains/orchestration-engine/internal/workflow/cancelled_history.go. */
const MESSAGE_LIMIT = 1000;
const MESSAGE_BYTES = 256 << 10;
const TOTAL_BYTES = 1 << 20;
// Leave room inside the total body for the JSON envelope, identity, and escaping.
const ENVELOPE_HEADROOM = 64 << 10;
const TRUNCATION_MARKER =
  "earlier completed steps omitted to fit cancelled-history limits";
const TRUNCATED_OPENER_MARKER =
  "prompt omitted to fit cancelled-history limits";

function wireJson(message: WorkflowMessage): string {
  return JSON.stringify(
    toJson(WorkflowMessageSchema, message, { useProtoFieldName: true }),
  );
}

/** Bytes Go's encoding/json adds when it HTML-escapes a string. OE measures each
 * row twice — the raw UTF-8 ProtoJSON it receives, and its own json.Marshal of the
 * decoded message, which escapes <, >, & and U+2028/U+2029 to six-byte \uXXXX
 * sequences. Budget the larger so neither check rejects the row. */
function goEscapeOverhead(serialized: string): number {
  const count = (needle: string): number => serialized.split(needle).length - 1;
  return (
    5 * (count("<") + count(">") + count("&")) + 3 * (count(" ") + count(" "))
  );
}

function encodedMessageSize(message: WorkflowMessage): number {
  const serialized = wireJson(message);
  return Buffer.byteLength(serialized, "utf8") + goEscapeOverhead(serialized);
}

/** Keep the opener's identity while shrinking content that cannot fit. OE requires
 * the opener row to be present and within the per-message bound; a prompt larger
 * than that would otherwise reject the whole transcript. */
function fitOpener(opener: WorkflowMessage): WorkflowMessage {
  if (encodedMessageSize(opener) <= MESSAGE_BYTES) return opener;
  return create(WorkflowMessageSchema, {
    id: opener.id,
    role: opener.role,
    content: fromJson(ValueSchema, TRUNCATED_OPENER_MARKER),
  });
}

/** @internal Project a display transcript that fits OE's cancelled-history decode
 * bounds: OE rejects more than MESSAGE_LIMIT messages, any message over
 * MESSAGE_BYTES once encoded, or a body over TOTAL_BYTES — in both the raw UTF-8
 * ProtoJSON and its HTML-escaped Go representation. Keep the opener plus the newest
 * messages that fit, shrink an oversized opener, and record an explicit truncation
 * marker when anything is dropped. Only shapes the display projection; never
 * mutates acknowledged state. */
export function boundCancelledHistoryMessages(
  messages: readonly WorkflowMessage[],
  openerMessageId: string,
): WorkflowMessage[] {
  const budget = TOTAL_BYTES - ENVELOPE_HEADROOM;
  const originalOpener = messages.find(
    (message) => message.id === openerMessageId,
  );
  const opener =
    originalOpener !== undefined ? fitOpener(originalOpener) : undefined;
  const fits = (candidate: readonly WorkflowMessage[]): boolean => {
    if (candidate.length > MESSAGE_LIMIT) return false;
    let total = 0;
    for (const message of candidate) {
      const size = encodedMessageSize(message);
      if (size > MESSAGE_BYTES) return false;
      total += size;
      if (total > budget) return false;
    }
    return true;
  };
  if (opener === originalOpener && fits(messages)) return [...messages];
  if (messages.length === 1 && opener !== undefined) {
    // A single-opener input has no completed work: keep exactly one bounded row
    // (the fitted opener carries its own content-omission marker), so the report
    // stays opener-only for OE's replacement-before-replay fallback.
    return [opener];
  }

  const marker = create(WorkflowMessageSchema, {
    role: MessageRole.ASSISTANT,
    content: fromJson(ValueSchema, TRUNCATION_MARKER),
  });
  // Reserve the opener and the truncation marker (count and bytes) up front.
  let reserved = encodedMessageSize(marker);
  if (opener !== undefined) reserved += encodedMessageSize(opener);
  const tailBudget = Math.max(budget - reserved, 0);
  const maxTail = Math.max(
    MESSAGE_LIMIT - (opener !== undefined ? 1 : 0) - 1,
    0,
  );
  const kept: WorkflowMessage[] = [];
  let used = 0;
  for (const message of [...messages].reverse()) {
    if (opener !== undefined && message.id === openerMessageId) continue;
    const size = encodedMessageSize(message);
    if (
      size > MESSAGE_BYTES ||
      kept.length + 1 > maxTail ||
      used + size > tailBudget
    ) {
      continue;
    }
    kept.push(message);
    used += size;
  }
  kept.reverse();
  if (opener !== undefined) kept.unshift(opener);
  kept.splice(opener !== undefined ? 1 : 0, 0, marker);
  return kept;
}

/** @internal */
export function cancelledHistoryReport(
  attempt: AttemptContext,
  capture: CancelledHistoryCapture,
  provisional = false,
): CancelledHistoryReport {
  const messages = boundCancelledHistoryMessages(
    capture.messages,
    capture.openerMessageId,
  ).map((message) =>
    toJson(WorkflowMessageSchema, message, { useProtoFieldName: true }),
  );
  const messagesJson = JSON.stringify(messages);
  return {
    workflow_identity: toJson(
      WorkflowIdentitySchema,
      attempt.workflowIdentity as WorkflowIdentity,
      { useProtoFieldName: true },
    ),
    attempt_id: attempt.attemptId,
    fencing_token: attempt.fencingToken.toString(),
    opener_message_id: capture.openerMessageId,
    messages,
    messagesJson,
    accepted_step_ordinal: capture.acceptedStepOrdinal.toString(),
    ...(capture.sourceStateHash && {
      source_state_hash: capture.sourceStateHash,
    }),
    base_session_revision: "0",
    payload_hash: cancelledHistoryMessagesHash(messagesJson),
    ...(provisional && { provisional: true }),
  };
}

/** @internal Attempt-owned capture storage. */
export class CancelledHistoryState {
  private openerJson: string | null = null;
  private acknowledged: {
    stateJson: string;
    ordinal: bigint;
    hash?: string;
  } | null = null;
  private closed = false;
  private reportPromise: Promise<void> | null = null;
  reporter: (() => Promise<void>) | null = null;

  setInput(opener: WorkflowMessage): void {
    if (this.closed) return;
    if (!opener.id)
      throw new Error("cancelled history opener requires a stable message id");
    this.openerJson = toJsonString(WorkflowMessageSchema, opener);
  }

  acknowledge(
    state: StateSnapshot,
    ordinal: bigint,
    sourceStateHash?: string,
  ): void {
    if (this.closed) return;
    this.acknowledged = {
      stateJson: toJsonString(StateSnapshotSchema, state),
      ordinal,
      ...(sourceStateHash && { hash: sourceStateHash }),
    };
  }

  capture(): CancelledHistoryCapture | null {
    if (this.closed || this.openerJson === null) return null;
    const opener = fromJsonString(WorkflowMessageSchema, this.openerJson);
    if (!opener.id) return null;
    // With no acknowledged step the turn is only its opener; projecting to the
    // detached USER opener (not the hook input) keeps the report opener-only,
    // which OE's replacement-before-replay fallback requires.
    const messages =
      this.acknowledged === null
        ? [opener]
        : fromJsonString(StateSnapshotSchema, this.acknowledged.stateJson)
            .messages;
    // Display fallback must not mutate the retained checkpoint or its ACK hash.
    if (!messages.some((message) => message.id === opener.id)) {
      messages.unshift(opener);
    }
    return {
      openerMessageId: opener.id,
      messages,
      acceptedStepOrdinal: this.acknowledged?.ordinal ?? 0n,
      ...(this.acknowledged?.hash && {
        sourceStateHash: this.acknowledged.hash,
      }),
    };
  }

  reportBeforeCleanup(): Promise<void> {
    if (this.reportPromise !== null) return this.reportPromise;
    if (this.closed || this.reporter === null) return Promise.resolve();
    this.reportPromise = Promise.resolve().then(this.reporter);
    return this.reportPromise;
  }

  close(): void {
    this.closed = true;
    this.openerJson = null;
    this.acknowledged = null;
    this.reporter = null;
    this.reportPromise = null;
  }
}
