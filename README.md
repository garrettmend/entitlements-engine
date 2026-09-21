<!-- Explains how to run and use the live control-plane frontend with the deployed backend. -->
# Control Plane — Live Frontend

`index.html` now calls the real, deployed backend directly — no simulation.
It's a static page with no build step; open it in a browser or host it
anywhere static (GitHub Pages, Netlify, etc.).

## Before it'll work

**1. Redeploy the backend.** `SecurityConfig.java` (included in this zip)
now adds CORS, which the live Railway deployment doesn't have yet — without
it, every request from this page will fail with a generic network error in
the browser (see the in-page hint if that happens). Wildcard origins are
used deliberately and safely here: auth is stateless bearer-token, not
cookie-based, so there's no ambient credential for a wildcard origin to
exploit — see the comment on `corsConfigurationSource()` for the reasoning.

**2. Have a real Cognito ID token to paste in.** The page decodes it
client-side (for display only — the backend does the real verification) to
show you which tenant and groups you're about to test as.

**3. Make sure the data lines up in Postgres**, or you'll get real (and
informative) errors instead of the demo working:
- A row in `tenants` whose `id` matches your token's `custom:tenant_id` claim
- `ENTERPRISE` tier + the `REPORT_WRITER` Cognito group, to see "Create
  report" succeed
- `PRO` or `ENTERPRISE` tier, to see "Export data" succeed

## What's real now vs. the earlier version

The previous version of this page let you freely toggle tier and group to
watch the rules react — that was possible specifically because it was
simulated. Against the real API, tier lives in Postgres and group lives
inside whatever JWT you actually have, so those toggles are gone. In
exchange, every result on this page is now a genuine HTTP response: a
denial is a real `403` from `@PreAuthorize`, a blocked duplicate is a real
unique-constraint conflict, not a JS `if` statement pretending to be one.

The two-token "compare" panel in the tenant isolation section is the
closest replacement for the old dropdown — paste two different tenants'
tokens and load both side by side to see real RLS isolation with real data.

## Files in this zip

- `index.html` — the frontend (rewritten)
- `SecurityConfig.java` — the one backend file that changed (added CORS);
  drop this into `src/main/java/com/platform/entitlements/security/` in
  your existing project and redeploy to Railway
