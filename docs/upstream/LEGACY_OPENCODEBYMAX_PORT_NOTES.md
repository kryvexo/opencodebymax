# OpenCode by Max — Native Android Port

This Android release is based on the GPL-3.0-or-later AntiGem project. Upstream copyright and license notices are retained in `LICENSE` and the source tree. The launcher/package identity is OpenCode by Max (`com.kryvexo.opencode`, embedded `termux` flavor), release `2.0.0` / version code `118`.

## What is included

The native Android app retains AntiGem's embedded Termux environment and terminal, project/file tools, IDE surfaces, chat history UI, background update worker, and the upstream Claude Code integration. Antigravity-managed chat is routed through Google's public Gemini Interactions API and its managed-agent ID `antigravity-preview-09-2026`. Chat history and remote interaction/environment IDs are saved locally so an existing remote managed-agent session can continue.

## Authentication and capability boundary

This port does **not** use Google-account OAuth for the Antigravity IDE product, and does not include the upstream Antigravity OAuth client credentials. Google documents a supported Gemini developer API route instead. Open **Settings → Gemini API Key**, create a key in Google AI Studio, and save it on-device. The app encrypts the saved key with Android Keystore; no API key is built into the APK.

The managed agent runs in Google's remote managed environment. The separate on-device Termux shell remains available in the app, but this release does not connect that remote agent to the local Termux filesystem/processes or to custom local MCP servers. Arbitrary OpenAI-compatible custom-provider profiles are not part of this native port yet. For public production distribution, a device-side key can still be extracted from a compromised/rooted device; Google recommends using a protected backend proxy rather than shipping a server credential in a client app.

## GitHub updates

The update checker reads `version.json` from the `main` branch of `https://github.com/kryvexo/opencodebymax`. A release should use tag `v<version_name>` and attach APK assets named `OpenCodeByMax-termux-v<version_name>-release.apk` and, if built, `OpenCodeByMax-standard-v<version_name>-release.apk`. The APK only offers an update when the remote `version_code` is greater and the matching release asset is reachable. The updater links are configured, but the repository currently has no published release; the feature becomes active once `version.json` and release assets are pushed/published there.

For the next release, increment `version_code` in `version.json` and update `version_name`/changelog before creating the GitHub Release. The app checks the manifest in the background and downloads/installs only after the user chooses to proceed.

## Building

Use JDK 21, Android SDK/platform tools, and Go 1.26.5 (the embedded Go bridge is built for Android ARM64). With those tools configured in the environment:

```bash
./gradlew :app:assembleTermuxRelease
```

The output is under `app/build/outputs/apk/termux/release/`. Rename/copy the APK to the versioned GitHub asset name above when preparing a release. The `app/debug.keystore` used for the local upgrade-compatible build is intentionally excluded from source packages; it must never be published. For production releases, configure a private signing key outside the repository and preserve the same signer for in-place app updates.

## Credential hygiene

The Groq and OpenRouter keys previously pasted into the chat are not embedded in this native APK or source package. Because they were shared in plaintext, revoke/rotate them before public distribution.
