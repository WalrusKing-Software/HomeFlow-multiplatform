# RELEASE-PIPELINE.md — Packaging & Release Automation

> **Status:** Implemented. The release pipeline described here exists as the
> `.github/workflows/release-*.yml` workflows (server, desktop, Android, plus the
> `release-test` dry-run). This document remains the authoritative reference for
> how the pipeline is structured and how to change it; every file is given in full,
> with exact paths and contents.

This document defines a GitHub Actions **release pipeline** that builds and
publishes all three HomeFlow deliverables — the **server**, the **desktop app**,
and the **Android app** — as downloadable artifacts attached to a GitHub Release.

It complements, and does not replace:
- `__docs/BRANCHING.md` — versioning + the "Release artifacts (wire when nearing
  first release)" section this fulfils.
- `__docs/DOCKER.md` / `__docs/DEPLOYMENT.md` — how the server image/distribution
  is deployed on the Pi.
- `.github/workflows/ci.yml` — the per-PR quality gate (lint, detekt, tests). The
  release pipeline is **separate** and assumes CI already passed (see §9).

> **Folder note:** the user referred to `_docs/`; this repo's docs folder is
> `__docs/` (double underscore). This file lives at `__docs/RELEASE-PIPELINE.md`.

---

## 1. Goals & non-goals

**Goals**
1. One workflow produces, for a single version, all of:
   - **Server:** a runnable distribution archive (`.zip` + `.tar.gz`) **and** a
     multi-arch (`amd64` + `arm64`) Docker image pushed to GHCR.
   - **Desktop:** native installers — Windows `.msi`, macOS `.dmg`, Linux `.deb`.
   - **Android:** a **signed `.apk`** (for sideloading) plus a signed `.aab`
     (for completeness / future Play distribution).
2. **Runs only for releases.** Triggered by pushing a `v*` tag (the repo's
   existing release convention, `__docs/BRANCHING.md` §"Tags & versioning"), and
   **manually** via `workflow_dispatch`.
3. All artifacts attach to a single GitHub Release for that version, with release
   notes pulled from `CHANGELOG.md`.
4. Self-contained: the workflow **creates** the GitHub Release; you do not have to
   create it by hand first.

**Non-goals (explicitly out of scope for this pass)**
- macOS/Windows **code-signing & notarization** of the desktop installers. The
  installers are produced **unsigned** (acceptable for a single self-hosted user;
  see §8). Hooks for adding signing later are noted.
- Play Store / F-Droid publishing. We only *build* the `.aab`/`.apk`.
- Auto-deploying the server to the Pi. The pipeline *publishes* the image; the Pi
  pulls it per `DEPLOYMENT.md` (a follow-up doc tweak, §10).
- Re-running the full test suite (CI already gated the tagged commit; §9).

---

## 2. Deliverables (what a release contains)

| # | Deliverable | Artifact filename(s) | Built on | How produced |
|---|---|---|---|---|
| 1 | Server distribution | `homeflow-server-<version>.zip`, `homeflow-server-<version>.tar.gz` | `ubuntu-latest` | `:server:installDist` → archived |
| 2 | Server container image | `ghcr.io/<owner>/homeflow-multiplatform-server:<version>` (+ `:latest` for finals) | `ubuntu-latest` | thin runtime image over the prebuilt dist, `linux/amd64,linux/arm64` |
| 3 | Desktop — Windows | `HomeFlow-<version>.msi` | `windows-latest` | `:app:desktopApp:packageDistributionForCurrentOS` |
| 4 | Desktop — macOS | `HomeFlow-<version>.dmg` | `macos-latest` | same task |
| 5 | Desktop — Linux | `homeflow_<version>_amd64.deb` | `ubuntu-latest` | same task |
| 6 | Android APK | `homeflow-android-<version>.apk` | `ubuntu-latest` | `:app:androidApp:assembleRelease` |
| 7 | Android AAB | `homeflow-android-<version>.aab` | `ubuntu-latest` | `:app:androidApp:bundleRelease` |

`<version>` is the tag without the leading `v` (e.g. tag `v0.2.0` → `0.2.0`;
`v0.2.0-rc.1` → `0.2.0-rc.1`). Version resolution is defined in §6.

> **Why the server ships as both a dist archive *and* an image.** The archive is
> a JRE-runnable bundle anyone can download and run (`./bin/server`) with no
> Docker. The image is what the Pi `docker compose` deploy consumes. The image is
> **ARM64-capable** because the Pi is ARM64 (`DEPLOYMENT.md` Prerequisites).

---

## 3. Design rationale (read before implementing)

These choices are deliberate. Do **not** "simplify" them away without
understanding the consequence.

### 3.1 The server image is built from the *prebuilt* distribution — not via `server/Dockerfile`

