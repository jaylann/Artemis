# atlas-cost-formal-20260927-v1: aborted before any provider contact

- Seeded 60 courses on a fresh database (12:09-12:17 CEST), run started 10:17:05Z.
- The meter's Python (python.org 3.10 framework) has no CA bundle (`etc/openssl/cert.pem` missing), so every
  HTTPS connection to api.openai.com failed with SSLCertVerificationError before any request was sent.
- Ledger: 22 reserved, 22 `unknown` with httpStatus 0 and no provider request id, 1 `blocked`
  (late request after the observation closed). No request reached OpenAI; nothing was billed.
- r01-assignment-only-s1 ended FAILED (12/12 exercise failures, mapping hash unchanged). The harness
  stopped as designed and skipped the remaining 89 observations.
- Fix: launch `campaign.py run` with `SSL_CERT_FILE=/etc/ssl/cert.pem` (verified: HTTPS returns 401 without key).
  This is runtime environment only; source fingerprint unchanged.
- Not replayed. Superseded by atlas-cost-formal-20260927-v2 on a new fresh database.
