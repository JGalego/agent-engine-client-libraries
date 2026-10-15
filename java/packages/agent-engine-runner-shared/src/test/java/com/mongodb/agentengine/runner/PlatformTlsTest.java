package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the client against an HTTPS server that, like the OE, requires a client certificate. */
class PlatformTlsTest {

  private HttpsServer server;
  private String url;

  @BeforeEach
  void startServer() throws Exception {
    SSLContext context = PlatformTls.context(resource("server.pem"), resource("server.key"), resource("ca.pem"));
    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(context) {
          @Override
          public void configure(HttpsParameters params) {
            SSLParameters parameters = context.getDefaultSSLParameters();
            parameters.setNeedClientAuth(true);
            params.setSSLParameters(parameters);
          }
        });
    server.createContext("/", exchange -> {
      byte[] body = ("who: " + clientName(exchange)).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    url = "https://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pkcs1", "sec1", "pkcs8"})
  void presentsTheClientCertificateWhateverTheKeyEncoding(String encoding) throws Exception {
    Map<String, String> env = Map.of(
        "TLS_CERT_PEM", resource("client-" + encoding + ".pem"),
        "TLS_KEY_PEM", resource("client-" + encoding + ".key"),
        "TLS_CA_CERT_PEM", resource("ca.pem"));

    var response = OeClient.fromEnvironment(url, Duration.ofSeconds(5), env).postJson("/", Map.of(), 1);

    assertEquals("who: CN=client-" + encoding, response.body());
  }

  @Test
  void readsTheMaterialFromFiles() throws Exception {
    Map<String, String> env = Map.of(
        "TLS_CERT_PATH", path("client-sec1.pem"),
        "TLS_KEY_PATH", path("client-sec1.key"),
        "TLS_CA_CERT_PATH", path("ca.pem"));

    var response = OeClient.fromEnvironment(url, Duration.ofSeconds(5), env).postJson("/", Map.of(), 1);

    assertEquals("who: CN=client-sec1", response.body());
  }

  @Test
  void aServerWithAnotherCaIsNotTrusted() throws Exception {
    Map<String, String> env = Map.of(
        "TLS_CERT_PEM", resource("client-sec1.pem"),
        "TLS_KEY_PEM", resource("client-sec1.key"),
        "TLS_CA_CERT_PEM", resource("stranger.pem"));

    assertThrows(UncheckedIOException.class,
        () -> OeClient.fromEnvironment(url, Duration.ofSeconds(5), env).postJson("/", Map.of(), 1));
  }

  @Test
  void aClientCertificateFromAnotherCaIsRejected() throws Exception {
    Map<String, String> env = Map.of(
        "TLS_CERT_PEM", resource("stranger.pem"),
        "TLS_KEY_PEM", resource("stranger.key"),
        "TLS_CA_CERT_PEM", resource("ca.pem"));

    assertThrows(UncheckedIOException.class,
        () -> OeClient.fromEnvironment(url, Duration.ofSeconds(5), env).postJson("/", Map.of(), 1));
  }

  @Test
  void anHttpsOeWithoutTlsMaterialFailsClosed() {
    var e = assertThrows(IllegalStateException.class,
        () -> OeClient.fromEnvironment(url, Duration.ofSeconds(5), Map.of("TLS_CERT_PATH", path("client-sec1.pem"))));

    assertTrue(e.getMessage().contains("TLS_KEY, TLS_CA_CERT"), e.getMessage());
  }

  @Test
  void aPlainHttpOeNeedsNoTlsMaterial() {
    OeClient.fromEnvironment("http://localhost:1", Duration.ofSeconds(1), Map.of());
  }

  private static String clientName(com.sun.net.httpserver.HttpExchange exchange) {
    if (exchange instanceof com.sun.net.httpserver.HttpsExchange https) {
      try {
        return ((X500Principal) https.getSSLSession().getPeerPrincipal()).getName();
      } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
        return "unverified";
      }
    }
    return "plain";
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = PlatformTlsTest.class.getResourceAsStream("/tls/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  private static String path(String name) throws Exception {
    return java.nio.file.Path.of(PlatformTlsTest.class.getResource("/tls/" + name).toURI()).toString();
  }
}
