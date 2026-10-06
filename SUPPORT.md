# Support

## Bugs

Use the GitHub **Bug report** issue form.

For renderer problems, include the affected repository if possible. If the repository cannot be shared, provide a minimal test project or another reproducible way to exercise the same functionality.

Useful reports include:

- operating system and architecture
- GPU
- Kanvas backend
- JDK, Kotlin, Compose Desktop, and Skiko versions
- Kanvas version or commit
- the exact command that failed
- the complete error
- `build/kanvas/renderer-audit.log` when available

## Feature requests

Use the GitHub **Feature request** form and explain the use case first.

Features should solve a general Kanvas problem rather than only one application's private architecture.

## Usage questions

Use the GitHub **Question / support** issue form.

Before opening a question:

1. run `kanvasDoctor` and fix any `ERROR` results
2. run `kanvasCompatibility` to capture the detected Compose/Skiko versions
3. read [Getting Started](docs/GETTING_STARTED.md)
4. read [Troubleshooting](docs/TROUBLESHOOTING.md)
5. check [Known Limitations](docs/KNOWN_LIMITATIONS.md)

## Security issues

Do not open a normal public issue for a vulnerability.

Follow [SECURITY.md](SECURITY.md). Do not post vulnerability details in a normal issue.

## Contributions

Code contributions require:

- a related issue
- a merge request linked to that issue

See [CONTRIBUTING.md](CONTRIBUTING.md).
