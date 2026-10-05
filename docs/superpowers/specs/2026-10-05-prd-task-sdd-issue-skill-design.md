# PRD Task SDD and Issue Skill

## Purpose

Create a project-local Codex skill that turns the user's request to document the next task into one detailed, implementation-ready SDD package and one matching GitHub Issue. The skill must preserve continuity across sessions by using the PRD, adjacent task documents, GitHub Issue state, and Issue comments as its sources of truth.

## User request that activates the workflow

The skill should be considered only when the request explicitly asks to create or update task planning documents and register/summarize the task in GitHub, for example:

> 다음 단계의 task 또는 작업의 문서를 작성하고 issue에 요약해서 등록해줘

The skill description should match natural variations that include both task-document intent (such as `문서`, `설계`, `계획`, `spec`, or `plan`) and GitHub Issue registration intent. A request such as `다음 task 개발해줘` is an implementation request and must not trigger this planning skill. Skill selection is automatic and description-driven; it is not a deterministic phrase hook.

## Scope

- Add a project-local skill at `.agents/skills/prd-task-planner/SKILL.md` so it can travel with this repository and be discovered as a repository skill.
- Plan exactly one next undocumented or incompletely tracked leaf TASK per request. Existing parent TASK IDs group leaf TASKs; only a leaf TASK has a folder and GitHub Issue.
- Create the task package under `docs/specs/<phase-slug>/<TASK-ID>/` with `spec.md`, `plan.md`, `tasks.md`, and `test.md`.
- Read the PRD, repository instructions, prior/adjacent task packages, GitHub Issue dependencies, labels, and relevant comments before choosing or designing work.
- Choose the earliest leaf TASK in PRD order that is not already DONE and lacks either its complete four-file SDD package or its matching GitHub Issue. Whether its code has been implemented is not the criterion for this planning workflow. If all non-DONE tasks already have complete documents and Issues, report that planning is caught up instead of selecting an already planned but still open development task.
- Cross-check the proposed task against both incoming dependencies and the next dependent task so interface or order mismatches are caught before publication.
- Create or update only the matching GitHub Issue, with a concise summary, PRD IDs, task-document path, dependencies, acceptance criteria, and agreed labels.
- Use Issue comments as the decision channel. When a decision blocks safe planning or implementation, record a focused question, mark the Issue blocked, and wait for the user's answer before proceeding.
- If an Issue already exists for the TASK ID, update or continue that Issue instead of creating a duplicate.

## Out of scope

- Implementing application code while producing task documents.
- Creating documents or Issues for future tasks in the same request.
- Selecting an already documented and tracked, but still open, TASK merely because implementation has not started. That is the development workflow, requested separately (for example, `다음 task 개발해줘`).
- Rewriting the PRD unless a contradiction or newly approved decision requires it; any required PRD change must be called out before it is applied.
- Editing the user's global plugin, hook, or Codex configuration. The skill is repository-local.
- Guessing details that are absent from the PRD or project. Material ambiguity must be surfaced through the Issue decision flow.

## Task document requirements

- `spec.md` defines the bounded behavior, exact scope, non-goals, relevant PRD IDs, assumptions, and observable acceptance criteria.
- `plan.md` names the files or components to create/change, FE/BE/API ownership, dependencies, operation order, and verification approach. It must explain why UI or API work comes first for this task.
- `tasks.md` lists small executable steps with explicit dependency and completion checks. When code is involved, steps use test-first red/green/refactor sequencing.
- `test.md` describes test cases, inputs, expected outcomes, relevant commands, and required evidence. Separate automated checks from manual QA and release-only checks.
- The documents must be specific enough that another Codex session can implement the task without inventing product behavior. Preserve unresolved items as explicit questions; do not silently convert them into assumptions.

## GitHub Issue requirements

- Title format: `[TASK-xxx.nn] concise action and outcome`.
- Include a short goal, document path, parent TASK/Phase, related PRD IDs, prerequisite Issues, acceptance criteria, and test/evidence summary.
- Apply the agreed `phase:*`, `area:*`, `type:*`, and `status:*` labels from the PRD.
- On authentication, network, repository, or permission failure, do not claim Issue creation succeeded. Preserve the local document package and report the exact pending action so the same request can resume without duplicating files.

## Completion criteria

- Exactly one next dependency-ready leaf TASK is selected, or a specific blocker/question is reported.
- Its four task documents agree with each other, the PRD, and neighboring task contracts.
- The corresponding GitHub Issue exists exactly once and references the task documents and verification conditions, or the remaining registration blocker is explicitly reported.
- No implementation begins as part of the planning request.

## Automatic discovery

Use normal automatic skill selection. Keep the skill description narrow and directly tied to creating the next PRD task's SDD documents and GitHub Issue. The project-local `.agents/skills/` layout is chosen to keep the workflow versioned with the repository; it must be validated for Codex discovery before claiming automatic activation is available.
