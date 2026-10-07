# Common API errors and health endpoint

## Completion record

- Task: `TASK-001.04`
- Issue: [#4](https://github.com/donghyunlee-dev/meeting-automation/issues/4), closed as completed
- Pull request: [#82](https://github.com/donghyunlee-dev/meeting-automation/pull/82), merged
- Branch: `codex/issue-4-common-error-health`
- Final tested/reviewed/QA head: `b8af6ab66c56f2c238cab252d8633c17da54b0ea`
- Merge commit: `fb0a9d3306581c6d28747834f9dc01ba114c7306`
- Completion time: `2026-10-07 20:52 KST` (GitHub merge time)

## Contract implementation

- All API error responses use `{error:{code,message,category,retryable,traceId,details}}`.
- Bean validation, request parameter validation, and type conversion failures return safe field names and constraint codes under `details.fieldErrors`; rejected values are not copied into the response.
- A supplied `X-Request-Id` is reflected as `traceId`; otherwise the server creates a nonempty `tr_...` ID.
- Spring MVC 400, 405, and 415 client/protocol statuses are retained in the common envelope.
- Unexpected exceptions and invalid controller return-value validation return HTTP 500 `INTERNAL_ERROR`, without exception text, class, stack trace, or secret marker.
- `/actuator/health` returns HTTP 200 / `UP` without component details. Actuator discovery and non-health endpoints are not exposed.

## Test-first and build evidence

The first validation fixture used a nonblank marker with `@NotBlank`, so it was valid. The fixture was corrected to violate `@Size` before claiming validation red evidence. Review-driven corrections were each checked with failing regression tests before implementation.

| Command / check | Result |
|---|---|
| Contract-first `gradlew.bat test` before error handler | PASS as red evidence: valid validation fixture correction then confirmed missing envelope, exception handling, and Actuator discovery failures |
| Focused MVC client/protocol regressions before status mapping | PASS as red evidence: missing header, type mismatch, unsupported media type, and unsupported method were incorrectly mapped to 500 |
| Focused return-value validation regression before classification | PASS as red evidence: invalid controller response was incorrectly mapped to 400 instead of 500 |
| Focused validation detail regressions before adding `fieldErrors` | PASS as red evidence: parameter and service validation responses omitted field details |
| `gradlew.bat test` on final head | PASS; 10 tests, zero failures/errors |
| `gradlew.bat clean build` on final head | PASS; 8 tasks, `BUILD SUCCESSFUL` |
| Gradle wrapper-validation GitHub Actions workflow | PASS on final head |

Gradle commands ran from `backend/` with canonical Windows `TEMP` and `TMP` set to `C:\Users\donghyunlee\AppData\Local\Temp`, through the approved CMD execution path.

## Review evidence

- Current-head review: PASS on `b8af6ab66c56f2c238cab252d8633c17da54b0ea`; no remaining findings. All review threads were resolved.
- Review iterations found and fixed: preserving MVC client statuses, distinguishing invalid controller return validation from invalid request input, and returning safe field/constraint metadata for validation and type conversion.

## QA evidence

- Local service QA on exact final head: PASS.
- `GET /actuator/health`: HTTP 200, `UP`, no component/details fields.
- `GET /actuator`, `/actuator/info`, `/actuator/metrics`, and `POST /actuator/shutdown`: HTTP 404 with safe common errors; health remained available after the shutdown probe.
- An unmapped route returned a safe 404 common error envelope with a trace ID.
- Automated tests cover validation 400, type/protocol statuses, safe details, rejected-value redaction, unexpected/return-validation 500 responses, and hidden actuator endpoints. Test-only probes are not present in the runtime app.
- Port 8080 was listening during QA and no listener remained after stopping `bootRun`.
- Process identity inspection was unavailable due local access restrictions; the service was started and stopped through the Gradle terminal session.
- Browser QA: N/A because this task contains no UI.

Task completion recorded in PRD. Next development cursor: `TASK-001.05`.