The existing `server/Dockerfile` is a **self-contained** multi-stage build: it
installs the Android command-line SDK and runs Gradle *inside* the image build.
That is correct for the on-Pi `docker compose build` path (`DEPLOYMENT.md` Step
4), but it is the **wrong tool for multi-arch CI**:

- Building it for `linux/arm64` on an `amd64` runner means running **Gradle +
  Android SDK under QEMU emulation** — minutes-to-tens-of-minutes per arch, and a
  frequent OOM/timeout source.

The server is **pure JVM bytecode** (`:server` + `:core`, no native code). The
*only* architecture-specific layer is the **base JRE**, which Eclipse Temurin
publishes as a multi-arch image. So the release pipeline:

1. Runs `:server:installDist` **once** on the `amd64` runner → produces
   `server/build/install/server/` (arch-independent jars + launch scripts).
2. Builds a **thin** `server/Dockerfile.dist` that only `COPY`s that directory
   onto `eclipse-temurin:21-jre`. Buildx produces `amd64` + `arm64` variants in
   seconds — each just layers the *same* jars onto its native JRE base. No
   emulated Gradle, no Android SDK in the image build.

> **`.dockerignore` gotcha (must understand):** the repo `.dockerignore` excludes
> `**/build/`, which would hide `server/build/install/server` from a repo-root
> build context. The pipeline therefore sets the **Docker build context to the
> distribution directory itself** (`context: server/build/install/server`) and
> references the Dockerfile out-of-context via `file:`. `.dockerignore` is
> resolved at the *context root*, so the root `.dockerignore` does not apply and
> the build sees the dist. This is why `Dockerfile.dist` uses `COPY . ./`.

`server/Dockerfile` (the original) is **kept** for the on-Pi build path — this
pipeline adds `server/Dockerfile.dist` alongside it; it does not modify it.

### 3.2 Desktop versioning: jpackage requires major ≥ 1

`app/desktopApp/build.gradle.kts` hardcodes `packageVersion = "1.0.0"` with a
comment: jpackage rejects a `0.x` major for macOS `dmg`/`pkg`. Only macOS has that
constraint — Windows `msi` and Linux `deb` accept `0.x` fine. The pipeline uses
this to make in-place upgrades work on Windows/Linux even before 1.0:

- The build reads an optional `-PdesktopPackageVersion` (default `"1.0.0"`).
- `prepare` computes **two** installer versions and the desktop matrix job selects
  per format:
  - `desktop_version` (Windows `msi`, Linux `deb`) = the resolved `X.Y.Z`
    verbatim, including `0.x`.
  - `desktop_version_mac` (macOS `dmg`) = `X.Y.Z` **if MAJOR ≥ 1**, otherwise
    `"1.0.0"`.

**Why per-OS matters — MSI upgrades require a *higher* version.** Windows Installer
only replaces an existing install when the new `ProductVersion` is greater; equal
versions are a silent no-op (old files kept). If every pre-1.0 build reported
`1.0.0`, reinstalling would never update the app — a real trap that cost real
debugging time. Using the true `0.x` version on Windows/Linux makes
`0.1.0 < 0.1.1 < 0.2.0 < … < 1.0.0` upgrade cleanly, and it stays below the eventual
`1.0.0` with no transition cliff. (`upgradeUuid` is already stable in the build.)

**Remaining macOS limitation:** pre-1.0 macOS `dmg` installers still report `1.0.0`
internally (the jpackage/macOS constraint is unavoidable). Distinguish pre-1.0
macOS builds by the **artifact filename**, which always carries the real version
(`HomeFlow-0.1.20.dmg`). From `1.0.0` on, all platforms track the real version.

> The `release-test.yml` workflow is different on purpose: test builds reuse the
> same source version every run, so it sets the installer version to
> `1.0.<run_number>` (monotonic per CI run) so each test MSI supersedes the last.
> The real version is still in the artifact filename.

### 3.3 Desktop uses `packageDistributionForCurrentOS` (not the `...Release...` variant)

Compose Desktop's `packageReleaseDistributionForCurrentOS` applies ProGuard
minification, which can strip reflectively-used classes and produce a broken
installer without extra `proguard` rules. For reliability we use the
**non-minified** `packageDistributionForCurrentOS`. Optimizing to the release
variant is a *later* opt-in (would need a verified ProGuard config); noted in §11.

### 3.4 Android `versionCode` must be monotonic

`versionCode` is hardcoded `= 1`. Play and clean upgrades require a strictly
increasing integer. Make the build read `-PversionCode` (default `1`) and have the
workflow derive it deterministically from the version core:

```
versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH
```

e.g. `0.1.19` → `1_019`; `0.2.0` → `2_000`; `1.0.0` → `1_000_000`. Monotonic for
any normal version increment (minor/patch < 1000).

