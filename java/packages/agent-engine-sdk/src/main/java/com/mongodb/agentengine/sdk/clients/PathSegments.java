package com.mongodb.agentengine.sdk.clients;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Safe encoding of values that become one URL path segment. */
public final class PathSegments {

  private PathSegments() {}

  /**
   * Percent-encodes an id for use as a single path segment.
   *
   * <p>Bare {@code .} and {@code ..} are refused: they survive encoding, and a URL parser would
   * resolve them into a rewrite of the parent path of an authenticated request.
   *
   * @throws IllegalArgumentException if the id is a bare dot segment
   */
  public static String encode(String id) {
    if (id.equals(".") || id.equals("..")) {
      throw new IllegalArgumentException("Invalid id: bare dot segment \"" + id + "\"");
    }
    return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
