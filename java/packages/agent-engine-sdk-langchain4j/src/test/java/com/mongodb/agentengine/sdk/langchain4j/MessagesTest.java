package com.mongodb.agentengine.sdk.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.DocumentBlock;
import com.mongodb.agentengine.sdk.ImageBlock;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMToolCall;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.MessageContent;
import com.mongodb.agentengine.sdk.Role;
import com.mongodb.agentengine.sdk.TextBlock;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagesTest {

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  private static ChatMessage single(Message message) {
    return Messages.toLangChain(message).orElseThrow();
  }

  // --- Platform -> LangChain4j ------------------------------------------------------------

  @Test
  void textMessagesKeepTheirRole() {
    assertEquals("be brief", assertInstanceOf(SystemMessage.class, single(Message.system("be brief"))).text());
    assertEquals("hi", assertInstanceOf(UserMessage.class, single(Message.user("hi"))).singleText());
    assertEquals("hello", assertInstanceOf(AiMessage.class, single(Message.assistant("hello"))).text());
  }

  @Test
  void userNameIsKept() {
    Message message = new Message(Role.USER, new MessageContent.Text("hi"), null, null, null, "ana", null, null, null);

    assertEquals("ana", assertInstanceOf(UserMessage.class, single(message)).name());
  }

  @Test
  void multimodalUserContentMapsEachBlock() {
    var blocks =
        new MessageContent.Blocks(
            List.of(
                new TextBlock("look"),
                new ImageBlock("https://x/a.png", "image/png"),
                new DocumentBlock("https://x/a.pdf", "application/pdf", "a.pdf")));

    UserMessage user = assertInstanceOf(UserMessage.class, single(new Message(Role.USER, blocks, null, null, null, null, null, null, null)));

    assertEquals(3, user.contents().size());
    assertEquals("look", assertInstanceOf(TextContent.class, user.contents().get(0)).text());
    assertEquals("https://x/a.png", assertInstanceOf(ImageContent.class, user.contents().get(1)).image().url().toString());
    assertEquals("https://x/a.pdf", assertInstanceOf(PdfFileContent.class, user.contents().get(2)).pdfFile().url().toString());
  }

  @Test
  void pdfIsDetectedByExtensionWhenNoMimeTypeIsGiven() {
    var blocks = new MessageContent.Blocks(List.of(new DocumentBlock("https://x/Report.PDF", null, null)));

    UserMessage user = assertInstanceOf(UserMessage.class, single(new Message(Role.USER, blocks, null, null, null, null, null, null, null)));

    assertInstanceOf(PdfFileContent.class, user.contents().get(0));
  }

  @Test
  void otherDocumentsTravelAsText() {
    var blocks = new MessageContent.Blocks(List.of(new DocumentBlock("https://x/a.docx", "application/msword", "a.docx")));

    UserMessage user = assertInstanceOf(UserMessage.class, single(new Message(Role.USER, blocks, null, null, null, null, null, null, null)));

    assertEquals("[document: a.docx (https://x/a.docx)]", assertInstanceOf(TextContent.class, user.contents().get(0)).text());
  }

  @Test
  void assistantToolCallsBecomeExecutionRequests() throws Exception {
    var calls =
        List.of(
            new LLMToolCall("c1", "lookup", json("{\"q\":\"x\"}"), "tool_call", 0),
            new LLMToolCall("c2", "raw", json("\"{\\\"a\\\":1}\""), null, 1),
            new LLMToolCall("c3", "none", null, null, 2));
    Message message = new Message(Role.ASSISTANT, new MessageContent.Text(""), calls, null, null, null, null, null, null);

    AiMessage ai = assertInstanceOf(AiMessage.class, single(message));

    assertEquals(3, ai.toolExecutionRequests().size());
    assertEquals("{\"q\":\"x\"}", ai.toolExecutionRequests().get(0).arguments());
    assertEquals("{\"a\":1}", ai.toolExecutionRequests().get(1).arguments());
    assertEquals("{}", ai.toolExecutionRequests().get(2).arguments());
    assertEquals("c1", ai.toolExecutionRequests().get(0).id());
  }

  @Test
  void toolMessagesBecomeExecutionResults() {
    Message message = new Message(Role.TOOL, new MessageContent.Text("42"), null, "c1", true, "calc", null, null, null);

    ToolExecutionResultMessage result = assertInstanceOf(ToolExecutionResultMessage.class, single(message));

    assertEquals("c1", result.id());
    assertEquals("calc", result.toolName());
    assertEquals("42", result.text());
    assertTrue(result.isError());
  }

  @Test
  void structuredToolContentIsSerialized() {
    var blocks = new MessageContent.Blocks(List.of(new TextBlock("a"), new ImageBlock("https://x/i.png", null)));
    Message message = new Message(Role.TOOL, blocks, null, "c1", null, "t", null, null, null);

    String text = assertInstanceOf(ToolExecutionResultMessage.class, single(message)).text();

    assertTrue(text.contains("\"type\":\"image\""), text);
  }

  @Test
  void toolMessagesWithoutACallIdAreSkipped() {
    Message orphan = new Message(Role.TOOL, new MessageContent.Text("x"), null, null, null, "t", null, null, null);

    var converted = Messages.toLangChain(List.of(Message.user("a"), orphan, Message.assistant("b")));

    assertEquals(2, converted.size());
    assertTrue(Messages.toLangChain(orphan).isEmpty());
  }

  // --- LangChain4j -> platform ------------------------------------------------------------

  @Test
  void langChainMessagesMapBackToRoles() {
    assertEquals(Role.SYSTEM, Messages.toPlatform(SystemMessage.from("s")).role());
    assertEquals(Role.USER, Messages.toPlatform(UserMessage.from("u")).role());
    assertEquals(Role.ASSISTANT, Messages.toPlatform(AiMessage.from("a")).role());
  }

  @Test
  void singleTextUserMessageStaysAString() {
    Message message = Messages.toPlatform(UserMessage.from("ana", "hi"));

    assertEquals(new MessageContent.Text("hi"), message.content());
    assertEquals("ana", message.name());
  }

  @Test
  void multiContentUserMessageBecomesBlocks() {
    Message message = Messages.toPlatform(UserMessage.from(TextContent.from("look"), ImageContent.from("https://x/a.png")));

    var blocks = assertInstanceOf(MessageContent.Blocks.class, message.content()).blocks();
    assertEquals(new TextBlock("look"), blocks.get(0));
    assertEquals("https://x/a.png", assertInstanceOf(ImageBlock.class, blocks.get(1)).url());
  }

  @Test
  void inlineImagesBecomeDataUrls() {
    Message message = Messages.toPlatform(UserMessage.from(TextContent.from("look"), ImageContent.from("QUJD", "image/png")));

    var image = assertInstanceOf(ImageBlock.class, assertInstanceOf(MessageContent.Blocks.class, message.content()).blocks().get(1));
    assertEquals("data:image/png;base64,QUJD", image.url());
    assertEquals("image/png", image.mimeType());
  }

  @Test
  void aiToolRequestsBecomeParsedToolCalls() throws Exception {
    var requests =
        List.of(
            ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{\"q\":\"x\"}").build(),
            ToolExecutionRequest.builder().id("c2").name("odd").arguments("not json").build(),
            ToolExecutionRequest.builder().id("c3").name("none").arguments("").build());

    Message message = Messages.toPlatform(AiMessage.from("thinking", requests));

    assertEquals(new MessageContent.Text("thinking"), message.content());
    assertEquals(json("{\"q\":\"x\"}"), message.toolCalls().get(0).args());
    assertEquals("not json", message.toolCalls().get(1).args().asText());
    assertEquals(json("{}"), message.toolCalls().get(2).args());
    assertEquals(2, message.toolCalls().get(2).index());
    assertEquals("tool_call", message.toolCalls().get(0).type());
  }

  @Test
  void aiMessageWithOnlyToolCallsHasEmptyText() {
    Message message = Messages.toPlatform(AiMessage.from(ToolExecutionRequest.builder().id("c").name("t").arguments("{}").build()));

    assertEquals(new MessageContent.Text(""), message.content());
  }

  @Test
  void toolResultsKeepIdNameAndErrorFlag() {
    Message message = Messages.toPlatform(ToolExecutionResultMessage.builder().id("c1").toolName("calc").text("boom").isError(true).build());

    assertEquals(Role.TOOL, message.role());
    assertEquals("c1", message.toolCallId());
    assertEquals("calc", message.name());
    assertEquals(Boolean.TRUE, message.isError());
  }

  @Test
  void customMessagesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> Messages.toPlatform(new CustomMessage(Map.of("k", "v"))));
  }

  @Test
  void aConversationSurvivesARoundTrip() {
    List<Message> conversation =
        List.of(
            Message.system("be brief"),
            Message.user("what is 6x7?"),
            new Message(
                Role.ASSISTANT,
                new MessageContent.Text(""),
                List.of(new LLMToolCall("c1", "calc", Json.mapper().createObjectNode().put("expr", "6*7"), "tool_call", 0)),
                null, null, null, null, null, null),
            new Message(Role.TOOL, new MessageContent.Text("42"), null, "c1", false, "calc", null, null, null),
            Message.assistant("42"));

    List<Message> back = Messages.toPlatform(Messages.toLangChain(conversation));

    assertEquals(conversation.size(), back.size());
    for (int i = 0; i < conversation.size(); i++) {
      assertEquals(conversation.get(i).role(), back.get(i).role());
      assertEquals(conversation.get(i).content(), back.get(i).content());
    }
    assertEquals(conversation.get(2).toolCalls(), back.get(2).toolCalls());
    assertEquals("c1", back.get(3).toolCallId());
  }
}
