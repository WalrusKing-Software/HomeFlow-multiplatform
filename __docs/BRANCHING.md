# BRANCHING.md — Branching, Releases & CI Gates

Defines the git model and the checks that run before a merge. Adapted from the web
monorepo's two-train model. **This repo is one product with three deliverables**
(`:server`, `:app:desktopApp`, `:app:androidApp`) that share a single
contract (`:core`) — so it starts as a **single release train**, not the web
repo's split web/mobile trains.

> **Open decision (single vs. split trains).** Because the clients and server share
> `:core`, releasing them together keeps the contract coherent and is simplest
> early on — this is the recommended default below. If desktop and android later
> need to ship on independent cadences, split into per-component trains exactly like
> the web repo did (`release/<component>-x.y.z`, `<component>-vX.Y.Z` tags). Revisit
> at the first real release.

---

## The model (single train)

```
main                       latest STABLE of the whole app
  ▲  (PR: only from release/x.y.z)
  │
release/x.y.z              a release being assembled (protected)
  ▲  (PR: only from feature/* or bugfix/*)
  │
feature/<desc>            a single feature or tight group
bugfix/<desc>             a single bug fix
```

| Branch | Named | From | Receives PRs from | Protected |
|---|---|---|---|---|
| `main` | `main` | — | `release/*` only | yes |
| release | `release/x.y.z` (e.g. `release/0.1.0`) | `main` | `feature/*`, `bugfix/*` | yes |
| feature | `feature/<desc>` | its release branch | (merges into release) | no |
| bugfix | `bugfix/<desc>` | its release branch | (merges into release) | no |

- `main` always holds the latest stable. It advances when a finished `release/x.y.z`
  is merged in.
- A release branch is named for the **target version only**; pre-release qualifiers
  (`-alpha`, `-rc`) live in **tags**, not branch names.
- Optional `feature/server-…`, `feature/desktop-…`, `feature/android-…`,
  `feature/shared-…` prefixes (convention, not enforced) keep intent legible.

### Lifecycle

```bash
git switch main && git pull
git switch -c release/0.1.0 && git push -u origin release/0.1.0
git switch -c feature/server-cycles   # PR → release/0.1.0
git tag v0.1.0-alpha.1 && git push origin v0.1.0-alpha.1   # build a pre-release
# when ready: PR release/0.1.0 → main, then:
git tag v0.1.0 && git push origin v0.1.0
```

---

## Tags & versioning

| Tag format | Examples |
|---|---|
| `vX.Y.Z[-pre.N]` | `v0.1.0-alpha.1`, `v0.1.0-rc.1`, `v0.1.0` |

Pre-release ordering (SemVer): `-alpha.N` < `-beta.N` < `-rc.N` < final — tags only.

**Version source of truth:** a single `version` in the root Gradle build (or
`gradle.properties`), consumed by all modules. For Android, `versionName` = the
tag's `X.Y.Z` and **`versionCode`** (monotonic integer) bumps on every distributed
build. Desktop installer version = the same `X.Y.Z`.

**Changelog:** one `CHANGELOG.md` at the repo root (Keep-a-Changelog; see
`CLAUDE.md`). If trains split later, split the changelog per component then.

---

## Release artifacts (wire when nearing first release)

Tag-triggered workflows:
- `v*` → `./gradlew :server:installDist` + build/push the backend image; deploy per
  `DEPLOYMENT.md`.
- `v*` → `./gradlew :app:androidApp:bundleRelease` (signed AAB) and
  `:app:desktopApp:packageDistributionForCurrentOS` (desktop installers), attached to a
  GitHub Release. Desktop installers are per-OS, so build the macOS/Windows/Linux
  artifacts on their respective runners.

---

## CI security & quality gates (every PR)

| Job | Purpose | Blocks merge |
|---|---|---|
| `build` | `./gradlew build` (compile all modules) | yes |
| `test` | `./gradlew check` (unit + Testcontainers integration) | yes |
| `detekt`/`ktlint` | Kotlin lint/format | yes |
| `validate-branch-flow` | head → base branch policy | yes |
| `semgrep` | SAST (OSS rule packs incl. `p/kotlin`, `p/java`) | yes |
| `gitleaks` | committed-secret scan (`.gitleaks.toml`) | yes |

- **SAST:** Semgrep OSS (free on private repos). Add CodeQL (free Kotlin/Java
  analysis when public) if the repo is made public.
- **Secret scanning:** gitleaks in CI mirrors the Husky/pre-commit hook —
  defense-in-depth across the full history.
- Branch protection (rulesets in `.github/`) enforces PR-required, up-to-date,
  required checks, no force-push/delete on `main` and `release/*`. Rulesets need a
  public repo or a paid plan for private repos.

> ⚠️ Testcontainers integration tests need a Docker daemon on the CI runner. If
> that's not available, split them into a separate non-blocking job or a self-hosted
> runner rather than dropping the coverage.
