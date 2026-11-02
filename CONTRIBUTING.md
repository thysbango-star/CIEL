# Contributing

1. **Keep the core pure.** Anything that does not need the Android SDK belongs in a JVM module with tests in `testing/`.
2. **Every device effect goes through `GuardedDevice`.** No skill may call the accessibility service, intents or network side effects that bypass the stop check, risk policy and confirmation gate.
3. **No fake success.** Tools return `ToolOutcome(success, verified)`. Only set `verified = true` after reading back the effect.
4. **No secrets in code, logs or tests.** Use `Redaction` for any error text that might contain a key.
5. **Untrusted content stays data.** Anything from web, screen, notifications or files must be returned with `ToolOutcome.external(...)`.
6. Add or update tests; run `./gradlew :testing:run` before opening a PR.
7. Document limitations honestly in `PERMISSIONS.md` / `DEVELOPMENT_LOG.md`.

### Adding a provider protocol
Implement `AiProvider`, add a `ProtocolKind`, extend `ProviderFactory`, add request/response tests with `FakeTransport`.

### Adding a skill
Implement `Skill` (or ship a JSON `DeclarativeSkill`), declare `requiredCapabilities`, register it in `AppGraph`.
