package com.example.embeddingzoo.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns an HTML page into the visible text this demo embeds. */
public final class HtmlText {

  private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>");
  private static final Pattern STYLE = Pattern.compile("(?is)<style[^>]*>.*?</style>");
  private static final Pattern ARTICLE = Pattern.compile("(?is)<article[^>]*>(.*?)</article>");
  private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");

  private HtmlText() {}

  public static String visibleText(String html) {
    String region = html;
    var article = ARTICLE.matcher(html);
    if (article.find()) {
      region = article.group(1);
    }
    String stripped = TAG.matcher(STYLE.matcher(SCRIPT.matcher(region).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
    stripped = stripped
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
    return stripped.replaceAll("\\s+", " ").trim();
  }

  public static List<String> chunks(String text, int words, int overlap, int maxChunks) {
    String[] tokens = text.split("\\s+");
    if (tokens.length == 0 || tokens[0].isEmpty()) {
      return List.of();
    }
    if (tokens.length <= words) {
      return List.of(text.trim());
    }
    int step = Math.max(1, words - overlap);
    List<String> out = new ArrayList<>();
    for (int start = 0; start < tokens.length && out.size() < maxChunks; start += step) {
      int end = Math.min(tokens.length, start + words);
      StringBuilder chunk = new StringBuilder();
      for (int i = start; i < end; i++) {
        if (i > start) {
          chunk.append(' ');
        }
        chunk.append(tokens[i]);
      }
      out.add(chunk.toString());
      if (end == tokens.length) {
        break;
      }
    }
    return out;
  }

  public static String truncate(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars);
  }

  public static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
