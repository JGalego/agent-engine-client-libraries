package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Vectors;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Small HTTP helper for dataset downloads and public pages. */
public final class HttpTexts {

  public static final HttpClient HTTP = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  private HttpTexts() {}

  public static String get(URI uri) throws IOException {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMinutes(2))
        .header("User-Agent", "embedding-zoo-demo")
        .header("Accept", "text/html,application/json,text/plain,*/*")
        .GET()
        .build();
    try {
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IOException("HTTP " + response.statusCode() + " for " + uri);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while fetching " + uri, e);
    }
  }

  public static void download(URI uri, Path dest) throws IOException {
    Files.createDirectories(dest.getParent());
    Path partial = dest.resolveSibling(dest.getFileName() + ".partial");
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMinutes(30))
        .header("User-Agent", "embedding-zoo-demo")
        .GET()
        .build();
    try {
      HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() >= 400) {
        throw new IOException("HTTP " + response.statusCode() + " for " + uri);
      }
      try (InputStream in = response.body()) {
        Files.copy(in, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(partial, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while downloading " + uri, e);
    }
  }

  public static boolean blocked(String body) {
    if (body == null) {
      return true;
    }
    String lower = body.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("you have been blocked")
        || lower.contains("just a moment")
        || (lower.contains("cloudflare") && lower.contains("enable cookies"));
  }

  public static String shaOfFile(Path path) throws IOException {
    return Vectors.sha256(Files.readAllBytes(path));
  }
}
