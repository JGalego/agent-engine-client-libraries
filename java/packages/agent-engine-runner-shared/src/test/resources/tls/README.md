Test-only TLS material for `PlatformTlsTest`. The keys protect nothing: the CA's private key was
deleted after signing, and the certificates are valid for 100 years.

- `ca.pem` signed `server.pem` (localhost, 127.0.0.1) and the three `client-*` certificates, which
  differ only in how their keys are encoded (PKCS#1, SEC1, PKCS#8).
- `stranger.pem` is self-signed: a client the test server must reject.
