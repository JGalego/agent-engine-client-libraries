package com.mongodb.agentengine.runner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The mutual TLS the platform requires between a runner and the orchestration engine.
 *
 * <p>The OE's HTTPS port asks for a client certificate. The platform supplies the certificate, its
 * key and the CA that signed the OE's own certificate as {@code TLS_CERT}, {@code TLS_KEY} and
 * {@code TLS_CA_CERT}, each as a file ({@code _PATH}, mounted in containers) or as the PEM text
 * itself ({@code _PEM}, injected in microVMs). Like the Python runner, the context trusts that CA
 * and nothing else.
 */
public final class PlatformTls {

  private static final Pattern PEM_BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);
  // DER: the rsaEncryption AlgorithmIdentifier, and the id-ecPublicKey OID.
  private static final byte[] RSA_ALGORITHM = hex("300d06092a864886f70d0101010500");
  private static final byte[] EC_PUBLIC_KEY_OID = hex("06072a8648ce3d0201");

  private PlatformTls() {}

  /**
   * Builds the client context from the environment.
   *
   * @throws IllegalStateException if the certificate, key or CA is missing or unreadable. A runner
   *     that must use mTLS never falls back to an unauthenticated connection.
   */
  public static SSLContext fromEnvironment(Map<String, String> env) {
    String cert = material(env, "CERT");
    String key = material(env, "KEY");
    String ca = material(env, "CA_CERT");
    if (cert == null || key == null || ca == null) {
      throw new IllegalStateException(
          "An https OE needs TLS_CERT, TLS_KEY and TLS_CA_CERT (each as _PATH or _PEM); missing: "
              + String.join(", ", missing(cert, key, ca)));
    }
    try {
      return context(cert, key, ca);
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Cannot use the platform's TLS material: " + e.getMessage(), e);
    }
  }

  /** A context that presents {@code certPem} and trusts only {@code caPem}. */
  static SSLContext context(String certPem, String keyPem, String caPem) throws GeneralSecurityException, IOException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");

    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    int index = 0;
    for (Certificate anchor : certificates(factory, caPem)) {
      trust.setCertificateEntry("ca-" + index++, anchor);
    }
    TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trust);

    Certificate[] chain = certificates(factory, certPem).toArray(new Certificate[0]);
    char[] noPassword = new char[0];
    KeyStore identity = KeyStore.getInstance("PKCS12");
    identity.load(null, null);
    identity.setKeyEntry("client", privateKey(keyPem, chain[0].getPublicKey().getAlgorithm()), noPassword, chain);
    KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(identity, noPassword);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    return context;
  }

  /** PEM text from {@code TLS_<type>_PATH} (a file) or else {@code TLS_<type>_PEM}; null if neither is set. */
  private static String material(Map<String, String> env, String type) {
    String path = env.get("TLS_" + type + "_PATH");
    if (path != null && !path.isBlank()) {
      try {
        return Files.readString(Path.of(path.trim()));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot read TLS_" + type + "_PATH " + path + ": " + e.getMessage(), e);
      }
    }
    String pem = env.get("TLS_" + type + "_PEM");
    return pem == null || pem.isBlank() ? null : pem;
  }

  private static List<String> missing(String cert, String key, String ca) {
    List<String> names = new ArrayList<>();
    if (cert == null) {
      names.add("TLS_CERT");
    }
    if (key == null) {
      names.add("TLS_KEY");
    }
    if (ca == null) {
      names.add("TLS_CA_CERT");
    }
    return names;
  }

  private static Collection<? extends Certificate> certificates(CertificateFactory factory, String pem)
      throws GeneralSecurityException {
    Collection<? extends Certificate> found =
        factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    if (found.isEmpty()) {
      throw new GeneralSecurityException("no certificate in the PEM");
    }
    return found;
  }

  /**
   * Reads a PKCS#8 key as is, and wraps a PKCS#1 (RSA) or SEC1 (EC) key into PKCS#8, the only form
   * the JDK parses.
   */
  private static PrivateKey privateKey(String pem, String algorithm) throws GeneralSecurityException {
    Matcher block = PEM_BLOCK.matcher(pem);
    while (block.find()) {
      byte[] der = Base64.getMimeDecoder().decode(block.group(2));
      byte[] pkcs8 =
          switch (block.group(1)) {
            case "PRIVATE KEY" -> der;
            case "RSA PRIVATE KEY" -> der(0x30, concat(INTEGER_ZERO, RSA_ALGORITHM, der(0x04, der)));
            case "EC PRIVATE KEY" ->
                der(0x30, concat(INTEGER_ZERO, der(0x30, concat(EC_PUBLIC_KEY_OID, curveOid(der))), der(0x04, der)));
            default -> null;
          };
      if (pkcs8 != null) {
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
      }
    }
    throw new GeneralSecurityException("no private key in the PEM");
  }

  /** The named-curve OID from a SEC1 key's explicit {@code [0]} parameters. */
  private static byte[] curveOid(byte[] sec1) throws GeneralSecurityException {
    for (int i = 0; i + 2 < sec1.length; i++) {
      if ((sec1[i] & 0xff) == 0xa0 && (sec1[i + 2] & 0xff) == 0x06) {
        return Arrays.copyOfRange(sec1, i + 2, i + 2 + (sec1[i + 1] & 0xff));
      }
    }
    throw new GeneralSecurityException("EC key without a named curve");
  }

  private static final byte[] INTEGER_ZERO = {0x02, 0x01, 0x00};

  /** A DER element with the given tag. */
  private static byte[] der(int tag, byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    if (content.length < 0x80) {
      out.write(content.length);
    } else {
      int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(content.length) + 7) / 8;
      out.write(0x80 | bytes);
      for (int shift = 8 * (bytes - 1); shift >= 0; shift -= 8) {
        out.write(content.length >> shift);
      }
    }
    out.writeBytes(content);
    return out.toByteArray();
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static byte[] hex(String value) {
    byte[] out = new byte[value.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(value, 2 * i, 2 * i + 2, 16);
    }
    return out;
  }
}
