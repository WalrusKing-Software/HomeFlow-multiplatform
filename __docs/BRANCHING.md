# BRANCHING.md — Branching, Releases & CI Gates

Defines the git model and the checks that run before a merge. Adapted from the web
monorepo's two-train model. **This repo is one product with three deliverables**
(`:server`, `:app:desktopApp`, `:app:androidApp`) that share a single
contract (`:core`) — so it starts as a **single release train**, not the web
repo's split web/mobile trains.

> **Per-component vs. full-suite releases.** From v0.2.0 onward, the server and
> clients can be released independently using component-scoped tags (`server-v*`,
> `desktop-v*`, `android-v*`). A `v*` tag still builds and publishes all three
> together (lockstep). See the Tags & versioning section below and `COMPATIBILITY.md`.

---

## The model (single train)

```
main                       latest STABLE of the whole app
  ▲  (PR: only from release/x.y.z)
  │
release/x.y.z              the chosen version, assembled as ONE clean commit (protected)
  ▲  (PR: from develop-* ; feature/*|bugfix/* allowed for hotfixes)
  │
develop-<name>             integration branch for the next release (protected)
  ▲  (PR: only from feature/* or bugfix/*)
  │
feature/<desc>            a single feature or tight group
bugfix/<desc>             a single bug fix
```

| Branch | Named | From | Receives PRs from | Protected |
|---|---|---|---|---|
| `main` | `main` | — | `release/*` only | yes |
| release | `release/x.y.z` (e.g. `release/0.1.0`) | `develop-*` | `develop-*`; `feature/*`/`bugfix/*` (hotfix) | yes |
| develop | `develop-<name>` (e.g. `develop-next`) | `main` | `feature/*`, `bugfix/*` | yes |
| feature | `feature/<desc>` | its develop branch | (merges into develop) | no |
| bugfix | `bugfix/<desc>` | its develop branch | (merges into develop) | no |

- `main` always holds the latest stable. It advances when a finished `release/x.y.z`
  is merged in.
- **`develop-*` is the integration branch for the next release.** Work branches merge
  into it while the version number is still **undecided**, so you never have to commit
  to a `release/x.y.z` number prematurely. When the release is ready, its `develop-*`
  branch lands on a fresh `release/x.y.z` as **one clean (squashed) commit** — keeping
  release branches tidy and the version choice deferred to the last responsible moment.
  Name it anything after `develop-` (`develop-next`, `develop-0.2`, a theme, …); the
  ruleset matches `develop-*`.
- A release branch is named for the **target version** (`release/x.y.z`). The
  version-only form is the canonical, preferred style, with pre-release qualifiers
  (`-alpha`, `-rc`) carried in **tags**. An optional `v` prefix and/or a pre-release
  suffix on the branch name are also accepted (e.g. `release/v0.1.0-alpha-1`) — the
  branch-flow check matches `release/v?x.y.z[-suffix]`. Either way, the published
  pre-release identity lives in the **tag** (`v0.1.0-alpha.1`).
- Optional `feature/server-…`, `feature/desktop-…`, `feature/android-…`,
  `feature/shared-…` prefixes (convention, not enforced) keep intent legible.

### Lifecycle

```bash
git switch main && git pull
git switch -c develop-next && git push -u origin develop-next   # next release, version TBD
git switch -c feature/server-cycles   # PR → develop-next
git tag v0.1.0-alpha.1 && git push origin v0.1.0-alpha.1        # pre-release build off develop
# when the version is decided and the release is ready:
git switch main && git switch -c release/0.1.0 && git push -u origin release/0.1.0
#   PR develop-next → release/0.1.0  (SQUASH merge → release is one clean commit)
#   PR release/0.1.0 → main
git tag v0.1.0 && git push origin v0.1.0
```

---

## Tags & versioning

| Tag format | Targets | Example |
|---|---|---|
| `vX.Y.Z[-pre.N]` | All three — full lockstep release | `v0.3.0`, `v0.3.0-rc.1` |
| `server-vX.Y.Z[-pre.N]` | Server only | `server-v0.2.1` |
| `desktop-vX.Y.Z[-pre.N]` | Desktop only | `desktop-v0.2.1` |
| `android-vX.Y.Z[-pre.N]` | Android only | `android-v0.2.1` |
| `clients-vX.Y.Z[-pre.N]` | Desktop + Android together, no server | `clients-v0.2.1` |

Pre-release ordering (SemVer): `-alpha.N` < `-beta.N` < `-rc.N` < final — tags only.

**Version source of truth:** three per-component keys in `gradle.properties`
(`version.server`, `version.desktop`, `version.android`). Each deliverable module
reads its own key. `:core` and `:app:shared` are never released independently and
carry no version. For Android, `versionName` = the tag's `X.Y.Z` and **`versionCode`**
is a monotonic integer derived from it. Desktop installer version = same `X.Y.Z`.
A `clients-v*` release requires `version.desktop` and `version.android` to already
match (bump both together with `sh scripts/bump-version.sh clients`).

**Compatibility:** see `COMPATIBILITY.md` for the client–server compatibility matrix.
Update it whenever a release raises the minimum supported client version.

**Changelog:** one `CHANGELOG.md` at the repo root (Keep-a-Changelog; see `CLAUDE.md`).
Component-only releases use prefixed headers (`## [Server X.Y.Z]`, `## [Desktop X.Y.Z]`,
`## [Android X.Y.Z]`, `## [Clients X.Y.Z]` for a combined desktop+Android release);
full-suite releases use `## [X.Y.Z]`.

---

## Release artifacts

The release pipeline is implemented. See `__docs/RELEASE-PIPELINE.md` for the full
specification and the implementation checklist. In short: push a `vX.Y.Z` tag (or
trigger `workflow_dispatch`) to build the server distribution + multi-arch Docker
image, desktop installers for all three platforms, and a signed Android APK/AAB —
all attached to a single GitHub Release.

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
- Branch protection (rulesets in `.github/rulesets/`, applied via
  `.github/scripts/apply-branch-protection.sh`) enforces PR-required, up-to-date,
  required checks, no force-push/delete on `main`, `release/*`, and `develop-*`.
  Rulesets need a public repo or a paid plan for private repos.

> ⚠️ Testcontainers integration tests need a Docker daemon on the CI runner. If
> that's not available, split them into a separate non-blocking job or a self-hosted
> runner rather than dropping the coverage.