### 3.5 Android signing comes from CI secrets, written into the existing mechanism

`app/androidApp/build.gradle.kts` already wires release signing from an untracked
`keystore.properties` at the repo root, and produces an **unsigned** artifact when
that file is absent. The workflow **reconstructs** `keystore.properties` + the
`.jks` from GitHub secrets at build time (§5). No build-script change is needed
for signing — only the `versionCode` change (§3.4). If secrets are absent the
build still succeeds (unsigned APK), so forks/dry-runs don't hard-fail.

### 3.6 Multi-job fan-out, single publish

Each deliverable is a separate job (parallel, `fail-fast: false`) that uploads its
output as a **workflow artifact**. A final `publish` job downloads them all and
creates one GitHub Release. This avoids races on release creation and lets a
single failed platform be retried without rebuilding everything.

---

## 4. Trigger model

```yaml
on:
  push:
    tags: ['v*']          # the release convention from BRANCHING.md
  workflow_dispatch:       # manual run (with optional explicit version)
    inputs:
      version:
        description: 'Version X.Y.Z[-pre.N] (defaults to gradle.properties)'
        required: false
      prerelease:
        description: 'Mark the GitHub Release as a pre-release'
        type: boolean
        default: false
```

- **Tag push** is the normal path: after merging `release/x.y.z → main` you run
  `git tag vX.Y.Z && git push origin vX.Y.Z` (BRANCHING.md lifecycle) and the
  pipeline fires.
- **Manual** path: `workflow_dispatch` lets you re-run a release or cut one
  without a tag (it will create the `v<version>` tag when publishing). If
  `version` is omitted it falls back to the `version=` in `gradle.properties`.
- Tags with a pre-release suffix (`-alpha.N`, `-beta.N`, `-rc.N`) are published as
  **GitHub pre-releases** and do **not** move the image `:latest` tag.

This satisfies "only run on releases, optionally manual": there is no `push:
branches` or `pull_request` trigger, so day-to-day commits never invoke it.

---

## 5. Required GitHub secrets

Configure under **Settings → Secrets and variables → Actions**. Only the Android
ones are required; GHCR uses the built-in `GITHUB_TOKEN`.

