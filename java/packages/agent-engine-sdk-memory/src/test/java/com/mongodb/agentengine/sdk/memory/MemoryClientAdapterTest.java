package com.mongodb.agentengine.sdk.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.agentengine.sdk.api.v1.memory.CreateCustomRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.RetrieveCustomRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import com.mongodb.agentengine.sdk.clients.MemoryRouteStyle;
import com.mongodb.agentengine.sdk.memory.http.MemoryClientAdapter;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryClientAdapterTest {

  private FakeServer server;

  @BeforeEach
  void setUp() {
    server = new FakeServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private MemoryClientAdapter adapter(boolean projectScoped) {
    return MemoryClientAdapter.builder(server.url(), "/api/v1/memory", MemoryRouteStyle.NATIVE, projectScoped)
        .timeout(Duration.ofSeconds(5))
        .build();
  }

  private static final WriteTurnRequest TURN = WriteTurnRequest.builder("user").build();
  private static final CreateSemanticRequest FACT = CreateSemanticRequest.builder("l", "t").userId("u").build();

  @Test
  void authFailures() {
    server.reply(401, "{\"error\":\"bad token\"}").reply(403, "nope");

    var unauthorized = assertThrows(MemoryAuthException.class, () -> adapter(false).recordTurn(TURN));
    var forbidden = assertThrows(MemoryAuthException.class, () -> adapter(false).recordTurn(TURN));

    assertEquals("bad token", unauthorized.getMessage());
    assertEquals(401, unauthorized.status());
    assertEquals("nope", forbidden.getMessage());
    assertEquals("nope", forbidden.responseText());
  }

  @Test
  void structuredBodiesYieldMessageAndCode() {
    server.reply(400, "{\"code\":\"INVALID_REQUEST\",\"message\":\"label too long\"}");

    var error = assertThrows(MemoryBadRequestException.class, () -> adapter(false).createSemantic(FACT));

    assertEquals("label too long (code=INVALID_REQUEST)", error.getMessage());
    assertEquals("INVALID_REQUEST", error.code());
    assertEquals(400, error.status());
  }

  @Test
  void fastApiDetailIsUsedOnlyWhenItIsAString() {
    server.reply(422, "{\"detail\":\"missing field\"}").reply(422, "{\"detail\":[{\"msg\":\"x\"}]}");

    assertEquals("missing field", assertThrows(MemoryBadRequestException.class, () -> adapter(false).createSemantic(FACT)).getMessage());
    assertEquals(
        "{\"detail\":[{\"msg\":\"x\"}]}",
        assertThrows(MemoryBadRequestException.class, () -> adapter(false).createSemantic(FACT)).getMessage());
  }

  @Test
  void notProvisionedCodesWinOverTheStatusClass() {
    server.reply(503, "{\"code\":\"AGENT_NOT_DEPLOYED\",\"message\":\"deploy first\"}");

    var error = assertThrows(MemoryNotProvisionedException.class, () -> adapter(false).createSemantic(FACT));

    assertEquals("AGENT_NOT_DEPLOYED", error.code());
  }

  @Test
  void aCoreLoop404HintsAtTheRouteShape() {
    server.reply(404, "{}").reply(404, "{}");

    var flat = assertThrows(MemoryRouteNotFoundException.class, () -> adapter(false).recordTurn(TURN));
    var scoped = assertThrows(MemoryRouteNotFoundException.class, () -> adapter(true).recordTurn(TURN));

    assertTrue(flat.getMessage().contains("projectId is empty"));
    assertTrue(scoped.getMessage().contains("projectId is set"));
    assertInstanceOf(MemoryBadRequestException.class, flat);
  }

  @Test
  void aCrud404IsAPlainBadRequest() {
    server.reply(404, "gone");

    var error = assertThrows(MemoryBadRequestException.class, () -> adapter(false).createSemantic(FACT));

    assertEquals(MemoryBadRequestException.class, error.getClass());
  }

  @Test
  void serverErrorsAndBadBodies() {
    server.reply(500, "boom").reply(200, "not json");

    assertEquals(500, assertThrows(MemoryServerException.class, () -> adapter(false).createSemantic(FACT)).status());
    var bad = assertThrows(MemoryServerException.class, () -> adapter(false).createSemantic(FACT));
    assertNull(bad.status());
  }

  @Test
  void unreachableBackendsAreConnectionErrors() {
    server.close();

    var error = assertThrows(MemoryConnectionException.class, () -> adapter(false).searchSemantic(SearchSemanticRequest.builder("q").build()));

    assertNull(error.status());
  }

  @Test
  void readsRetryTransientFailuresButCreatesDoNot() {
    server.reply(503, "x").reply(200, "{\"id\":\"t\",\"session_id\":\"s\",\"turn_seq\":1,\"acknowledged\":true}");
    adapter(false).recordTurn(TURN);
    assertEquals(2, server.requests().size());

    server.reply(503, "x").reply(200, "{\"id\":\"1\",\"label\":\"l\",\"has_embedding\":false,\"acknowledged\":true}");
    assertThrows(MemoryServerException.class, () -> adapter(false).createSemantic(FACT));
    assertEquals(3, server.requests().size());
  }

  @Test
  void customTypeGapsBecomeNotSupported() {
    server.reply(404, "").reply(405, "").reply(400, "{\"message\":\"custom_memory_types is not enabled\"}");
    var save = CreateCustomRequest.builder("note", "c").build();

    var predates = assertThrows(MemoryNotSupportedException.class, () -> adapter(false).createCustom(save));
    assertThrows(MemoryNotSupportedException.class, () -> adapter(false).retrieveCustom(RetrieveCustomRequest.builder("note", "q").build()));
    var disabled = assertThrows(MemoryNotSupportedException.class, () -> adapter(false).createCustom(save));

    assertTrue(predates.getMessage().contains("does not support custom memory types"));
    assertInstanceOf(MemoryBadRequestException.class, predates.getCause());
    assertTrue(disabled.getMessage().contains("disabled on this deployment"));
  }

  @Test
  void anUnknownTypeErrorPassesThrough() {
    server.reply(404, "{\"message\":\"unknown custom memory type 'note'\"}").reply(400, "{\"message\":\"bad tag\"}");
    var save = CreateCustomRequest.builder("note", "c").build();

    var unknown = assertThrows(MemoryBadRequestException.class, () -> adapter(false).createCustom(save));
    var other = assertThrows(MemoryBadRequestException.class, () -> adapter(false).createCustom(save));

    assertEquals(404, unknown.status());
    assertEquals("bad tag", other.getMessage());
  }
}
