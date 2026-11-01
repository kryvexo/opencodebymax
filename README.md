<div align="center">

# MAXCODE

### A native Android AI coding workspace with a local Linux terminal

**Code, chat, and work with projects from your Android device.** MAXCODE brings multi-provider AI chat, a MAXTERM terminal, local Linux tools, and a native Kotlin/Jetpack Compose interface together in one app.

[![Latest release](https://img.shields.io/github/v/release/kryvexo/opencodebymax?display_name=tag&sort=semver)](https://github.com/kryvexo/opencodebymax/releases/latest)
[![License](https://img.shields.io/github/license/kryvexo/opencodebymax?label=license)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/kryvexo/opencodebymax?style=social)](https://github.com/kryvexo/opencodebymax/stargazers)
[![Android API](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![Kotlin](https://img.shields.io/badge/Kotlin-native-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

[Download latest release](https://github.com/kryvexo/opencodebymax/releases/latest) · [Report a bug](https://github.com/kryvexo/opencodebymax/issues) · [Read the license](LICENSE)

</div>

> **Powered by Max.** MAXCODE is an open-source community project derived from AntiGem and distributed under **GNU GPL-3.0-or-later**. Upstream notices are retained; see [LICENSE](LICENSE) and [port notes](MAXCODE-PORT-NOTES.md).

## Overview

MAXCODE is a native Android application for AI-assisted coding and local development. It combines a Compose-based chat workspace with provider selection, persistent conversations, an embedded Termux-compatible Linux environment branded **MAXTERM**, and an IDE bridge for local project workflows.

The app is designed to let you choose the AI service you connect to. **Provider API keys are entered by the user; this repository and its APKs do not include bundled provider keys.**

## Features

- **Multiple AI providers:** Google Gemini developer API, OpenCode Zen, xAI Grok, OpenRouter, and custom OpenAI-compatible endpoints.
- **Streaming chat:** Live responses for supported Gemini and OpenAI-compatible chat-completions APIs.
- **Persistent conversation history:** Reopen and continue chats, with provider and model identity retained in local conversation records.
- **MAXTERM local development:** Native terminal access, local Linux/PRoot tools, workspace and file workflows, and the embedded Go IDE bridge.
- **Native Android experience:** Kotlin and Jetpack Compose UI, frosted translucent surfaces, soft blur/glow effects, and animated thinking indicators.
- **Encrypted API-key storage:** User-entered provider credentials are protected at rest using Android Keystore-backed encryption.
- **In-app update support:** The updater checks the configured GitHub repository for a newer version and asks before installation.
- **Claude Code integration:** The upstream local integration remains available where the required environment and account configuration are present.

## AI providers

| Provider | Connection | Setup |
| --- | --- | --- |
| **Gemini** | Google's public Gemini Interactions API for managed-agent chat | Add a Gemini developer API key in **Settings → Gemini API Key**. |
| **OpenCode Zen** | OpenAI-compatible API at `https://opencode.ai/inference/openai/v1` | Select the provider and enter your API key in **Settings → AI Providers**. |
| **Grok (xAI)** | OpenAI-compatible API at `https://api.x.ai/v1` | Select the provider, enter your xAI key, then choose an available model. |
| **OpenRouter** | OpenAI-compatible API at `https://openrouter.ai/api/v1` | Select the provider, enter your OpenRouter key, then choose an available model. |
| **Custom** | User-supplied OpenAI-compatible base URL | Add a custom profile, base URL, API key, and model. |

Third-party provider support currently targets **text chat-completions and streaming**. Tool calls, image inputs, and provider-specific features may differ or be unavailable; compatibility depends on the selected endpoint and model. Gemini managed-agent chats use a separate backend and do not have automatic access to files or processes inside MAXTERM.

## Get MAXCODE

Download the latest published APK from [GitHub Releases](https://github.com/kryvexo/opencodebymax/releases/latest). Install only releases you trust. Android may ask you to allow installation from your browser or file manager.

The primary `termux` flavor uses application ID `com.kryvexo.opencode`. The optional `standard` flavor uses `com.kryvexo.opencode.standard`. Android updates require the same application ID **and** the same signing certificate as the installed build. A debug build may not install over a release build, or vice versa.

## Build from source

### Requirements

- JDK 21
- Android SDK Platform 36 and the Android build tools
- Go toolchain required by the embedded IDE bridge build tasks
- Android API 24 or later for installation

From the repository root, build the primary MAXCODE variant:

```bash
./gradlew :app:assembleTermuxDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/termux/debug/MAXCODE-termux-debug.apk
```

To build the optional standard flavor:

```bash
./gradlew :app:assembleStandardDebug
```

Release builds use your own private signing identity. Supply the following values through your local shell environment or local Gradle properties—**never commit a keystore or signing password**:

```text
MAXCODE_RELEASE_STORE_FILE=/absolute/path/to/your-release-key.jks
MAXCODE_RELEASE_STORE_PASSWORD=<private-store-password>
MAXCODE_RELEASE_KEY_ALIAS=<private-key-alias>
MAXCODE_RELEASE_KEY_PASSWORD=<private-key-password>
```

Then build the release variant:

```bash
./gradlew :app:assembleTermuxRelease
```

If these signing values are absent, Gradle leaves the release APK unsigned. Debug APKs are for development and testing, **not public distribution**. Keep the release keystore backed up securely; losing it prevents signing compatible in-place updates.

## Publishing an update

The in-app updater reads [`version.json`](version.json) from the repository's `main` branch. It offers an update only when the remote `version_code` is newer and the expected APK asset is available. The user confirms before installation.

For each release:

1. Increase `version_code`, update `version_name`, and add release notes in `version.json`.
2. Build the flavor with the same private release key used for previous versions.
3. Create a GitHub release tag in the form `v<version_name>`.
4. Attach the APK with the matching asset name:

```text
MAXCODE-termux-v<version_name>-release.apk
```

For the optional standard flavor, use:

```text
MAXCODE-standard-v<version_name>-release.apk
```

The repository URL and updater are configured in the app, but publishing a GitHub release and uploading its APK are separate steps.

## Security and privacy

- **No bundled API keys:** Configure your own credentials in the app. Do not put API keys in source files, screenshots, issue reports, or public commits.
- **Encrypted at rest:** Provider keys are encrypted with Android Keystore-backed storage. No client-side storage can protect a key from every rooted, compromised, or instrumented device.
- **Provider data handling:** Prompts and code sent to a provider are subject to that provider's privacy policy, retention rules, and billing. Review them before use.
- **Public-service deployments:** If distributing to a broad audience, consider a protected server-side proxy and per-user authentication rather than embedding shared provider credentials in an APK.
- **Supported authentication:** Gemini access uses a user-supplied developer API key. MAXCODE does not provide private Google/Antigravity OAuth credentials or a private Google-account OAuth flow.
- **Signing credentials:** Keep release keystores and passwords outside this repository and outside source archives.

## Project details

| Property | Value |
| --- | --- |
| App name | MAXCODE |
| Terminal name | MAXTERM |
| Current version | 2.1.0 (`version_code` 119) |
| Primary package | `com.kryvexo.opencode` |
| Optional package | `com.kryvexo.opencode.standard` |
| Minimum Android version | API 24 |
| Main technologies | Kotlin, Jetpack Compose, Gradle, Go bridge, gRPC/Protobuf |
| License | GNU GPL-3.0-or-later |

## Contributing

Bug reports, focused improvements, and documentation contributions are welcome. Before opening an issue or pull request:

1. Search existing issues to avoid duplicates.
2. Include the app version, Android version, flavor, and clear reproduction steps.
3. Remove API keys, access tokens, private project code, and personal data from logs or screenshots.
4. Run the relevant Gradle build, for example `./gradlew :app:assembleTermuxDebug`.
5. Preserve GPL-3.0-or-later and applicable upstream notices in redistributed code.

## License and attribution

MAXCODE is a modified Android project derived from [AntiGem](docs/upstream/ANTIGEM_README.md). This distribution is licensed under **GNU GPL-3.0-or-later**. Keep the [license](LICENSE), copyright notices, and applicable upstream attribution with redistributed source and binaries.

MAXCODE is not affiliated with or endorsed by Google, Anthropic, OpenCode Zen, xAI, OpenRouter, or the Termux project. Provider and product names are used only to identify compatible services or components.
