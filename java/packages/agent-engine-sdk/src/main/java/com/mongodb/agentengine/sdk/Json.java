package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** The shared JSON mapper for the platform wire format. */
public final class Json {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .setDefaultPropertyInclusion(
              JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private Json() {}

  /**
   * Returns the mapper used for all wire (de)serialization: snake_case names, null properties
   * omitted (null map values are kept), unknown properties ignored. It is shared and must not be reconfigured; call {@code copy()}
   * first if you need a variant.
   */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  /**
   * Parses an ISO 8601 timestamp into an {@link Instant}. A timestamp without a UTC offset is
   * read as UTC.
   *
   * @throws IllegalArgumentException if the text is not a valid timestamp
   */
  public static Instant parseInstant(String text) {
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException withOffset) {
      try {
        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException noOffset) {
        throw new IllegalArgumentException("Invalid datetime string: " + text, noOffset);
      }
    }
  }

  /** Deserializes timestamps with {@link #parseInstant(String)}. */
  public static final class LenientInstantDeserializer extends JsonDeserializer<Instant> {
    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      try {
        return parseInstant(parser.getValueAsString());
      } catch (IllegalArgumentException e) {
        return (Instant) context.handleWeirdStringValue(Instant.class, parser.getText(), "%s", e.getMessage());
      }
    }
  }
}
