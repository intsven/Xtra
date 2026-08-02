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
