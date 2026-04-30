# Mutation report

_Run: 2026-04-29T23:46:09-04:00_

| Status | File | Line | Operator | Before → After |
|---|---|---|---|---|
| ❌ SURVIVED | `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/CalParse.kt` | 143 | and-to-or | `if (ap1 == null && ap2 == null && h1 > h2) h2 += 12` → `if (ap1 == null \|\| ap2 == null && h1 > h2) h2 += 12` |
| ❌ SURVIVED | `composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/CalParse.kt` | 199 | drop-not | `if (!usedDefaultDate && start.time.before(now)) {` → `if (usedDefaultDate && start.time.before(now)) {` |

## Summary

- Killed:   41
- Survived: 2
- Skipped:  0
- Mutation score: 95.3%
