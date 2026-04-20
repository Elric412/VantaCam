# Plan.md — Fully Worked Example

This is a realistic example of the level of detail the Executor needs. It is the output the Advisor should produce in response to the request:

> "Add Google OAuth login to my Next.js 14 App Router app. Users should stay logged in across refreshes. Store the session in a cookie. I'm using TypeScript."

Read this example end-to-end the first time you use the Advisor skill so you calibrate on how concrete "concrete" actually is. Notice that every important choice is baked into literal code, every file path is spelled out, and nothing is left to the Executor's architectural judgment.

---

```markdown
# Plan: Add Google OAuth login with persistent cookie sessions (Next.js 14 App Router)

## Context
The project is a Next.js 14 App Router application written in TypeScript. It currently has no auth system. We are adding Google OAuth sign-in using Auth.js (NextAuth v5), storing sessions in an encrypted httpOnly cookie so users stay logged in across refreshes. No database is involved — JWT-in-cookie strategy is used.

## Stack & Assumptions
- Language/runtime: TypeScript 5.x, Node 20.x
- Framework: Next.js 14.2 (App Router, `src/app` directory)
- Package manager: pnpm
- Key dependencies already installed: `next@14.2`, `react@18`, `typescript@5`
- Key dependencies to install: `next-auth@5.0.0-beta.20` (Auth.js v5)
- Environment variables required:
  - `AUTH_SECRET` — random 32-byte string. Generate locally with `openssl rand -base64 32`.
  - `AUTH_GOOGLE_ID` — Google OAuth client ID. Obtained from https://console.cloud.google.com/apis/credentials.
  - `AUTH_GOOGLE_SECRET` — Google OAuth client secret. Same source.
- Assumptions the Executor should NOT re-verify:
  - The dev server runs on `http://localhost:3000`.
  - The repo already has `src/app/layout.tsx` and `src/app/page.tsx`.

## Files to create or modify
- [ ] `src/auth.ts` (new)
- [ ] `src/app/api/auth/[...nextauth]/route.ts` (new)
- [ ] `src/middleware.ts` (new)
- [ ] `src/app/page.tsx` (modify)
- [ ] `src/app/signin/page.tsx` (new)
- [ ] `.env.local` (add three keys)
- [ ] `next.config.js` (no change — verify only)

## Steps

### Step 1: Install dependencies
- [ ] Run: `pnpm add next-auth@5.0.0-beta.20`
- [ ] Verify `package.json` contains: `"next-auth": "5.0.0-beta.20"`

### Step 2: Create `src/auth.ts`
- [ ] Create the file with the exact contents below.

```ts
import NextAuth from "next-auth";
import Google from "next-auth/providers/google";

export const { handlers, signIn, signOut, auth } = NextAuth({
  providers: [
    Google({
      clientId: process.env.AUTH_GOOGLE_ID!,
      clientSecret: process.env.AUTH_GOOGLE_SECRET!,
    }),
  ],
  session: {
    strategy: "jwt",
    maxAge: 60 * 60 * 24 * 30, // 30 days
  },
  pages: {
    signIn: "/signin",
  },
});
```

### Step 3: Create `src/app/api/auth/[...nextauth]/route.ts`
- [ ] Create the file with the exact contents below. This wires Auth.js's route handlers into Next's App Router.

```ts
import { handlers } from "@/auth";

export const { GET, POST } = handlers;
```

### Step 4: Create `src/middleware.ts`
- [ ] Create the file with the exact contents below. This protects every route except `/signin`, the auth callback, and static assets.

```ts
import { auth } from "@/auth";

export default auth((req) => {
  const isLoggedIn = !!req.auth;
  const { pathname } = req.nextUrl;

  // Allow unauthenticated access to sign-in and auth API routes.
  if (pathname.startsWith("/signin") || pathname.startsWith("/api/auth")) {
    return;
  }

  if (!isLoggedIn) {
    const signInUrl = new URL("/signin", req.nextUrl.origin);
    return Response.redirect(signInUrl);
  }
});

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
```

### Step 5: Create `src/app/signin/page.tsx`
- [ ] Create the file with the exact contents below.

```tsx
import { signIn } from "@/auth";

export default function SignInPage() {
  return (
    <main style={{ padding: 32 }}>
      <h1>Sign in</h1>
      <form
        action={async () => {
          "use server";
          await signIn("google", { redirectTo: "/" });
        }}
      >
        <button type="submit">Continue with Google</button>
      </form>
    </main>
  );
}
```

