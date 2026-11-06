<p align="center">
  <img src="docs/banner.png" alt="OpenCode Mobile">
</p>

# OpenCode Mobile

An Android app that runs its own [OpenCode](https://opencode.ai) server on the phone. It unpacks Ubuntu 24.04 in proot (no root needed), starts `opencode serve` on `127.0.0.1` and puts a native mobile UI on top of it. You don't need a computer, but you can also connect to OpenCode running on one.

Android 8+, arm64. Unofficial client. The newest build is on [Releases](../../releases).

<p align="center"><a href="https://sociabuzz.com/hirateam"><img src="https://img.shields.io/badge/Donate-Sociabuzz-5b6ef5?style=for-the-badge" alt="Donate on Sociabuzz"></a></p>

<p align="center">
  <img src="docs/screenshots/home.jpg" width="19%" alt="Home">
  <img src="docs/screenshots/drawer.jpg" width="19%" alt="Side menu">
  <img src="docs/screenshots/add-to-session.jpg" width="19%" alt="Add to session">
  <img src="docs/screenshots/usage.jpg" width="19%" alt="Usage limits">
  <img src="docs/screenshots/providers.jpg" width="19%" alt="Providers">
</p>

## Install

1. Download the APK from [Releases](../../releases) and install it. Android asks you to allow installs from this source.
2. On first launch the app downloads Ubuntu Base and OpenCode, so you need internet and some free space.
3. Open Settings → Providers, connect a provider or pick a free model, and start chatting.

Later updates arrive inside the app. This is a beta, so please report bugs in [Issues](../../issues).

## Features

**Chat and code.** Plain chats and project sessions share one UI. The agent's work is a single summary line that opens into a card of steps; each step shows the command and its output, the diff or the thought. Command output streams into the chat while it runs. After a task, Changes lets you keep or undo each edited file, and Search looks inside messages and memory.

**Providers and models.** OpenCode Zen and Go, the free models, GitHub Copilot, Pollinations (no account), or your own key for any provider OpenCode supports.

**Kamihub.** Settings → Providers → Add provider → Kamihub (`https://kamihub.us.ci/v1`, OpenAI-compatible). Paste the API key; the app tests it with a chat request, reads `/v1/models`, registers every model it returns and stores the key. If the server refuses to save the provider config, the app repairs file permissions and writes the config file directly.

**Skills.** Type `/` in the chat to pick a skill or command. Skills are regular `SKILL.md` folders: import ones from Claude or ChatGPT (.zip, .skill or SKILL.md), paste the instructions of a custom GPT or a Claude project, or ask the agent to write one. Default skills ship with the app: `anti-ai-slop-writing`, `reverse-engineer-anything` an engineering set of 25 (`using-agent-skills` picks the right one for the task: spec, plan, build, test, debug, review, ship), Superpowers (15 process skills), Ponytail (6) and Caveman (6). Caveman, Ponytail and Superpowers are applied to every prompt through the always-on block in AGENTS.md; say "stop caveman", "stop ponytail" or "normal mode" to turn them off. Run `tools/fetch-default-skills.sh` to replace the bundled copies with the upstream files.

**Team of agents.** Settings → Capabilities → Team. Define the helpers yourself (name, role, model) or let the main agent decide how many it needs. Helpers run in parallel as OpenCode subagents.

**Permissions.** Choose Ask, Auto-edit or Auto for new sessions, and set global rules for file edits, shell commands, web fetch and access outside the project.

**GitHub.** Sign in once with a device code. The app installs git and the GitHub CLI in Ubuntu and sets git to your name and noreply email, so the agent can clone, commit, push and open pull requests.

**Phone tools.** The app gives the agent an MCP server to show notifications, set reminders, read and fill the clipboard, take a photo, and save or share files. Each tool can be switched off. Sites the agent builds open in a live preview inside the app.

**Usage.** A ring in the top bar shows the 5-hour, weekly and monthly limits and what fills the context window (system prompt, tools, MCP, skills, memory, messages).

**Memory.** The agent keeps facts as markdown files under `/root/memory`. View and edit them in Settings → Memory.

**Interrupted tasks.** If Android kills the server in the middle of a task, the chat offers Continue or Cancel when you come back. Progress can be shown on the lock screen, with Allow and Deny buttons for permission requests.

**Your computer.** Remote devices → Add device → Scan QR code. On the computer run `curl -fsSL https://raw.githubusercontent.com/mistqkw/opencode-mobile/main/tools/connect-qr.sh | sh`; it starts OpenCode on your local network with a new password and prints a QR code. The phone and the computer must be on the same network.

**Backup and updates.** Settings → Backup & move saves chats, sign-ins, memory, skills, projects and settings into one file you can restore on another phone. The app checks GitHub releases and installs updates with one tap.

**Looks.** Material 3 with dynamic colors on Android 12+, plus several alternative looks in Settings.

## Server settings

Settings → OpenCode controls the local server.

- **Restart** stops the server, runs `pkill -f opencode` to clear anything left over, then starts it again.
- **Run as root** starts proot through `su` (Magisk / KernelSU) so Ubuntu can read the Termux home and other protected paths. Switching it on or off first kills the running server in the old mode, then changes the mode and starts the server again. Switching it off hands files created as root back to the app.
- **Compatibility mode** disables proot's seccomp acceleration; try it if the server keeps crashing. It restarts the server too.

In root mode the server creates files as real root. The app takes ownership of its config folders (`.config`, `.local`, `work`) before every start, otherwise it can't read or write its own config.

## How it works

- `runtime/` downloads and unpacks the rootfs and OpenCode and runs them through proot (taken from Termux, shipped as `libproot.so` in `jniLibs`). `ServerService` is a foreground service that keeps the server alive.
- `api/` is a REST and SSE client for OpenCode 2.x. `EventHub` translates the server's `session.*` events.
- `data/` holds chat state, the model catalog, usage tracking, memory, skills, GitHub sign-in, the phone MCP server (`PhoneBridge`), custom providers, backup and updates.
- `ui/` contains the Compose screens; looks are defined in `ui/theme/Looks.kt`.
- `tools/` has `connect-qr.sh` (connect to a computer) and `fetch-default-skills.sh`.

OpenCode is pinned to 2.2.5 (`RuntimeController.OC_VERSION`), the version the client targets.

`targetSdk` is 28 on purpose: from 29 on, Android does not allow running binaries from the app's data directory, and proot needs that (Termux does the same). For the same reason the app is distributed as an APK and not through Google Play.

## Build

```
./gradlew :app:assembleDebug
```

JDK 21 is required. Release builds are made by GitHub Actions on every push to `main` (`.github/workflows/release.yml`). The version lives in `gradle.properties` (`appVersionName` / `appVersionCode`); CI appends the build number to versionName and uses the run number as versionCode, so run #36 publishes `v0.0.2-beta-1-build36` (versionName `0.0.2 beta-1 build 36`). Set the repository variable `VERSION_OFFSET` to shift the build number. The workflow runs the unit tests, builds, signs with `keystore/release.jks` (see `keystore/README.md`), checks the signature and publishes the APK with a `.sha256` file.

**Private repo and the in-app updater.** The updater reads releases of `updateRepo` (`gradle.properties`) through the public GitHub API, so it gets HTTP 404 if that repo is private. Either keep a separate public repo that only holds releases (set the repository variable `RELEASE_REPO`, the secret `RELEASE_TOKEN` and `updateRepo`), or make the releases repo public.

## Not tested yet

Phones other than a Pixel, Android 14–16 on other firmware, light themes of every look, Copilot on paid plans, and restoring a backup on a different phone.

## Support

If the app is useful to you, you can support its development: **[sociabuzz.com/hirateam](https://sociabuzz.com/hirateam)**

## Credits

- [OpenCode](https://opencode.ai): the agent and server this app is built around.
- [REA](https://github.com/morluto/rea): source of the bundled `reverse-engineer-anything` skill.
- [agent-skills](https://github.com/addyosmani/agent-skills) by Addy Osmani: the engineering skill set (MIT, license kept in `default-skills/_references`).
- [superpowers](https://github.com/obra/superpowers) by Jesse Vincent (MIT), [ponytail](https://github.com/DietrichGebert/ponytail) by DietrichGebert (MIT) and [caveman](https://github.com/juliusbrussee/caveman) by Julius Brussee (Apache-2.0): bundled default skills; their licenses are in `default-skills/_licenses`.
- [Claude](https://claude.ai): helped write and debug the code.
- [mistqkw](https://github.com/mistqkw): [github.com/mistqkw](https://github.com/mistqkw)

## License

The app's own code is MIT (see `LICENSE`).

OpenCode Mobile is an unofficial client and is not affiliated with the OpenCode developers. "OpenCode" is the name of their project (MIT). The app includes proot and libtalloc from Termux (GPL-2.0 / LGPL-3.0), whose sources are in the Termux project, and downloads Ubuntu Base 24.04 and OpenCode on first launch. Those components keep their own licenses. Fonts: Inter, Source Serif 4 and Unbounded, all SIL OFL 1.1.
