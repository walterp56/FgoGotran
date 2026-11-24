# FgoGotran

- Use the model selected for the session. Inspect the repo root, HEAD, dirty
  paths, and current implementation before proposing a change.
- Audit, review, investigate, “don't code/write,” and Release Phase 1 are
  read-only. Give the plan and evidence in chat. An unambiguous fix or update
  request authorizes scoped local edits. Respect any explicit approval gate.
  Approval covers the named work and actions. Ask before expanding that scope.
  Push, publication, device writes, and host-permission changes need explicit
  authorization. Reuse authorization already given for the exact action.
- Identify the original symptom and the behavior to preserve. Reproduce at the
  normal entry point where possible. Trace the first wrong value, make the
  smallest authorized change, and rerun the original case plus one relevant
  control. Check the final diff. Ask only when the answer would change scope,
  expected behavior, or an action that needs authorization.
- Report the exact input, command or runtime setup, observed result, and open
  verification. If a tool or permission is missing, use available read-only
  evidence or a representative fixture and name the remaining runtime check.
- Keep raw chats, private logs, credentials, and owner settings out of shared
  files. For handoff, give HEAD and dirty paths, approved scope, symptom,
  decisions and sources, checks and results, and the next authorized step.
  For pilots, also record the model label, harness version, and permissions.

## Load when relevant

- OCR, overlay, and LOG — Preserve Accessibility OCR and MediaProjection
  voice. Check the affected mode and visible state. LOG speaker dialogue
  always gets fresh 「 」 around its verbatim body. Check typography separately.
- Glossary or cache — Read term_builder/README.md. Check affected source TSVs
  and derived data for aliases, identity/gender collisions, and exact output.
  Check applicable standalone/scene and cold/warm paths. Rebuild only affected
  generated outputs.
- Release — Follow .agents/app-release-manager.md. Read
  FgoGotranLocal/docs/portable-releases.md for Local. If the release playbook
  is unavailable, report readiness from tracked sources and request the
  playbook before executing release steps.
- Voice profile data — Read .agents/voice-profile-builder.md when available
  and term_builder/README.md for schema and validation.
- General prompt tuning — Use the existing fgo-general-prompt-eval skill when
  available. Otherwise compare effective app requests on paired, source-backed
  cases. Prioritize quality, then latency, then prompt noise. Keep Sakura out
  of general prompt changes.
- Delegate independent evidence gathering when it saves time. Give workers
  disjoint scope and require a real checkpoint for long runs. Review their
  evidence before using it. Run the same checks serially if needed.