### Step 6: Modify `src/app/page.tsx`
- [ ] Open `src/app/page.tsx`.
- [ ] Replace the entire file contents with:

```tsx
import { auth, signOut } from "@/auth";

export default async function Home() {
  const session = await auth();

  return (
    <main style={{ padding: 32 }}>
      <h1>Hello {session?.user?.name ?? "stranger"}</h1>
      <form
        action={async () => {
          "use server";
          await signOut({ redirectTo: "/signin" });
        }}
      >
        <button type="submit">Sign out</button>
      </form>
    </main>
  );
}
```

### Step 7: Add environment variables to `.env.local`
- [ ] Append (or create) `.env.local` at the repo root with these three lines, substituting real values for the placeholders.

```env
AUTH_SECRET=<output of: openssl rand -base64 32>
AUTH_GOOGLE_ID=<from Google Cloud Console — OAuth 2.0 Client IDs>
AUTH_GOOGLE_SECRET=<from Google Cloud Console — OAuth 2.0 Client IDs>
```

- [ ] In the Google Cloud Console OAuth client, ensure the following is set as an Authorized redirect URI (exact string):
  `http://localhost:3000/api/auth/callback/google`

## Verification
- [ ] Run `pnpm dev`. Expect: no TypeScript errors; server starts on port 3000.
- [ ] Visit `http://localhost:3000/` in an incognito window. Expect: redirect to `/signin`.
- [ ] Click "Continue with Google". Expect: Google consent screen, then redirect back to `/` showing "Hello <your name>".
- [ ] Refresh the page. Expect: still signed in (no redirect to `/signin`).
- [ ] Click "Sign out". Expect: redirect to `/signin`.
- [ ] In DevTools → Application → Cookies, expect a cookie named `authjs.session-token` with `HttpOnly` and `Secure` flags (`Secure` only in prod — in dev over http it may be absent, this is expected).

## Known Edge Cases & Gotchas
- **Trap:** Importing from `next-auth/next` or `next-auth/react` (those are v4 patterns).
  **Do this instead:** In v5, the project-local `@/auth` export is the single source — import `auth`, `signIn`, `signOut`, and `handlers` from there only.

- **Trap:** Putting `AUTH_SECRET` in `.env` and committing it.
  **Do this instead:** Put it only in `.env.local`, which is gitignored by default in Next.js projects.

- **Trap:** The middleware matcher accidentally blocks the auth callback, causing an infinite redirect loop on sign-in.
  **Do this instead:** Keep the `pathname.startsWith("/api/auth")` early-return at the top of the middleware body exactly as shown in Step 4.

- **Trap:** Hardcoding `http://localhost:3000` as the base URL.
  **Do this instead:** Auth.js v5 infers the base URL from request headers in dev. For production deployment, set `AUTH_URL=https://yourdomain.com` in the production environment (out of scope for this plan, but note it for the user).

- **Trap:** Using the `credentials` provider or adding a custom `jwt` callback "just to be safe".
  **Do this instead:** Don't. The default JWT strategy already encrypts with `AUTH_SECRET` and stores in an httpOnly cookie — which is exactly what was requested.

## Out of Scope
Do NOT do any of the following, even if they seem helpful:
- Do not add a database adapter. JWT-in-cookie is intentional.
- Do not add additional OAuth providers (GitHub, Apple, etc.).
- Do not refactor `src/app/layout.tsx` or global styles.
- Do not add a user profile page, account settings, or role system.
- Do not upgrade `next`, `react`, or `typescript` versions.

## If Something Goes Wrong
- [ ] Do NOT improvise a fix.
- [ ] Stop, report which step number failed, paste the exact error output, and wait for a revised plan from the Advisor.
```

---

## What to notice about this example

1. **Every load-bearing line of code is literal.** The Executor never has to guess the shape of the `NextAuth` config or the middleware matcher.
2. **Every file path appears twice** — once in the top-level checklist and once as a section header — so the Executor can't drift.
3. **The Gotchas section bakes in Advisor-level knowledge** (v4 vs v5 import paths, middleware loop trap, "don't add a jwt callback just in case") that a cheaper executor model would plausibly get wrong if asked to figure it out.
4. **Out of Scope is explicit.** Without it, a helpful Executor would wander off and "improve" `layout.tsx` or add a database.
5. **No reference to the conversation** — "as the user said" appears nowhere. The plan is entirely self-contained.
