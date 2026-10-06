# Release Checklist

Use this checklist before creating a public Kanvas release.

A source implementation being committed is not the same as a verified release.

## 1. Release scope

- [ ] Create or identify the release tracking issue
- [ ] Decide the release version
- [ ] Review unresolved release-blocking issues
- [ ] Update `CHANGELOG.md`
- [ ] Update compatibility and known-limitation docs if behavior changed

## 2. Clean source verification

Run a clean-clone verification instead of relying only on an existing developer checkout.

Linux / macOS:

~~~bash
./scripts/verify-clean-clone.sh
~~~

Windows:

~~~powershell
.\scripts\verify-clean-clone.ps1
~~~

- [ ] Clean clone succeeds
- [ ] Example projects configure
- [ ] Example projects build
- [ ] No build depends on untracked local files or absolute developer paths

## 3. Automated/local tests

- [ ] Gradle plugin tests pass
- [ ] Integration-agent tests pass
- [ ] Compose bridge tests pass
- [ ] Native C tests pass
- [ ] Renderer/display-list tests pass
- [ ] `kanvasDoctor` completes with zero `ERROR` results
- [ ] `kanvasCompatibility` records the expected Compose/Skiko versions

Full local project checks:

Linux / macOS:

~~~bash
./scripts/check-release.sh
~~~

Windows:

~~~powershell
.\scripts\check-release.ps1
~~~

## 4. Real hardware

Record what was actually tested.

### Windows

- [ ] application launches through Kanvas
- [ ] primary Windows GPU backend renders a representative Compose app
- [ ] resize/minimize/restore checked
- [ ] keyboard/pointer/scroll checked
- [ ] packaging checked

### macOS

- [ ] application launches through Metal
- [ ] representative Compose app renders
- [ ] resize/minimize/restore checked
- [ ] keyboard/pointer/scroll checked
- [ ] packaging checked
- [ ] Apple Silicon result recorded

### Linux

- [ ] application launches through Vulkan
- [ ] representative Compose app renders
- [ ] resize/minimize/restore checked
- [ ] keyboard/pointer/scroll checked
- [ ] packaging checked
- [ ] X11/XWayland environment recorded

Do not mark a platform verified if it was only compiled.

## 5. Dependency and license check

- [ ] Review [Dependencies & Licenses](DEPENDENCIES.md)
- [ ] Confirm bundled third-party notices are current
- [ ] Confirm fetched Vulkan-Headers version/license
- [ ] Confirm no new runtime dependency was added without license review
- [ ] Confirm Apache-2.0 `LICENSE` is present
- [ ] Confirm packaged runtimes contain `LICENSE` and `THIRD_PARTY_NOTICES.md`

## 6. Documentation

- [ ] README commands match current plugin behavior
- [ ] root-project plugin application is shown correctly
- [ ] examples use current versions and a supported system Gradle
- [ ] compatibility matrix matches tested versions
- [ ] backend status matches real verification
- [ ] known limitations include new release caveats
- [ ] support/security links work

## 7. Plugin validation

- [ ] plugin version is correct
- [ ] plugin website/VCS metadata points to the public Kanvas repository
- [ ] published runtime source resolves to the matching tag
- [ ] publication validation succeeds
- [ ] Configuration Cache and Isolated Projects compatibility declarations still match reality

When Plugin Portal publication is enabled:

~~~bash
gradle -p gradle-plugin -PkanvasVersion=X.Y.Z publishPlugins --validate-only
~~~

## 8. Release

- [ ] commit the final release metadata/docs
- [ ] create `vX.Y.Z`
- [ ] publish the intended artifacts
- [ ] verify the published plugin/artifacts from a fresh consumer project
- [ ] move the changelog contents from **Unreleased** into the released version section

## 9. Post-release

- [ ] create a new empty **Unreleased** changelog section
- [ ] record any release-specific known issue discovered immediately after publication
- [ ] keep hardware verification records separate from unsupported assumptions
