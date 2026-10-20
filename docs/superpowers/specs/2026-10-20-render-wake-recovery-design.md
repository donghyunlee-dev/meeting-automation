# Render Wake Recovery Design

## Goal

When a request to the configured backend origin stalls or fails because the Render service is still waking, show a polished recovery overlay, keep checking backend availability, and dismiss the overlay with a brief “ready” animation after the backend responds. Do not send periodic keep-alive calls while the app is idle, show elapsed seconds, or claim a precise recovery percentage or ETA.

## Current application context

The frontend resolves the backend origin in `frontend/src/apiBase.ts`. API calls use direct `fetch` from 11 modules and currently translate transport errors into module-specific errors. Some long-running operations implement their own timeouts. The Spring backend includes Actuator and exposes the health endpoint with component details hidden. The app shell is rendered from `frontend/src/App.tsx`.

This means recovery detection belongs at a shared frontend API boundary, with recovery UI mounted once at the app shell. It must not be duplicated across pages or create concurrent poll loops for concurrent failed requests.

## Trigger and classification

1. For a backend-origin API request, detect a transport failure, a configured request timeout, or an upstream gateway response such as 502/503/504.
2. Probe the backend health endpoint on the same configured origin.
3. If health responds successfully, treat the original request as an ordinary API failure and do not show the recovery overlay.
4. Show the overlay only while the health probe cannot reach a ready backend. Poll at a short fixed interval, prevent overlapping probes, and stop on recovery or app teardown.
5. A browser cannot distinguish a sleeping Render instance from a client network outage if both make the origin unreachable. Use neutral user-facing wording such as “서버 연결을 확인하고 있어요”; do not claim Render itself is confirmed to be asleep.

## Recovery overlay

Mount one accessible, viewport-centered overlay above the current page. It may occupy substantial screen space, with a calm ice-to-open-door visual: a softly pulsing frozen mark, gradual cracks and warm light while checking, then a short opening/reveal animation after health succeeds. Show a concise status label, no elapsed seconds, fake percentage, or ETA. Respect `prefers-reduced-motion`; keep the message understandable without animation. Prevent duplicate overlays and polling loops.

On successful health response, show the ready/open state briefly, then dismiss and return focus to the prior page. If recovery does not occur, retain the overlay with a clear retry action and an exit path; do not trap the user indefinitely.

## Original request behavior

After health recovers, automatically retry only requests known to be safe: read-only requests and mutations protected by an `Idempotency-Key`. Do not replay an uncertain, unkeyed mutation because the server may have processed the original request before the connection failed. In that case, dismiss the recovery overlay after readiness and preserve the existing operation-specific error/retry path.

Retry an eligible original request at most once per recovery episode. Keep existing operation-specific timeout durations and abort behavior. User cancellation and route-unmount aborts must not open the overlay.

## Scope

- Add a shared frontend API transport/recovery coordinator and connect the API modules that call the backend to it.
- Add one app-shell recovery overlay and its responsive, reduced-motion styling.
- Reuse the configured backend origin and existing Actuator health endpoint; no backend contract or Render configuration change is planned.

## Validation

Verify the classification and recovery state transitions with focused automated coverage during implementation, then run the frontend production build. Do not test against production Render as part of local implementation; deployment verification must be a separate deliberate step.

## Open implementation detail

Confirm that the deployed health URL is reachable cross-origin from the Vercel frontend and returns a ready status before relying on it. If the current health path is not usable from the browser, add a minimal public application health endpoint with no integration details and use that endpoint instead.
