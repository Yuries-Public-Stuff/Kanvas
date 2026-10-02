# Versioning

Kanvas uses Semantic Versioning-style release numbers:

~~~text
MAJOR.MINOR.PATCH
~~~

The project is currently pre-1.0, so the compatibility policy is intentionally conservative rather than pretending the API is frozen.

## Release versions

A release version uses:

~~~text
0.1.0
0.2.0
0.2.1
~~~

The matching Git tag is:

~~~text
v0.1.0
v0.2.0
v0.2.1
~~~

The Gradle plugin version and runtime source tag should match.

## Snapshot versions

Development builds may use:

~~~text
0.1.0-SNAPSHOT
~~~

Snapshots are not releases and should not be used as evidence that a platform/version combination is verified.

## Before 1.0

Before `1.0.0`:

- minor versions may include breaking changes
- patch versions should be limited to compatible fixes where practical
- user-facing breaking changes must be called out in `CHANGELOG.md`
- Gradle DSL names should not be renamed casually
- compatibility aliases should only be removed intentionally and with release notes

## After 1.0

Once `1.0.0` is declared, normal semantic-versioning expectations should apply to the public Gradle DSL and documented runtime behavior.

## Compatibility claims

Version numbers and renderer verification are separate.

A release should document:

- Kotlin version(s) tested
- Compose Desktop version(s) tested
- Skiko version(s) tested
- operating systems tested
- CPU architectures tested
- GPU backends actually exercised

See [Compatibility](COMPATIBILITY.md) and [Release Checklist](RELEASE_CHECKLIST.md).