| Secret | Purpose | How to produce |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | The release keystore, base64-encoded | `base64 -w0 homeflow-release.jks` (the `.jks` from `keystore.properties.example`'s `keytool` command) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore store password | from keystore creation |
| `ANDROID_KEY_ALIAS` | Signing key alias (e.g. `homeflow`) | from keystore creation |
| `ANDROID_KEY_PASSWORD` | Signing key password | from keystore creation |

> The `.jks` and these passwords must be **backed up safely** — losing them means
> you can never ship an upgrade signed by the same key (Android refuses to install
> over a differently-signed APK). This mirrors the warning in
> `keystore.properties.example`.

**GHCR:** no secret needed. The workflow logs in with `${{ github.actor }}` /
`${{ secrets.GITHUB_TOKEN }}` and `packages: write` permission. The first push
creates a private package under the repo owner; make it public later if desired.

**macOS/Windows signing:** intentionally omitted (§1 non-goals). Placeholders for
where they'd plug in are noted in §11.

---

## 6. Version resolution (the `prepare` job contract)

A single `prepare` job computes everything downstream needs and exposes it as job
outputs. Logic:

1. **Raw version string:**
   - tag push → `${GITHUB_REF_NAME}` with leading `v` stripped;
   - manual with `inputs.version` → that value (strip leading `v` if present);
   - manual without input → the `version=` value from `gradle.properties`.
2. **Validate** it matches `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$`; fail
   otherwise.
3. **Guard (tag builds only):** the tag's `X.Y.Z` core **must equal** the
   `gradle.properties` `version=`. This catches a tag that doesn't match the
   committed version (a common mistake). Pre-release suffixes are allowed to
   differ (e.g. tag `v0.2.0-rc.1` against committed `0.2.0`).
4. **Outputs:**
   - `version` — full string (`0.2.0` or `0.2.0-rc.1`); used in filenames + image tag.
   - `version_core` — `X.Y.Z` only; used for CHANGELOG lookup.
   - `desktop_version` — `version_core` if MAJOR ≥ 1, else `1.0.0` (§3.2).
   - `android_version_code` — `MAJOR*1000000 + MINOR*1000 + PATCH` (§3.4).
   - `is_prerelease` — `true` if the version has a pre-release suffix **or**
     `inputs.prerelease` is true.
   - `image` — lowercased `ghcr.io/${GITHUB_REPOSITORY}-server` (GHCR names must be
     lowercase; the repo name has capitals).

The exact bash is in the workflow file in §7.

---

## 7. The workflow file (create verbatim)

Create **`.github/workflows/release.yml`** with exactly this content:

```yaml
name: Release

# Builds and publishes all three HomeFlow deliverables (server, desktop, Android)
# for a single version and attaches them to a GitHub Release. Runs ONLY for
# releases: a pushed `v*` tag, or a manual dispatch. See __docs/RELEASE-PIPELINE.md.

on:
  push:
    tags: ['v*']
  workflow_dispatch:
    inputs:
      version:
        description: 'Version X.Y.Z[-pre.N] (defaults to gradle.properties)'
        required: false
      prerelease:
        description: 'Mark the GitHub Release as a pre-release'
        type: boolean
        default: false

permissions:
  contents: write   # create the Release and upload assets
  packages: write   # push the server image to GHCR

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false   # never cancel a release mid-flight

jobs:
  # ── Resolve version + derived values once, share via outputs ────────────────
  prepare:
    name: Resolve version
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.v.outputs.version }}
      version_core: ${{ steps.v.outputs.version_core }}
      desktop_version: ${{ steps.v.outputs.desktop_version }}
      android_version_code: ${{ steps.v.outputs.android_version_code }}
      is_prerelease: ${{ steps.v.outputs.is_prerelease }}
      image: ${{ steps.v.outputs.image }}
    steps:
      - uses: actions/checkout@v4
      - id: v
        shell: bash
        env:
          INPUT_VERSION: ${{ inputs.version }}
          INPUT_PRERELEASE: ${{ inputs.prerelease }}
        run: |
          set -euo pipefail

          # 1. Raw version string.
          if [[ "${GITHUB_REF_TYPE:-}" == "tag" ]]; then
            raw="${GITHUB_REF_NAME#v}"
          elif [[ -n "${INPUT_VERSION}" ]]; then
            raw="${INPUT_VERSION#v}"
          else
            raw="$(grep -E '^version=' gradle.properties | head -n1 | cut -d= -f2 | tr -d ' \t\r')"
          fi
          echo "Resolved raw version: $raw"

          # 2. Validate semver core (+ optional pre-release suffix).
          if [[ ! "$raw" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.-]+)?$ ]]; then
            echo "::error::'$raw' is not a valid X.Y.Z[-pre.N] version"; exit 1
          fi
          major="${BASH_REMATCH[1]}"
          minor="${BASH_REMATCH[2]}"
          patch="${BASH_REMATCH[3]}"
          pre="${BASH_REMATCH[4]}"
          core="${major}.${minor}.${patch}"

          # 3. On a tag build, the tag core must match gradle.properties.
          gp="$(grep -E '^version=' gradle.properties | head -n1 | cut -d= -f2 | tr -d ' \t\r')"
          if [[ "${GITHUB_REF_TYPE:-}" == "tag" && "$core" != "$gp" ]]; then
            echo "::error::tag core '$core' != gradle.properties version '$gp' — bump gradle.properties or fix the tag"; exit 1
          fi

          # 4. Desktop installer version: jpackage/macOS need major >= 1.
          if (( major < 1 )); then desktop="1.0.0"; else desktop="$core"; fi

          # 5. Android versionCode: monotonic integer from the core.
          code=$(( major * 1000000 + minor * 1000 + patch ))

          # 6. Pre-release?
          if [[ -n "$pre" || "${INPUT_PRERELEASE}" == "true" ]]; then ispre=true; else ispre=false; fi

          # 7. GHCR image name must be lowercase.
          image="ghcr.io/${GITHUB_REPOSITORY,,}-server"

          {
            echo "version=$raw"
            echo "version_core=$core"
            echo "desktop_version=$desktop"
            echo "android_version_code=$code"
            echo "is_prerelease=$ispre"
            echo "image=$image"
          } >> "$GITHUB_OUTPUT"

          echo "version=$raw core=$core desktop=$desktop code=$code prerelease=$ispre image=$image"

  # ── Server: distribution archives + multi-arch GHCR image ───────────────────
  server:
    name: Server (dist + image)
    needs: prepare
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Set up Android SDK
        # :server depends on :core (a KMP module with an Android target); Gradle must
        # configure the Android plugin even to build the JVM server. Same reason the
        # original server/Dockerfile installs the SDK.
        uses: android-actions/setup-android@v3
      - name: Install required SDK packages
        run: sdkmanager "platforms;android-36" "build-tools;36.0.0"
      - uses: gradle/actions/setup-gradle@v4

      - name: Build server distribution
        run: ./gradlew :server:installDist --no-daemon --no-configuration-cache

      - name: Package distribution archives
        shell: bash
        run: |
          set -euo pipefail
          v="${{ needs.prepare.outputs.version }}"
          mkdir -p dist-out
          # The dist dir is server/build/install/server.
          ( cd server/build/install && zip -qr "$GITHUB_WORKSPACE/dist-out/homeflow-server-${v}.zip" server )
          tar -czf "dist-out/homeflow-server-${v}.tar.gz" -C server/build/install server
          ls -l dist-out

      - uses: actions/upload-artifact@v4
        with:
          name: server-dist
          path: dist-out/*
          if-no-files-found: error

      # Multi-arch image built FROM the prebuilt dist (see RELEASE-PIPELINE.md §3.1).
      # Context is the dist directory so the repo .dockerignore (**/build/) does not hide it.
      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - name: Compute image tags
        id: tags
        shell: bash
        run: |
          set -euo pipefail
          img="${{ needs.prepare.outputs.image }}"
          v="${{ needs.prepare.outputs.version }}"
          tags="${img}:${v}"
          if [[ "${{ needs.prepare.outputs.is_prerelease }}" == "false" ]]; then
            tags="${tags}"$'\n'"${img}:latest"
          fi
          { echo "tags<<EOF"; echo "$tags"; echo "EOF"; } >> "$GITHUB_OUTPUT"
      - name: Build & push server image
        uses: docker/build-push-action@v6
        with:
          context: server/build/install/server
          file: server/Dockerfile.dist
          platforms: linux/amd64,linux/arm64
          push: true
          tags: ${{ steps.tags.outputs.tags }}
          provenance: false

  # ── Desktop installers, one per OS ──────────────────────────────────────────
  desktop:
    name: Desktop (${{ matrix.fmt }})
    needs: prepare
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: windows-latest
            fmt: msi
          - os: macos-latest
            fmt: dmg
          - os: ubuntu-latest
            fmt: deb
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Set up Android SDK
        # :app:desktopApp -> :app:shared (KMP with an Android target) -> :core.
        # Gradle configures all targets, so the Android SDK must be present even to
        # package the desktop (jvm) app.
        uses: android-actions/setup-android@v3
      - name: Install required SDK packages
        shell: bash
        run: sdkmanager "platforms;android-36" "build-tools;36.0.0"
      - uses: gradle/actions/setup-gradle@v4

      - name: Package desktop distribution
        shell: bash
        run: >
          ./gradlew :app:desktopApp:packageDistributionForCurrentOS
          -PdesktopPackageVersion=${{ needs.prepare.outputs.desktop_version }}
          --no-daemon --no-configuration-cache

      - name: Collect installer
        shell: bash
        run: |
          set -euo pipefail
          v="${{ needs.prepare.outputs.version }}"
          fmt="${{ matrix.fmt }}"
          src="$(ls app/desktopApp/build/compose/binaries/main/${fmt}/*.${fmt} | head -n1)"
          mkdir -p out
          case "$fmt" in
            dmg) dst="out/HomeFlow-${v}.dmg" ;;
            msi) dst="out/HomeFlow-${v}.msi" ;;
            deb) dst="out/homeflow_${v}_amd64.deb" ;;
          esac
          cp "$src" "$dst"
          echo "Collected $dst (from $src)"

      - uses: actions/upload-artifact@v4
        with:
          name: desktop-${{ matrix.fmt }}
          path: out/*
          if-no-files-found: error

  # ── Android signed APK + AAB ────────────────────────────────────────────────
  android:
    name: Android (apk + aab)
    needs: prepare
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: android-actions/setup-android@v3
      - name: Install required SDK packages
        run: sdkmanager "platforms;android-36" "build-tools;36.0.0"
      - uses: gradle/actions/setup-gradle@v4

      - name: Write signing keystore (if secrets present)
        id: signing
        shell: bash
        env:
          KS_B64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
          KS_PW: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          KS_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          KS_KEY_PW: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          set -euo pipefail
          if [[ -z "${KS_B64}" ]]; then
            echo "No keystore secret set — building an UNSIGNED release APK."
            echo "enabled=false" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          echo "${KS_B64}" | base64 -d > homeflow-release.jks
          cat > keystore.properties <<EOF
          storeFile=homeflow-release.jks
          storePassword=${KS_PW}
          keyAlias=${KS_ALIAS}
          keyPassword=${KS_KEY_PW}
          EOF
          echo "enabled=true" >> "$GITHUB_OUTPUT"

      - name: Assemble release APK + AAB
        shell: bash
        run: >
          ./gradlew :app:androidApp:assembleRelease :app:androidApp:bundleRelease
          -PversionCode=${{ needs.prepare.outputs.android_version_code }}
          --no-daemon --no-configuration-cache

      - name: Collect artifacts
        shell: bash
        run: |
          set -euo pipefail
          v="${{ needs.prepare.outputs.version }}"
          mkdir -p out
          apk="$(ls app/androidApp/build/outputs/apk/release/*.apk | head -n1)"
          aab="$(ls app/androidApp/build/outputs/bundle/release/*.aab | head -n1)"
          cp "$apk" "out/homeflow-android-${v}.apk"
          cp "$aab" "out/homeflow-android-${v}.aab"
          echo "Collected APK=$apk AAB=$aab (signed=${{ steps.signing.outputs.enabled }})"

      - name: Clean up signing material
        if: always()
        shell: bash
        run: rm -f homeflow-release.jks keystore.properties

      - uses: actions/upload-artifact@v4
        with:
          name: android
          path: out/*
          if-no-files-found: error

  # ── Gather everything and publish one GitHub Release ────────────────────────
  publish:
    name: Publish GitHub Release
    needs: [prepare, server, desktop, android]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4   # needed for CHANGELOG.md
      - uses: actions/download-artifact@v4
        with:
          path: assets
          merge-multiple: true
      - name: Extract release notes from CHANGELOG
        shell: bash
        run: |
          set -euo pipefail
          v="${{ needs.prepare.outputs.version_core }}"
          # Pull the body of the "## [X.Y.Z]" section (up to the next "## [" heading).
          awk -v ver="$v" '
            $0 ~ ("^## \\[" ver "\\]") {flag=1; next}
            flag && /^## \[/ {flag=0}
            flag {print}
          ' CHANGELOG.md > release-notes.md || true
          if [[ ! -s release-notes.md ]]; then
            echo "Release ${{ needs.prepare.outputs.version }}." > release-notes.md
          fi
          echo "----- release notes -----"; cat release-notes.md
      - name: List collected assets
        shell: bash
        run: ls -lR assets
      - name: Publish release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ github.ref_type == 'tag' && github.ref_name || format('v{0}', needs.prepare.outputs.version) }}
          name: HomeFlow ${{ needs.prepare.outputs.version }}
          body_path: release-notes.md
          prerelease: ${{ needs.prepare.outputs.is_prerelease == 'true' }}
          make_latest: ${{ needs.prepare.outputs.is_prerelease == 'false' }}
          files: assets/**
          fail_on_unmatched_files: true
```

---

## 8. Supporting files & build-script edits (create/edit exactly)

### 8.1 New file — `server/Dockerfile.dist`

```dockerfile
# syntax=docker/dockerfile:1
# Thin RUNTIME-ONLY image built FROM a prebuilt :server installDist distribution.
#
# The distribution is plain JVM bytecode + launch scripts (architecture-independent),
# so this image is trivially multi-arch: buildx layers the SAME /app onto each
# platform's native Temurin JRE base. No Gradle, no Android SDK in the image build.
#
# Build context MUST be the distribution directory (server/build/install/server),
# because the repo .dockerignore excludes **/build/. The release workflow sets
# `context: server/build/install/server` and references this file via `file:`.
# See __docs/RELEASE-PIPELINE.md §3.1.
#
# Produce the distribution first:  ./gradlew :server:installDist
FROM eclipse-temurin:21-jre AS production
WORKDIR /app
COPY . ./
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser
EXPOSE 8080
# Migrations are NOT run from this image (CLAUDE.md / DEPLOYMENT.md §5).
CMD ["./bin/server"]
```

### 8.2 Edit — `app/androidApp/build.gradle.kts` (make `versionCode` overridable)

Change the hardcoded line inside `defaultConfig`:

```kotlin
        // versionName tracks the project version (gradle.properties); bump
        // versionCode manually on every distributed build (see __docs/BRANCHING.md).
        versionCode = 1
```

to:

```kotlin
        // versionName tracks the project version (gradle.properties). versionCode is
        // a monotonic integer supplied by the release pipeline via -PversionCode
        // (derived from the version core); defaults to 1 for local/dev builds.
        // See __docs/RELEASE-PIPELINE.md §3.4.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
```

No other change to this file — the existing `keystore.properties` signing block is
reused as-is; the workflow writes that file at build time.

### 8.3 Edit — `app/desktopApp/build.gradle.kts` (make installer version overridable)

Change:

```kotlin
            packageVersion = "1.0.0"
```

to:

```kotlin
            // Installer version. jpackage requires major >= 1 (macOS dmg/pkg), so the
            // release pipeline passes -PdesktopPackageVersion = the release X.Y.Z when
            // major >= 1, else "1.0.0". Defaults to "1.0.0" for local packaging.
            // See __docs/RELEASE-PIPELINE.md §3.2.
            packageVersion = (project.findProperty("desktopPackageVersion") as String?) ?: "1.0.0"
```

> Keep the existing surrounding comment about decoupling from the project version;
> this just makes the value injectable.

### 8.4 No change to `server/Dockerfile`, `.dockerignore`, `gradle.properties`, CI

- `server/Dockerfile` stays (on-Pi build path).
- `.dockerignore` stays (the image build sidesteps it via context, §3.1).
- `gradle.properties` `version=` stays the source of truth; the tag must match it.
- `.github/workflows/ci.yml` is untouched.

---

## 9. Relationship to CI / quality gates

The release pipeline **does not re-run** ktlint/detekt/tests. Rationale:

- `__docs/BRANCHING.md` requires every change to reach `main` through a PR with the
  `build-checks` status check green; `main` is the only branch you tag from for a
  final release. The tagged commit is therefore already verified.
- Re-running the Testcontainers suite here would add minutes and a Docker
  dependency to every release for no new signal.

If you want a belt-and-suspenders gate, add a first job `verify` running
`./gradlew check` and make `server`/`desktop`/`android` `needs: [prepare, verify]`.
This is **optional** and not included by default. (Decide once; don't toggle per
release.)

---

## 10. Follow-up doc updates (do these in the same PR)

1. **`CHANGELOG.md`** — add an entry under the current Unreleased/version section:
   > **Added — Release pipeline.** Tagging `vX.Y.Z` (or a manual run) now builds
   > and publishes the server distribution + multi-arch container image, desktop
   > installers (Windows `.msi`, macOS `.dmg`, Linux `.deb`), and a signed Android
   > APK/AAB to a GitHub Release.
2. **`__docs/BRANCHING.md`** — replace the speculative "Release artifacts (wire
   when nearing first release)" paragraph with a one-line pointer to this doc.
