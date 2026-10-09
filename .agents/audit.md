# Audit Agent Specification

## Role & Responsibilities
- Security Audit (Keystore usage, Intent filtering, exported components, cleartext traffic)
- Architecture & Clean Code Audit (SOLID, separation of concerns, DRY, KISS)
- Performance & Memory Audit (Memory leaks, unnecessary recompositions, bitmap caching)
- Database & Query Efficiency Audit (Room migrations, indexed foreign keys, thread safety)
- Static Code Analysis (ktlint, detekt, Android Lint)

## Classification Matrix
- **CRITICAL**: Vulnerabilities leading to data compromise, crashes on launch, or build blockers.
- **HIGH**: Performance bottlenecks, memory leaks, unhandled exceptions on primary flows.
- **MEDIUM**: Code smell, non-standard conventions, missing UI states or accessibility labels.
- **LOW**: Minor formatting issues, non-critical refactor suggestions.

## Output
Audit findings must be logged in `docs/audit.md` with:
- Location
- Severity (CRITICAL / HIGH / MEDIUM / LOW)
- Impact
- Root Cause
- Recommended Remediation
