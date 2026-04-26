# Known Issues Registry

Every entry is a future work item observed during the P0-P4 build-and-wire hardening pass (see `/Plan.md`). Entries are grouped by category; the registry is append-only.

| File | Category |
|---|---|
| `build.md` | Gradle / AGP / NDK |
| `di.md` | Hilt / DI graph |
| `processing.md` | Algorithmic wiring — cross-linked to `/processing-problems.md` |
| `sdk.md` | Android SDK / permissions / target API |
| `wiring.md` | Module-graph / architectural debt |

## Ledger format

Each row in a file is:

```markdown
### I<yyyy-mm-dd>-<n>: <short title>
- Observed in: <file:line or commit sha>
- Severity: <BLOCKER | MAJOR | MINOR | STYLE>
- Owner: <team / skill>
- Linked plan: <Plan.md#subplan or external>
- Resolution: <open | deferred | in-progress | resolved in commit X>
```