3. **`__docs/DEPLOYMENT.md`** *(optional, recommended)* — add an "Install from a
   published image" note: instead of `docker compose build`, the Pi can
   `docker pull ghcr.io/<owner>/homeflow-multiplatform-server:<version>` and a
   compose override can set `image:` instead of `build:`. (Leave the build path as
   the documented default; this is an alternative.)
4. **`CLAUDE.md`** — add a row to the Documentation Index table:
   `| __docs/RELEASE-PIPELINE.md | Release packaging & GitHub Actions pipeline | Any release/packaging/CI-artifact work |`

---

## 11. Deferred / future enhancements (not in this pass)

- **Desktop code-signing & notarization.** macOS: import a Developer ID cert
  (`MACOS_CERTIFICATE` + password secrets), sign the `.app`/`.dmg`, and notarize
  via `notarytool` (needs an App Store Connect API key). Windows: Authenticode
  sign the `.msi` (`WINDOWS_CERTIFICATE`). Plug into the `desktop` job after the
  packaging step. Until then installers are unsigned — on macOS, open via
  right-click → Open, or `xattr -d com.apple.quarantine HomeFlow-<v>.dmg`.
- **`packageReleaseDistributionForCurrentOS`** (ProGuard-minified) once a verified
  keep-rules config exists (§3.3) — smaller installers.
