package com.mongodb.agentengine.sdk;

/**
 * Coordinate of a committed checkpoint.
 *
 * @param threadId the checkpoint thread
 * @param checkpointId the checkpoint within the thread
 */
public record CheckpointRef(String threadId, String checkpointId) {}
