# Agent instructions

Follow `CLAUDE.md` and `.claude/skills/kotlin-spring-guidelines/references/strict-rules.md` for work in this repository.

## Environment file privacy

- Never read, inspect, search the contents of, copy, print, quote, write or edit `.env` files, including `.env.*` and `*.env`, anywhere in the workspace. This includes example/template environment files.
- Exclude these files explicitly from content searches, bulk reads, archives and diagnostic collection. Do not access their contents indirectly through another tool, subprocess, browser, log or generated artifact.
- Never print or inspect secret environment variable values, including `SOLANA_RPC_URL`. Application code may consume environment configuration without exposing its contents to the agent.
- If configuration is needed, ask the user to set the variable locally; ask only for its name or whether it is configured, never its secret value.
