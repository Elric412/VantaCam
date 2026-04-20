# Deep Code Review — VantaCam

Date: 2026-04-20 (UTC)
Reviewer: Codex (fallback approach because `code-reviewer` skill is not available in this workspace)

## Scope reviewed

This review focused on the Bun web server + API surface because it is externally exposed and has the highest immediate risk concentration:

- `server/index.js`
- `server/lib/api-handlers.js`
- `server/lib/validation.js`

## Executive summary

I found **5 actionable issues**:

- **P1 (high): 2 issues**
- **P2 (medium): 2 issues**
- **P3 (low): 1 issue**

No obvious remote code execution was found in the reviewed files, and provider/id validation is present in most download endpoints. However, there are correctness and data-exposure weaknesses that should be addressed before production hardening.

---

## Findings

### 1) P1 — Internal skill source disclosure via `/api/command-source/:id`

**Where**
- `server/index.js`: route `/api/command-source/:id`
- `server/lib/api-handlers.js`: `getCommandSource(id)`

**Problem**
`getCommandSource(id)` returns `source/skills/<id>/SKILL.md` for any syntactically valid ID that exists. It does **not** enforce `userInvocable === true` (or any authorization layer). That means callers can fetch internal-only skill instructions by guessing IDs.

**Why this matters**
This is a data-leak vector for prompt/instruction internals and implementation details that are likely intended to remain private.

**Evidence**
- Endpoint directly proxies content from `getCommandSource` without access gate.
- `getCommandSource` validates only shape (`isValidId`) and existence.

**Recommended fix**
- Restrict endpoint to user-invocable skills only (derive from `getSkills()` metadata).
- Return 404 for non-user-invocable IDs to avoid oracle leakage.
- Optionally require auth for source download.

---

### 2) P1 — Empty static files can never be served from fallback `fetch` handler

**Where**
- `server/index.js`: `fetch(req)` fallback

**Problem**
Fallback serves a file only when `staticFile.size > 0`. Empty files (size `0`) are valid static assets (e.g., zero-byte marker files, generated placeholders), but this logic returns 404 for them.

**Why this matters**
This is a correctness bug that breaks standards-compliant static serving behavior and can cause confusing environment-dependent failures.

**Recommended fix**
- Use existence checks instead of size checks (`await staticFile.exists()`), then always return the file response regardless of size.

---

### 3) P2 — Unhandled parse failures in `getSkills()` can fail entire endpoint

**Where**
- `server/lib/api-handlers.js`: `getSkills()`

**Problem**
`parseFrontmatter(content)` runs inside a loop without per-file error isolation. A single malformed `SKILL.md` can throw and fail the whole `/api/skills` and `/api/commands` responses.

**Why this matters**
One bad file can cause total endpoint outage (availability impact).

**Recommended fix**
- Wrap per-file parse in `try/catch` and skip/report invalid entries.
- Emit structured logs with skill ID to simplify debugging.

---

### 4) P2 — Silent ID normalization for route params may create confusing behavior

**Where**
- `server/index.js`: `/skills/:id`, `/tutorials/:slug`

**Problem**
Route params are sanitized by removing non-matching characters (`replace(/[^a-z0-9-]/gi, "")`) rather than validating/rejecting. Inputs like `"abc<script>"` become `"abcscript"`, potentially resolving to an unintended page instead of returning 400.

**Why this matters**
Not a direct exploit, but it creates non-obvious URL behavior and weakens input-contract clarity.

**Recommended fix**
- Validate exact pattern (e.g., `^[a-z0-9-]+$`) and return 400 on mismatch.
- Keep sanitization only as a defense-in-depth secondary layer.

---

### 5) P3 — `sanitizeFilename` assumes string input and can throw TypeError

**Where**
- `server/lib/validation.js`: `sanitizeFilename(filename)`

**Problem**
`sanitizeFilename` calls `.replace` directly; if callers ever pass a non-string, it throws.

**Why this matters**
Current call sites likely pass strings, so risk is low today, but this utility is brittle and unsafe for reuse.

**Recommended fix**
- Guard input type and coerce or fail safely:
  - `if (typeof filename !== 'string') return 'download';`

---

## Suggested remediation order

1. **Block data leak first**: enforce user-invocable restriction on `/api/command-source/:id`.
2. **Fix static-serving correctness**: replace size-based fallback check.
3. **Harden reliability**: isolate frontmatter parsing failures per skill.
4. **Tighten input contracts**: reject invalid IDs/slugs instead of mutating.
5. **Utility hardening**: make `sanitizeFilename` type-safe.

## Quick verification checklist after fixes

- `/api/command-source/<non-user-skill>` returns 404.
- Empty file in `public/` is served with 200 from fallback route.
- Malformed frontmatter in one skill does not break `/api/skills`.
- `/skills/<invalid chars>` returns 400, not rewritten content.
- Filename sanitizer handles non-string input without throwing.
