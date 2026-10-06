# Security Policy

## Reporting a security issue

Please do **not** open a public issue for a security vulnerability.

If the repository has GitHub private vulnerability reporting enabled, use the repository's **Security** tab to submit a private report.

If private vulnerability reporting is not available, open a minimal public issue asking for a private maintainer contact. Do not include exploit details, proof-of-concept code, secrets, or sensitive information in that issue.

Please include:

- a clear description of the issue
- affected operating systems or backends
- the Kanvas version or commit
- steps to reproduce
- expected impact
- any proof-of-concept material needed to confirm the issue

Do not include secrets, access tokens, personal data, or unrelated private information.

## Scope

Security reports are especially relevant when they involve:

- unsafe native memory handling
- JNI boundary issues
- arbitrary code execution
- unsafe file/process handling
- dependency or build-tool compromise
- packaging behavior that unexpectedly exposes or executes content

Normal rendering bugs, crashes, visual corruption, compatibility problems, and performance issues should use the regular bug report form instead.

## Disclosure

Please give maintainers a reasonable opportunity to reproduce and fix a security issue before publishing technical details.
