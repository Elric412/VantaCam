# Plan.md — Blank Template

Use this as the starting skeleton whenever you produce a `Plan.md`. Fill in every `<placeholder>`; do not leave any behind. Delete sections only if they are genuinely N/A for the task — and if you delete one, briefly say why at the top of the plan so the Executor knows it wasn't forgotten.

---

```markdown
# Plan: <one-line task name>

## Context
<2-4 sentences. What is being built, in what project, with what stack.
Repeat everything the user told the Advisor — the Executor cannot see
the original conversation.>

## Stack & Assumptions
- Language/runtime: <e.g. TypeScript 5.x, Node 20.x>
- Framework: <e.g. Next.js 14.2 App Router>
- Package manager: <npm | pnpm | yarn | bun>
- Key dependencies already installed: <list with versions, or "none relevant">
- Key dependencies to install: <list with exact versions and flags>
- Environment variables required:
  - `VAR_NAME_1` — <what it is, where it comes from>
  - `VAR_NAME_2` — <what it is, where it comes from>
- Assumptions the Executor should NOT re-verify: <e.g. "the repo already
  has a working dev server on port 3000">

## Files to create or modify
Checklist the Executor should copy to the top of their working buffer:
- [ ] `path/to/file1.ts` (new)
- [ ] `path/to/file2.ts` (modify)
- [ ] `path/to/file3.ts` (modify)
- [ ] `.env.local` (add keys)

## Steps

### Step 1: Install dependencies
- [ ] Run: `<exact command>`
- [ ] Verify `package.json` contains: `"<package>": "<version>"`

### Step 2: Create `path/to/file.ts`
- [ ] Create the file with the exact contents below.

```<language>
<complete, copy-paste-ready code block>
<include all imports, full function bodies, no `// ...` placeholders>
```

### Step 3: Modify `path/to/other.ts`
- [ ] Open `path/to/other.ts`.
- [ ] Locate this block:
```<language>
<exact old snippet the Executor should find>
```
- [ ] Replace it with:
```<language>
<exact new snippet>
```

### Step 4: <next action — keep steps atomic and verifiable>
- [ ] ...

<Repeat until all files in the checklist above are covered.>

## Verification
The Executor should run each of these and confirm the expected result
before declaring the task complete.

- [ ] Run `<command>`. Expect: `<exact output or observable behavior>`.
- [ ] Visit `<url>` in a browser. Expect: `<what the user sees>`.
- [ ] Run test suite: `<command>`. Expect: all tests green.
- [ ] Tail logs: `<command>`. Expect: no errors matching `<pattern>`.

## Known Edge Cases & Gotchas
These are the places where a naive implementation goes wrong. Follow the
"Do this instead" column exactly.

- **Trap:** <failure mode a less-careful model would fall into>
  **Do this instead:** <correct behavior, with file:line reference if relevant>

- **Trap:** <another trap>
  **Do this instead:** <correction>

## Out of Scope
Do NOT do any of the following, even if they seem helpful:
- <thing 1 that is tempting but not part of this task>
- <thing 2 — e.g. "do not refactor unrelated files">
- <thing 3 — e.g. "do not upgrade any other package versions">

## If Something Goes Wrong
If any verification step fails or the code as written does not compile:
- [ ] Do NOT improvise a fix.
- [ ] Stop, report which step failed, paste the exact error, and wait
      for a revised plan from the Advisor.
```

---

## How to use this template

1. Copy the block above into your working buffer.
2. Replace every `<placeholder>` with concrete content.
3. For every step that involves writing code, paste the actual code — do not describe it.
4. For every step that involves editing existing code, paste both the old snippet (what to find) and the new snippet (what to replace it with).
5. Before you emit the plan, re-read it as if you were a junior contractor who had never seen the user's message. Every question that pops into your head is a section of the plan that is still too vague.
