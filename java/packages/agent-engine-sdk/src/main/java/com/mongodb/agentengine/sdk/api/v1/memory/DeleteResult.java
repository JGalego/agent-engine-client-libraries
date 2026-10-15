package com.mongodb.agentengine.sdk.api.v1.memory;

/** Outcome of a delete. */
public record DeleteResult(int deletedCount, boolean acknowledged) {}
