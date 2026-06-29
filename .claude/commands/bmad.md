# BMAD Workflow Coordinator

This command walks you through the BMAD spec-driven development pipeline for a Marquez feature.

**Usage:** `/bmad $FEATURE_NAME`

If $ARGUMENTS is empty, ask: "What feature are you building? Provide a kebab-case name (e.g., `column-lineage-v3`)."

---

## What this command does

It inspects the current state of `specs/$FEATURE/` and tells you exactly which phase is next, which agent to invoke, and what artifact is missing. It does NOT skip phases.

---

## Phase Detection

Run through these checks IN ORDER and stop at the first incomplete phase:

**Phase 1 — PRD**
- Check: does `specs/$FEATURE/prd.md` exist with `Status: In Review` or `Approved`?
- If NO → print: "Phase 1 incomplete. Run `/pm-agent $FEATURE` to write the PRD."
- If yes → continue.

**Phase 2a — Competitive Analysis**
- Check: does `specs/$FEATURE/prd.md` contain "Appendix A"?
- If NO → print: "Phase 2a incomplete. Run `/comparative-analyst-agent $FEATURE` to add competitive analysis to the PRD."
- If yes → continue.

**Phase 2b — UX Design**
- Check: does `specs/$FEATURE/ux.md` exist?
- If NO → print: "Phase 2b incomplete. Run `/ux-designer-agent $FEATURE` to produce the UX design."
- If yes → continue.

**Phase 3 — Architecture (ADR + Spec)**
- Check: does `specs/$FEATURE/adr.md` exist AND `specs/$FEATURE/spec.md` exist?
- If NO → print: "Phase 3 incomplete. Run `/architect-agent $FEATURE` to write the ADR and technical spec."
- If yes → continue.

**Phase 4 — Stories**
- Check: does `specs/$FEATURE/stories.md` exist?
- If NO → print: "Phase 4 incomplete. Run `/sm-agent $FEATURE` to decompose the spec into developer stories."
- If yes → continue.

**Phase 5 — Test Plan**
- Check: does `specs/$FEATURE/test-plan.md` exist?
- If NO → print: "Phase 5 incomplete. Run `/qa-agent $FEATURE` to write the test plan."
- If yes → continue.

**Phase 6 — Implementation**
- Check: are there stories in `specs/$FEATURE/stories.md` with `Status: TODO`?
- If YES → list the TODO stories and print:
  "Phase 6 in progress. Next story to implement: [Story N title].
   Use the appropriate dev agent:
   - DB migration story → `/dev-database-agent $FEATURE story N`
   - Backend story      → `/dev-backend-agent $FEATURE story N`
   - Frontend story     → `/dev-frontend-agent $FEATURE story N`"
- If all stories are DONE → continue.

**All phases complete**
- Print: "✅ All BMAD phases complete for $FEATURE. Ready for PR review."
- Remind user to update `specs/README.md` to set Status: Complete and move to archive after merge.

---

## Rules

- NEVER skip a phase or produce a phase's artifact if the preceding phase is incomplete.
- NEVER write implementation code from this coordinator command — delegate to the appropriate dev agent.
- This command is read-only: it inspects state and directs, it does not create files.
