# Architecture Decision Record
## Title: SMS Decoding on Telegram Bot (Server-side)
- **Status**: Accepted
- **Decision**: The Telegram Bot (/decode command) decrypts encrypted SMS payloads, not the Android client
- **Rationale**: Keeps decryption keys away from the physical device that could be stolen; the bot runs on a separate device
- **Consequences**: User must have Telegram access to read SMS contents
