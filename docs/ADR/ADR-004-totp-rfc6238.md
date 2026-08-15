# Architecture Decision Record
## Title: TOTP RFC 6238 for Disarm Authentication
- **Status**: Accepted
- **Decision**: Use TOTP (RFC 6238, HMAC-SHA1, 6-digit, 30s) with Google Authenticator QR instead of static OTP or password
- **Rationale**: Time-based codes prevent replay attacks; rate limiter (3 attempts → 5 min lockout) prevents brute force; Google Authenticator is widely available
- **Consequences**: User must set up Google Authenticator; clock skew tolerance of ±1 period
