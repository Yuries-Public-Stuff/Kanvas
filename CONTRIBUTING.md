# Contributing to Kanvas

Thanks for helping with Kanvas.

Before changing code, read [AI.md](AI.md) for repository rules and architecture notes.

Versioning rules are in [docs/VERSIONING.md](docs/VERSIONING.md). Release verification is in [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).

## Good contributions

Useful contributions include:

- renderer compatibility fixes
- platform build fixes
- Gradle integration improvements
- documentation improvements
- reproducible rendering bug reports
- tests for existing or newly fixed behavior

## Contribution requirements

Every contribution needs:

1. a related issue describing the bug, change, or feature
2. a merge request linked to that issue

Merge requests without a related issue may be closed until the issue exists.

## Before submitting a merge request

Run the tests relevant to your change. The repository does not currently ship a Gradle wrapper, so contributor commands use a system `gradle` installation.

Gradle plugin:

~~~bash
gradle -p gradle-plugin test
~~~

Java agent:

~~~bash
gradle -p integration-agent test
~~~

Full local check on Linux/macOS:

~~~bash
./scripts/check-release.sh
~~~

Windows:

~~~powershell
.\scripts\check-release.ps1
~~~

If you could not run a platform-specific test, say that in the merge request instead of marking it as verified.

## Merge requests

Keep merge requests focused.

Describe:

- what changed
- why it changed
- how it was tested
- which operating systems/backends are affected
- any remaining limitations

For rendering changes, screenshots or logs are useful when they demonstrate the behavior.

## Issues

Use the issue forms under `.github/ISSUE_TEMPLATE/`.

Renderer bugs should include `build/kanvas/renderer-audit.log` when available.
