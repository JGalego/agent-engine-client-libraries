package com.mongodb.agentengine.sdk.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.sdk.ContentBlock;
import com.mongodb.agentengine.sdk.DocumentBlock;
import com.mongodb.agentengine.sdk.ImageBlock;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMToolCall;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.MessageContent;
import com.mongodb.agentengine.sdk.Role;
import com.mongodb.agentengine.sdk.TextBlock;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Translation between platform {@link Message}s and LangChain4j {@link ChatMessage}s. */
public final class Messages {

  private static final System.Logger LOG = System.getLogger(Messages.class.getName());

  private Messages() {}

  // ---------------------------------------------------------------------------
  // Platform -> LangChain4j
  // ---------------------------------------------------------------------------

  /**
   * Converts platform messages, skipping any that cannot be represented: a tool message without a
   * tool call id has no call to answer, so it is dropped with a warning.
   */
  public static List<ChatMessage> toLangChain(List<Message> messages) {
    List<ChatMessage> converted = new ArrayList<>(messages.size());
    for (Message message : messages) {
      toLangChain(message).ifPresent(converted::add);
    }
    return converted;
  }

  /** Converts one platform message, or returns empty if it cannot be represented. */
  public static Optional<ChatMessage> toLangChain(Message message) {
    return switch (message.role()) {
      case SYSTEM -> Optional.of(SystemMessage.from(text(message.content())));
      case USER -> Optional.of(userMessage(message));
      case ASSISTANT -> Optional.of(aiMessage(message));
      case TOOL -> toolResult(message);
    };
  }

  private static ChatMessage userMessage(Message message) {
    if (message.content() instanceof MessageContent.Text text) {
      return message.name() == null
          ? UserMessage.from(text.value())
          : UserMessage.from(message.name(), text.value());
    }
    List<Content> contents = new ArrayList<>();
    for (ContentBlock block : ((MessageContent.Blocks) message.content()).blocks()) {
      contents.add(content(block));
    }
    return message.name() == null ? UserMessage.from(contents) : UserMessage.from(message.name(), contents);
  }

  private static Content content(ContentBlock block) {
    if (block instanceof TextBlock text) {
      return TextContent.from(text.text());
    }
    if (block instanceof ImageBlock image) {
      return ImageContent.from(URI.create(image.url()));
    }
    DocumentBlock document = (DocumentBlock) block;
    return isPdf(document) ? PdfFileContent.from(URI.create(document.url())) : unsupportedDocument(document);
  }

  private static boolean isPdf(DocumentBlock document) {
    return "application/pdf".equalsIgnoreCase(document.mimeType())
        || (document.mimeType() == null && document.url().toLowerCase().endsWith(".pdf"));
  }

  /** LangChain4j models PDFs but no other document type, so the rest travel as text. */
  private static Content unsupportedDocument(DocumentBlock document) {
    LOG.log(System.Logger.Level.WARNING, "Document {0} is not a PDF; sending it as text", document.url());
    String name = document.filename() != null ? document.filename() : document.url();
    return TextContent.from("[document: " + name + " (" + document.url() + ")]");
  }

