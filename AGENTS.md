# AGENTS.md

## Tool Usage

- **Every tool call MUST have an explicit `timeout` parameter.** No exceptions.
- File reads, grep, glob: 10-15s timeout.
- ADB commands: 15s timeout.
- Web requests: 10s timeout.
- Build commands (gradle): 300s (5 min) timeout.
- If a tool times out, report it and move on — do NOT retry endlessly.
- If you encounter repeated tool failures or hangs, stop and report the issue to the user.
- Never let a tool block the session for more than its timeout. If the default (120s) would be hit, set a shorter explicit timeout.

## Build Settings

- Limit Gradle workers to 12 CPU cores: `org.gradle.workers.max=12` in `gradle.properties`
- Gradle cache must use `D:\gradle_home` (`$env:GRADLE_USER_HOME = "D:\gradle_home"`)
- Build command: `.\gradlew.bat assembleRelease`

## Device Testing

- `.39` device (Pixel 8 Pro): SSH via `ssh u0_a140@192.168.178.39 -p 8022`; install APK via `su -c 'pm install -r /sdcard/Download/<file>.apk'` (SELinux blocks `pm install` from shell without `su`)
- OnePlus (`bde365d8`): connect via USB for ADB install

## Twitch Link Routing

- **Chat links** route directly to Xtra's `MainActivity` via explicit intent (works regardless of other apps)
- **External links** (Chrome, etc.) CANNOT be intercepted by Xtra — Twitch does not host an `assetlinks.json` declaring Xtra as a handler, so Android App Link verification prevents Chrome interception. This is a platform limitation.
- `OPEN_TWITCH_LINKS_IN_XTRA` preference (default `true`) controls internal routing