- **Native ARM64 runners** (`ubuntu-24.04-arm`) for the image build if/when the
  thin-image approach is ever replaced — not needed today (§3.1).
- **`verify` gate** before packaging (§9).
- **Checksums** (`sha256sum`) and/or Sigstore signing of every asset for download
  integrity.
- **Split release trains** if desktop/android ever ship on independent cadences
  (`__docs/BRANCHING.md` "Open decision") — parameterize this workflow per
  component then.

---

## 12. Implementation checklist (ordered, for the implementing agent)

1. [ ] Create `server/Dockerfile.dist` (§8.1).
2. [ ] Edit `app/androidApp/build.gradle.kts` — overridable `versionCode` (§8.2).
3. [ ] Edit `app/desktopApp/build.gradle.kts` — overridable `packageVersion` (§8.3).
4. [ ] Create `.github/workflows/release.yml` verbatim (§7).
5. [ ] Local sanity (no secrets needed):
   - `./gradlew :server:installDist --no-configuration-cache` → confirm
     `server/build/install/server/bin/server` exists.
   - `./gradlew :app:desktopApp:packageDistributionForCurrentOS -PdesktopPackageVersion=1.0.0 --no-configuration-cache`
     → confirm an installer appears under
     `app/desktopApp/build/compose/binaries/main/<fmt>/`.
   - `./gradlew :app:androidApp:assembleRelease -PversionCode=1019 --no-configuration-cache`
     → confirm an APK under `app/androidApp/build/outputs/apk/release/` (unsigned
     is fine locally).
   - `docker buildx build --platform linux/amd64,linux/arm64 -f server/Dockerfile.dist server/build/install/server`
     (no `--push`) → confirm the thin image builds for both arches.
