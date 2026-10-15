package com.mongodb.agentengine.sdk.memory;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Identity resolution: call argument over bound context over runtime context. */
public final class Identity {

  private Identity() {}

  /**
   * Resolves each identity field from the first tier that has it: the call arguments, then the
   * bound context, then the runtime context. A blank value counts as unset at every tier.
   *
   * <p>Two guards drop tiers for one field each.
   *
   * <p>{@code suppressRuntimeUserId} drops the runtime tier for the user. A read that asks for a
   * broad visibility must not borrow the ambient principal as its filter. A user set by the caller
   * or by binding is deliberate and still applies.
   *
   * <p>{@code suppressInheritedSessionId} drops the bound and runtime tiers for the session, so
   * only an explicit call argument applies. A session scopes conversation I/O, not long-term
   * search: episodic search matches it exactly, and consolidated episodic memory is stored without
   * one, so an inherited session would match nothing.
   *
   * @param call identity given on this call
   * @param bound identity bound to the facade, or null
   * @param runtime ambient identity from the runtime, or null
   * @param required fields that must resolve
   * @throws MemoryIdentityException if a required field resolves to nothing
   */
  public static MemoryRequestContext resolve(
      MemoryRequestContext call,
      MemoryRequestContext bound,
      MemoryRequestContext runtime,
      Set<IdentityField> required,
      boolean suppressRuntimeUserId,
      boolean suppressInheritedSessionId) {
    String userId = pick(call.userId(), bound, runtime, IdentityField.USER_ID, suppressRuntimeUserId, false);
    String agentId = pick(call.agentId(), bound, runtime, IdentityField.AGENT_ID, false, false);
    String sessionId =
        pick(call.sessionId(), bound, runtime, IdentityField.SESSION_ID, false, suppressInheritedSessionId);
    MemoryRequestContext resolved = new MemoryRequestContext(userId, agentId, sessionId);

    Set<IdentityField> missing = EnumSet.noneOf(IdentityField.class);
    for (IdentityField field : required) {
      if (get(resolved, field) == null) {
        missing.add(field);
      }
    }
    if (!missing.isEmpty()) {
      throw new MemoryIdentityException(
          "could not resolve required identity field(s): "
              + missing.stream().map(IdentityField::label).collect(Collectors.joining(", ")));
    }
    return resolved;
  }

  private static String pick(
      String callValue,
      MemoryRequestContext bound,
      MemoryRequestContext runtime,
      IdentityField field,
      boolean skipRuntime,
      boolean skipInherited) {
    String value = normalize(callValue);
    if (value == null && bound != null && !skipInherited) {
      value = normalize(get(bound, field));
    }
    if (value == null && runtime != null && !skipInherited && !skipRuntime) {
      value = normalize(get(runtime, field));
    }
    return value;
  }

  private static String get(MemoryRequestContext ctx, IdentityField field) {
    return switch (field) {
      case USER_ID -> ctx.userId();
      case AGENT_ID -> ctx.agentId();
      case SESSION_ID -> ctx.sessionId();
    };
  }

  /** A blank value carries no identity; treating it as unset keeps it from satisfying `required`. */
  static String normalize(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
