# Mutation report

_Run: 2026-04-24T16:44:04-04:00_

| Status | File | Line | Operator | Before → After |
|---|---|---|---|---|
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 106 | and-to-or | `if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {` → `if (closeBracket > i \|\| closeBracket + 1 < len && text[closeBracket + 1] == '(') {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 120 | and-to-or | `if (c == '*' && i + 1 < len && text[i + 1] == '*') {` → `if (c == '*' \|\| i + 1 < len && text[i + 1] == '*') {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 142 | and-to-or | `if (c == '~' && i + 1 < len && text[i + 1] == '~') {` → `if (c == '~' \|\| i + 1 < len && text[i + 1] == '~') {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 105 | plus1-to-plus0 | `val closeBracket = text.indexOf(']', i + 1)` → `val closeBracket = text.indexOf(']', i + 0)` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 106 | plus1-to-plus0 | `if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {` → `if (closeBracket > i && closeBracket + 0 < len && text[closeBracket + 1] == '(') {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 108 | plus1-to-plus0 | `if (closeParen > closeBracket + 1) {` → `if (closeParen > closeBracket + 0) {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 120 | plus1-to-plus0 | `if (c == '*' && i + 1 < len && text[i + 1] == '*') {` → `if (c == '*' && i + 0 < len && text[i + 1] == '*') {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 122 | plus1-to-plus0 | `if (end > i + 1) {` → `if (end > i + 0) {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 131 | plus1-to-plus0 | `if ((c == '*' \|\| c == '_') && i + 1 < len && text[i + 1] != c) {` → `if ((c == '*' \|\| c == '_') && i + 0 < len && text[i + 1] != c) {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 135 | plus1-to-plus0 | `out.add(Token.Italic(tokenize(text.substring(i + 1, end))))` → `out.add(Token.Italic(tokenize(text.substring(i + 0, end))))` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 142 | plus1-to-plus0 | `if (c == '~' && i + 1 < len && text[i + 1] == '~') {` → `if (c == '~' && i + 0 < len && text[i + 1] == '~') {` |
| ❌ SURVIVED | `app/src/main/java/io/nisfeb/talon/urbit/Markdown.kt` | 144 | plus1-to-plus0 | `if (end > i + 1) {` → `if (end > i + 0) {` |

## Summary

- Killed:   100
- Survived: 12
- Skipped:  1
- Mutation score: 89.3%