6. [ ] Add the four GitHub secrets (§5).
7. [ ] Do the follow-up doc updates (§10).
8. [ ] **Dry run** before a real tag: trigger `workflow_dispatch` (optionally with
   an explicit `version` and `prerelease: true`). Confirm a *pre-release* GitHub
   Release appears with all 7 asset types and the GHCR package is pushed. Delete
   the test release/tag/package afterwards.
9. [ ] First real release: merge `release/x.y.z → main`, then
   `git tag vX.Y.Z && git push origin vX.Y.Z` and watch the run.

### Done-when
- A pushed `vX.Y.Z` tag produces a GitHub Release titled `HomeFlow X.Y.Z`
  carrying: `homeflow-server-X.Y.Z.zip` + `.tar.gz`, `HomeFlow-X.Y.Z.msi`,
  `HomeFlow-X.Y.Z.dmg`, `homeflow_X.Y.Z_amd64.deb`, `homeflow-android-X.Y.Z.apk`,
  `homeflow-android-X.Y.Z.aab`.
- `ghcr.io/<owner>/homeflow-multiplatform-server:X.Y.Z` (and `:latest` for a final
  release) is pullable and runs on both `amd64` and `arm64`.
- A pre-release tag (`-rc.N`) yields a GitHub *pre-release* and does **not** move
  `:latest`.
