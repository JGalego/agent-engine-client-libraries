package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.agentengine.sdk.Message;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionScopeTest {

  private static SecureExecution execution(String id) {
    return new SecureExecution(new RunnableApp("a"), new OeClient("http://127.0.0.1:1", Duration.ofSeconds(1)), id, Map.of());
  }

  @Test
  void thereIsNoCurrentExecutionOutsideATurn() {
    assertFalse(ExecutionScope.isActive());
    assertThrows(IllegalStateException.class, ExecutionScope::current);
  }

  @Test
  void callBindsTheExecutionAndRestoresTheOuterOne() throws Exception {
    SecureExecution outer = execution("outer");
    SecureExecution inner = execution("inner");

    ExecutionScope.call(outer, () -> {
      assertSame(outer, ExecutionScope.current());
      ExecutionScope.call(inner, () -> {
        assertSame(inner, ExecutionScope.current());
        return null;
      });
      assertSame(outer, ExecutionScope.current());
      return null;
    });

    assertFalse(ExecutionScope.isActive());
  }

  @Test
  void theScopeIsClearedEvenWhenTheBodyThrows() {
    assertThrows(IllegalStateException.class, () -> ExecutionScope.call(execution("e"), () -> {
      throw new IllegalStateException("boom");
    }));

    assertFalse(ExecutionScope.isActive());
  }

  @Test
  void wrapCarriesTheScopeToAnotherThread() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();

    ExecutionScope.call(execution("exec-9"), () -> {
      Thread worker = new Thread(ExecutionScope.wrap(() -> seen.set(ExecutionScope.current().executionId())));
      worker.start();
      worker.join();
      return null;
    });

    assertEquals("exec-9", seen.get());
  }

  @Test
  void anUnwrappedThreadHasNoScope() throws Exception {
    AtomicReference<Boolean> active = new AtomicReference<>();

    ExecutionScope.call(execution("e"), () -> {
      Thread worker = new Thread(() -> active.set(ExecutionScope.isActive()));
      worker.start();
      worker.join();
      return null;
    });

    assertEquals(false, active.get());
  }

  @Test
  void appHandlesRefuseToRunOutsideATurn() {
    RunnableApp app = new RunnableApp("a");
    SecureLlm llm = app.llm("gpt-x", new StubLlm());

    assertThrows(IllegalStateException.class, () -> llm.invoke(List.of(Message.user("hi"))));
    assertThrows(IllegalStateException.class, () -> app.callTool("anything", Map.of()));
    assertTrue(app.registeredLlm("__default__").isPresent());
  }
}
