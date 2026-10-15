package com.mongodb.agentengine.sdk;

import java.util.Objects;

/**
 * Reference to a branch and its fork point.
 *
 * @param name the branch name
 * @param rootEventId the event the branch forks from
 */
public record BranchRef(String name, String rootEventId) {
  public BranchRef {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(rootEventId, "rootEventId");
  }
}
