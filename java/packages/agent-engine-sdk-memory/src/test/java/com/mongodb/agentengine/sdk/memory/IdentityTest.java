package com.mongodb.agentengine.sdk.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityTest {

  private static final Set<IdentityField> NONE = EnumSet.noneOf(IdentityField.class);

  private static MemoryRequestContext ctx(String user, String agent, String session) {
    return new MemoryRequestContext(user, agent, session);
  }

  private static MemoryRequestContext resolve(
      MemoryRequestContext call, MemoryRequestContext bound, MemoryRequestContext runtime) {
    return Identity.resolve(call, bound, runtime, NONE, false, false);
  }

  @Test
  void callArgumentBeatsBoundBeatsRuntime() {
    var call = ctx("call", null, null);
    var bound = ctx("bound", "bound-agent", null);
    var runtime = ctx("runtime", "runtime-agent", "runtime-session");

    assertEquals(ctx("call", "bound-agent", "runtime-session"), resolve(call, bound, runtime));
  }

  @Test
  void blankValuesAreUnsetAtEveryTier() {
    var resolved = resolve(ctx(" ", "", null), ctx("  ", null, null), ctx("runtime", "agent", "s"));

    assertEquals(ctx("runtime", "agent", "s"), resolved);
  }

  @Test
  void missingRequiredFieldsAreReportedTogether() {
    var error =
        assertThrows(
            MemoryIdentityException.class,
            () ->
                Identity.resolve(
                    MemoryRequestContext.EMPTY, null, null,
                    EnumSet.of(IdentityField.USER_ID, IdentityField.SESSION_ID), false, false));

    assertEquals("could not resolve required identity field(s): userId, sessionId", error.getMessage());
  }

  @Test
  void blankDoesNotSatisfyRequired() {
    assertThrows(
        MemoryIdentityException.class,
        () -> Identity.resolve(ctx("  ", null, null), null, null, EnumSet.of(IdentityField.USER_ID), false, false));
  }

  @Test
  void suppressRuntimeUserIdKeepsExplicitAndBoundUsers() {
    var runtime = ctx("ambient", null, null);

    assertNull(Identity.resolve(MemoryRequestContext.EMPTY, null, runtime, NONE, true, false).userId());
    assertEquals("bound", Identity.resolve(MemoryRequestContext.EMPTY, ctx("bound", null, null), runtime, NONE, true, false).userId());
    assertEquals("call", Identity.resolve(ctx("call", null, null), null, runtime, NONE, true, false).userId());
  }

  @Test
  void suppressInheritedSessionIdKeepsOnlyTheExplicitSession() {
    var inherited = ctx(null, null, "inherited");

    assertNull(Identity.resolve(MemoryRequestContext.EMPTY, inherited, inherited, NONE, false, true).sessionId());
    assertEquals("explicit", Identity.resolve(ctx(null, null, "explicit"), inherited, inherited, NONE, false, true).sessionId());
  }
}
