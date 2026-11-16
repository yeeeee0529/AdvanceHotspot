# Agent Instructions

AdvanceHotspot is an Android app that adds advanced hotspot control on one rooted Pixel. These instructions apply to every agent working in this repository.

## Handoff is mandatory

[HANDOFF.md](HANDOFF.md) is the only handoff channel between agents. Do not keep handoff notes anywhere else: not in a separate notes file, not in commit messages alone, and not only in the conversation.

At the start of a session:

1. Read `HANDOFF.md` in full before you inspect or change anything.
2. Treat its "Current state", "Findings that cost time to discover" and "State left on the test phone" sections as your starting assumptions, and check any of them your task depends on.

Before you stop, for any reason, including being blocked or handing an unfinished task back:

1. Update the sections of `HANDOFF.md` that your work changed: current state, verification status, known limits, state left on the test phone, suggested next steps.
2. Add a dated entry at the top of the "Handoff log" that says what you did, what is unfinished, and any decision the owner made.
3. Record every non-obvious finding under "Findings that cost time to discover", so the next agent does not repeat the investigation.
4. Report unverified work as unverified. Never mark something verified unless you ran it and saw the result.
5. Commit `HANDOFF.md` together with the work it describes.

A session that changes code, documentation or the test phone without updating `HANDOFF.md` is incomplete.

## What does not belong in the handoff

- Secrets of any kind, including the hotspot passphrase.
- MAC addresses, SSIDs or hostnames of real devices. Describe devices by role, for example "the owner's test client".

## Project conventions

- Human documentation is `README.md`, `docs/zh-TW/README.md` and `docs/en/README.md`. Keep all three in sync when behavior, setup or status changes.
- UI text and source comments are Traditional Chinese (`zh-TW`). Identifiers, runtime diagnostics, commit messages and agent-facing files are English.
- Run this before committing:

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

- Verify behavior on the connected phone when a change touches the helper, routing, intranet or traffic code. Unit tests cover only the pure script generators and parsers.
- Do not push, create a remote, or change the KernelSU allowlist without the owner's explicit request.