  private static ChatMessage aiMessage(Message message) {
    String text = text(message.content());
    if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
      return AiMessage.from(text);
    }
    List<ToolExecutionRequest> requests = new ArrayList<>();
    for (LLMToolCall call : message.toolCalls()) {
      requests.add(
          ToolExecutionRequest.builder().id(call.id()).name(call.name()).arguments(arguments(call.args())).build());
    }
    return AiMessage.from(text.isEmpty() ? null : text, requests);
  }

  private static Optional<ChatMessage> toolResult(Message message) {
    if (message.toolCallId() == null || message.toolCallId().isEmpty()) {
      LOG.log(System.Logger.Level.WARNING, "Skipping tool message without a tool call id");
      return Optional.empty();
    }
    return Optional.of(
        ToolExecutionResultMessage.builder()
            .id(message.toolCallId())
            .toolName(message.name())
            .text(toolText(message.content()))
            .isError(message.isError())
            .build());
  }

  /** Tool results are text for LangChain4j; structured content is serialized as JSON. */
  private static String toolText(MessageContent content) {
    if (content instanceof MessageContent.Text text) {
      return text.value();
    }
    List<ContentBlock> blocks = ((MessageContent.Blocks) content).blocks();
    if (blocks.stream().allMatch(TextBlock.class::isInstance)) {
      return text(content);
    }
    try {
      // The element type must be explicit, or Jackson drops each block's `type` discriminator.
      return Json.mapper().writerFor(new TypeReference<List<ContentBlock>>() {}).writeValueAsString(blocks);
    } catch (JsonProcessingException e) {
      return String.valueOf(blocks);
    }
  }

  /** The text of the content, with non-text blocks left out. */
  private static String text(MessageContent content) {
    if (content instanceof MessageContent.Text text) {
      return text.value();
    }
    return ((MessageContent.Blocks) content)
        .blocks().stream()
            .filter(TextBlock.class::isInstance)
            .map(block -> ((TextBlock) block).text())
            .collect(Collectors.joining());
  }

  /** LangChain4j carries tool arguments as a JSON string; some providers send one already. */
  private static String arguments(JsonNode args) {
    if (args == null || args.isNull()) {
      return "{}";
    }
    return args.isTextual() ? args.asText() : args.toString();
  }

  // ---------------------------------------------------------------------------
  // LangChain4j -> platform
  // ---------------------------------------------------------------------------

  /** Converts LangChain4j messages to platform messages. */
  public static List<Message> toPlatform(List<ChatMessage> messages) {
    List<Message> converted = new ArrayList<>(messages.size());
    for (ChatMessage message : messages) {
      converted.add(toPlatform(message));
    }
    return converted;
  }

  /**
   * Converts one LangChain4j message.
   *
   * @throws IllegalArgumentException for a message type the platform has no role for, such as a
   *     custom message
   */
  public static Message toPlatform(ChatMessage message) {
    if (message instanceof SystemMessage system) {
      return Message.system(system.text());
    }
    if (message instanceof UserMessage user) {
      MessageContent content =
          user.hasSingleText()
              ? new MessageContent.Text(user.singleText())
              : new MessageContent.Blocks(user.contents().stream().map(Messages::block).toList());
      return new Message(Role.USER, content, null, null, null, user.name(), null, null, null);
    }
    if (message instanceof AiMessage ai) {
      List<LLMToolCall> calls = ai.hasToolExecutionRequests() ? toolCalls(ai.toolExecutionRequests()) : null;
      String text = ai.text() == null ? "" : ai.text();
      return new Message(Role.ASSISTANT, new MessageContent.Text(text), calls, null, null, null, null, null, null);
    }
    if (message instanceof ToolExecutionResultMessage result) {
      return new Message(
          Role.TOOL,
          new MessageContent.Text(result.text() == null ? "" : result.text()),
          null,
          result.id(),
          result.isError(),
          result.toolName(),
          null,
          null,
          null);
    }
    throw new IllegalArgumentException("Unsupported LangChain4j message type: " + message.type());
  }

  static List<LLMToolCall> toolCalls(List<ToolExecutionRequest> requests) {
    List<LLMToolCall> calls = new ArrayList<>(requests.size());
    for (int i = 0; i < requests.size(); i++) {
      ToolExecutionRequest request = requests.get(i);
      calls.add(new LLMToolCall(request.id(), request.name(), parseArguments(request.arguments()), "tool_call", i));
    }
    return calls;
  }

  /** Parses a tool-argument string; text that is not JSON is kept as a string. */
  static JsonNode parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return JsonNodeFactory.instance.objectNode();
    }
    try {
      return Json.mapper().readTree(arguments);
    } catch (JsonProcessingException e) {
      return JsonNodeFactory.instance.textNode(arguments);
    }
  }

  private static ContentBlock block(Content content) {
    if (content instanceof TextContent text) {
      return new TextBlock(text.text());
    }
    if (content instanceof ImageContent imageContent) {
      Image image = imageContent.image();
      return new ImageBlock(image.url() != null ? image.url().toString() : dataUrl(image.mimeType(), image.base64Data()), image.mimeType());
    }
    if (content instanceof PdfFileContent pdf) {
      var file = pdf.pdfFile();
      return new DocumentBlock(
          file.url() != null ? file.url().toString() : dataUrl(file.mimeType(), file.base64Data()), file.mimeType(), null);
    }
    LOG.log(System.Logger.Level.WARNING, "Unsupported content type {0}; sending it as text", content.type());
    return new TextBlock(content.toString());
  }

  private static String dataUrl(String mimeType, String base64) {
    return "data:" + mimeType + ";base64," + base64;
  }
}
