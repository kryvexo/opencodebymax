# MAXCODE native port notes

## Project origin and license

MAXCODE is a modified Android application derived from AntiGem. The upstream GPL-3.0-or-later `LICENSE`, applicable copyright notices, and upstream README are retained in this source package. MAXCODE's changes are also distributed under GPL-3.0-or-later. Do not remove or weaken the upstream notices when redistributing the app or source.

## Branding and release metadata

The app is branded **MAXCODE** and the terminal launcher is **MAXTERM**. The About screen retains the **Powered by Max** credit and links to `https://github.com/kryvexo/opencodebymax`. Release metadata is version `2.1.0`, code `119` in `version.json`.

The primary `termux` flavor uses application ID `com.kryvexo.opencode`; the `standard` flavor uses `com.kryvexo.opencode.standard`. The package IDs are intentionally preserved for upgrade continuity.

## AI providers and auth boundary

Gemini managed-agent chat uses Google's public Gemini Interactions API with a user-supplied Gemini developer API key. The app does not include the upstream private Antigravity/Google OAuth client credentials and does not use private Google-account OAuth for this route. The registered managed-agent identifier is `antigravity-preview-09-2026`.

OpenCode Zen, xAI Grok, OpenRouter, and custom OpenAI-compatible providers use chat-completions requests with server-sent-event streaming. Provider API keys are user-entered and stored encrypted with Android Keystore-backed key material. No API keys are bundled in the APK or committed in this source tree. Keys stored on a rooted or otherwise compromised device may be exposed; apps distributed publicly should consider routing provider traffic through a protected backend.

The OpenAI-compatible transport is intended for text chat-completions. Tool use, multimodal inputs, and provider-specific extensions depend on the endpoint and are not guaranteed by this implementation.

## Native features and known boundary

The port retains the upstream embedded Termux-compatible Linux environment, MAXTERM terminal launcher and SSH inspector, native PTY/foreground execution paths, PRoot/local-tool integration, Go IDE bridge, editor, workspace/file tools, Claude Code integration, and Android Compose application structure. Chat history is stored locally; conversation records retain provider and model metadata for continuation.

Gemini managed-agent execution happens in Google's remote managed environment. It is not automatically connected to the local MAXTERM filesystem or processes. Keeping the local Linux environment and remote managed agent separate is an explicit boundary of this build.

## Visual design

Native Compose surfaces use translucent fills, highlighted borders, soft colored glows, and a small orbital sparkle animation while the agent is thinking. On Android 12 and newer the backdrop uses platform blur; older Android versions retain the translucent-gradient fallback.

## Updates and GitHub release assets

The updater reads `version.json` from the repository's `main` branch at `https://github.com/kryvexo/opencodebymax`. Publish releases under tag `v<version_name>` with the matching asset name:

- `MAXCODE-termux-v<version_name>-release.apk`
- `MAXCODE-standard-v<version_name>-release.apk` when publishing the standard flavor

The app shows an update only when the remote `version_code` is greater and the expected release asset is reachable. Download and installation require the user's confirmation. A repository URL and updater configuration do not by themselves publish releases; `version.json` and APK assets still need to be pushed and attached on GitHub.

## Build and validation

Build commands:

```bash
./gradlew :app:assembleTermuxDebug
./gradlew :app:assembleTermuxRelease
```

The termux flavor Kotlin/Compose compile completed successfully during this port. The final clean APK assembly and source archive are being produced from the current tree. A physical-device runtime smoke test has not been performed in this cloud build environment.

Keep release signing keys outside the public source archive. APKs installed as updates must preserve the same application ID and signing certificate. The local debug keystore, if present, is not an acceptable public release signing key.

## Credential rotation reminder

The Groq/xAI and OpenRouter API keys pasted in the earlier conversation were exposed in plaintext. They are not included in this native source or APK. Revoke and replace those keys before using the app or publishing it publicly.


## Build verification update

The clean `:app:assembleTermuxDebug` build completed successfully after correcting a duplicate Compose `Scaffold` argument. The delivered debug APK is for testing, not for public release distribution. The inherited local keystore was confirmed to carry the standard Android Debug identity; Gradle no longer uses it for release signing. A production release must be signed with a user-controlled private key supplied via the four `MAXCODE_RELEASE_*` values documented in the README. No emulator or physical-device runtime smoke test was available in this cloud environment.