- The Android APK is signed when the keystore secrets are present.

---

## 13. Per-component releases

From v0.2.0 onward, each deliverable can be released independently using a
component-scoped tag. This avoids rebuilding clients when only the server changes,
and vice versa.

### Tag formats

| Tag | Workflow | GitHub Release | Assets |
|---|---|---|---|
| `vX.Y.Z` | `release.yml` | HomeFlow X.Y.Z | All 7 |
| `server-vX.Y.Z` | `release-server.yml` | HomeFlow Server X.Y.Z | dist .zip + .tar.gz |
| `desktop-vX.Y.Z` | `release-desktop.yml` | HomeFlow Desktop X.Y.Z | .msi + .dmg + .deb |
| `android-vX.Y.Z` | `release-android.yml` | HomeFlow Android X.Y.Z | .apk + .aab |

### Version sources

Each workflow reads its component's key from `gradle.properties`:
- `release-server.yml` → `version.server`
- `release-desktop.yml` → `version.desktop`
- `release-android.yml` → `version.android`
- `release.yml` (lockstep) → all three must match; uses `version.server` as the
  canonical fallback for `workflow_dispatch` without an explicit version input.

### `make_latest: false`

Component-only releases set `make_latest: false` on the GitHub Release. Only full
lockstep `v*` releases move the repo-level "Latest release" pointer. This keeps the
GitHub Releases page meaningful — the latest *combined* release is always prominent.

### Compatibility

When releasing a server update that changes the API in a backward-incompatible way,
or that requires a newer client:

1. Update `COMPATIBILITY.md` with the new row.
2. Set `MIN_CLIENT_VERSION=X.Y.Z` in your homelab `.env` before deploying.
3. The server's `GET /api/v1/version` endpoint (Phase 2) returns this value so
   clients can enforce the check automatically.

### Bumping versions before tagging

```bash
# Server hotfix only:
sh scripts/bump-version.sh server
git add gradle.properties && git commit -m "chore: bump server to 0.2.1"
git tag server-v0.2.1 && git push origin server-v0.2.1

# Full lockstep release:
sh scripts/bump-version.sh all
git add gradle.properties && git commit -m "chore: bump all to 0.3.0"
git tag v0.3.0 && git push origin v0.3.0
```

---

## 14. Ad-hoc test-APK builds (not a release)

`.github/workflows/build-test-apk.yml` exists for a different need than the
release workflows above: quickly getting a branch onto a **test phone** without
cutting a release.

| | Release workflows (§7, §13) | `build-test-apk.yml` |
|---|---|---|
| Trigger | `v*` tags / `workflow_dispatch` | `workflow_dispatch` only, with a `ref` input (branch/tag/SHA) |
| Build type | signed **release** APK + AAB | debug-signed **APK** only |
| Signing | needs `ANDROID_KEYSTORE_*` secrets | none — Android debug keystore, always installable |
| Output | assets on a GitHub Release | a `test-apk-*` **workflow artifact** (14-day retention) |
| Version | semantic from `gradle.properties` | `versionCode = github.run_number` (monotonic); real version in the filename |

**Usage:** Actions → **Build Test APK** → **Run workflow** → enter the branch/tag/
SHA → download the `test-apk-*` artifact from the finished run, unzip, and install
the `.apk` on the phone (`adb install -r` or open it on-device). The run summary
prints the install notes.

Because it is debug-signed, a test APK cannot upgrade over an app installed from a
**release** APK (signature mismatch) — uninstall the release build on the test
phone first if an install is blocked. It never publishes a Release or a GHCR
image, and it needs no secrets, so it works from forks and any branch — including
branches created before this workflow was merged, since the workflow runs from the
default branch and checks out the requested `ref`.
