package com.mongodb.agentengine.sdk.api.v1.memory;

/**
 * Outcome of generating a session's internal state.
 *
 * @param updated whether a new version was written
 * @param coalesced whether the request was merged into one already in flight
 * @param skippedReason why generation was skipped, when it was
 */
public record InternalStateGenerationResult(
    boolean updated,
    Integer version,
    Integer tokenCount,
    String contentHash,
    Integer previousVersion,
    boolean wasNoop,
    boolean coalesced,
    String skippedReason,
    String nextUpdateAt,
    String modelId,
    String eventsHash) {}
