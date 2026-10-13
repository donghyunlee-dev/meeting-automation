# TASK-005.02 verification

> Issue #20 · PR #113 · Recording controls and MediaRecorder lifecycle

## Automated checks

| Command | Result |
| --- | --- |
| `npm test -- --run` | PASS, 93 tests across 6 files |
| `npm run lint` | PASS |
| `npm run build` | PASS, TypeScript and Vite |
| `npx playwright test --config=playwright.config.ts` | PASS, Chromium fake-media QA, 1 test |
| `git diff --check` | PASS |
| GitHub PR workflow | PASS, `Validate Gradle Wrapper` |

Commands ran on Windows with Node 22.23.3. The exact implementation head reviewed, tested, and QA'd was `97a6e82ac1a01a8dc1f4d9f1431a610ac3001e01`, based on `acd6b066b2dd4f2c77e3ccb259d3b63f829d73d5`.

## Browser QA

- QA used Chromium with a fake microphone and recorder on a fresh Vite server at `http://127.0.0.1:5177`; the server returned HTTP 200 and was stopped after QA.
- All application API routes were intercepted with synthetic responses. No real microphone, provider API, or external write was used.
- Ready → Recording → Paused → Recording → completed passed. Microphone access was not requested before Start and was requested once after Start.
- Elapsed time stayed at `00:00:12` while paused, resumed counting, and remained at `00:00:22` on completion. The recorder became inactive and its track ended after stop.
- With the 15-second upload policy, recorder timeslice was `15000` and the selected MIME was `audio/webm;codecs=opus`.
- A synthetic route exit while recording released the track and recorder. Synthetic `NotAllowedError` returned to Ready with safe guidance and did not expose the raw error.
- At 360px there was no horizontal overflow; action buttons were 324×48px. Reduced-motion removed the recording transition.

## Review, merge, and acceptance

The independent current-head review found no actionable issues. Independent browser QA passed on the same exact head. Regression tests cover StrictMode effect replay, unmount before the recorder start event, synchronous stop failure, and preserving final elapsed time after completion.

PR #113 merged into `master` as `2fc7398caaafa6fe938beff254c79ec7a0398d7a`. Issue #20 is closed as completed and labeled `status:done`. TASK-005.02 acceptance passed; the next development cursor is TASK-005.03 / Issue #21. Physical-device and long-duration recording checks remain in Phase 8.
