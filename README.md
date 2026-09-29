# flakydiff

[![CI](https://github.com/wrinfotel/flakydiff/actions/workflows/ci.yml/badge.svg)](https://github.com/wrinfotel/flakydiff/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![JDK](https://img.shields.io/badge/JDK-17%2B-blue)

Finds the minimal set of **test polluters** (order dependency) behind a flaky JVM test, from XML test reports.

Retry plugins tell you *that* a test is flaky — not *why* it fails. flakydiff tests one specific hypothesis: **order dependency** (test pollution). It takes the XML reports of a failing CI/local run, reconstructs the recorded execution order, and searches the victim's predecessors with delta debugging until it has the minimal set of tests that make the victim fail — verifying every hypothesis in a fresh JVM.

The method is the JVM analog of [`asottile/detect-test-pollution`](https://github.com/asottile/detect-test-pollution) (Python), generalized with delta debugging instead of bisection.

## How it works

1. **reader** — parses the reports of the recorded run (Surefire XML, including Gradle's legacy-JUnit format) into an ordered execution list. Class order comes from exact `timestamp` attributes (surefire ≥ 3.5.5 with `reportTestTimestamp=true`) when present, otherwise from a file-mtime heuristic. Rerun entries (`rerunFailure`/`flakyFailure`) are deduplicated to the first execution and mark the test as flaky-in-run.
2. **replay** — every probe is a **fresh JVM** running `prefix → victim × N` through the JUnit Platform Launcher. The victim project's classpath comes first (its own JUnit platform version wins), the bundled `flakydiff-replay.jar` last. Surefire configuration from the effective pom — system properties, `systemPropertiesFile`, `argLine`, active profiles — is propagated into the probe, so it sees the same environment as the recorded run.
3. **ddmin** — preconditions first: the victim must pass alone (step 0), the full recorded prefix must reproduce the failure (step 1). Then chunked delta debugging narrows the prefix to a 1-minimal polluter set. A probe counts as failing only when the victim fails **≥ 2 of 3** repeats (tunable). Infra errors (broken classpath, timeouts, setup failures) never narrow the search — after one retry they stop it with `INFRA-BROKEN`. The final set is re-confirmed in another fresh JVM with a stricter criterion (**≥ 4 of 5**), otherwise the verdict is downgraded to `UNCONFIRMED`.
4. **verdict** — human-readable text plus machine-readable JSON with the same fields, always carrying trust flags (see [Verdicts](#verdicts)).

**Cost, honestly:** every probe runs the entire prefix in a new JVM. On a real project with heavy test contexts (e.g. Spring) a diagnosis takes hours, not minutes. The mitigations are first-class CLI flags: `--prefix-file` (hand-picked suspects) and `--max-classes` (cap the prefix size).

## Requirements

- JDK 17+ to run flakydiff.
- The victim project: Maven (3.9+ recommended) with JUnit 5 (Jupiter) tests. Maven is invoked to compile the tests, build the classpath and read the effective pom.
- XML reports of the recorded run: `target/surefire-reports` or equivalent (Gradle's JUnit XML also works).

JUnit 4 and TestNG victims are not supported in v1 — they are reported honestly as `UNSUPPORTED`, never silently skipped.

## Install

Grab the two jars from the [latest release](https://github.com/wrinfotel/flakydiff/releases) and keep them side by side:

- `flakydiff-<version>.jar` — the CLI (`diagnose` / `replay` / `report`), runs with `java -jar`;
- `flakydiff-<version>-replay.jar` — the probe artifact. It is picked up automatically next to the main jar and appended last to every probe's classpath. You never run it directly.

## Quick start

```bash
java -jar flakydiff-0.1.0.jar diagnose \
  --project /path/to/victim-project \
  --reports /path/to/victim-project/target/surefire-reports \
  --victim com.acme.OrderServiceTest.shouldCharge
```

The victim is `FQCN#method`. Output (real format; diagnostic strings are currently localized to Russian — the JSON is language-neutral):

```
VERDICT: ORDER-DEPENDENCY (reproduced 3/3, confirmed fresh-process 5/5)
victim:   com.acme.OrderServiceTest.shouldCharge
polluter: com.acme.PaymentRepositoryTest.shouldCacheIds
evidence: изолированно 3/3 pass; после polluter 3/3 fail
flags:    order_unreliable=no  forks_possible=yes
repro:    flakydiff replay --project /path/to/victim-project --prefix com.acme.PaymentRepositoryTest --victim com.acme.OrderServiceTest#shouldCharge
```

The `repro` line is a runnable command: it re-verifies the finding with a single manual probe. If the victim is slow (timeout frame above the 30 s floor), the repro command already carries the right `--victim-timeout`.

## Commands

### `diagnose` — full pipeline

```
java -jar flakydiff-<version>.jar diagnose
  --project <dir>            victim's Maven project root (required)
  --reports <dir>            directory with XML reports of the recorded run (required)
  --victim <FQCN#method>     the flaky test (required)
  [--sequential]             the recorded run was sequential; clears order_unreliable
                             only when the order came from exact timestamps
  [--no-forks]               surefire forking excluded (forkCount=1, reuseForks=true)
  [--prefix-file <file>]     hand-picked suspects, one FQCN[#method] per line (# comments allowed);
                             overrides the recorded order
  [--max-classes N]          cap the prefix: keeps the first N recorded entries + warning
  [--min-fail-ratio K]       victim must fail K of 3 repeats to count as "reproduced" (default 2;
                             ≥2 recommended — 1-of-3 noise would break ddmin monotonicity)
  [--victim-timeout SEC]     per-repeat victim frame; default clamp(xml duration × 5, 30s, 180s)
  [--probe-timeout SEC]      whole-probe frame; default min(2·Σ(prefix) + victimTimeout·repeat + 60s, 15 min)
  [--out <dir>]              also write verdict.json here
```

Exit codes: `0` verdict produced; `1` victim not found in the reports / diagnosis failed; `2` usage errors.

### `replay` — single manual probe

```
java -jar flakydiff-<version>.jar replay
  --project <dir>            victim's Maven project root (required)
  --victim <FQCN#method>     the test to repeat (required)
  [--prefix <list>]          comma-separated FQCN[#method] run before the victim, in this order,
                             in the same JVM; empty = victim alone
  [--repeat N]               victim repeats, ≥ 3 (default 3)
  [--victim-timeout SEC]     default 30 s here (no XML durations to derive it from)
  [--probe-timeout SEC]      whole-probe frame
```

Prints `REPRODUCED failures/attempts` (+ failure type and message), `NOT-REPRODUCED passes/attempts pass` or `INFRA-ERROR: cause`. Exit codes: `0` reproduced, `1` not reproduced, `2` infra/usage error.

Use it to double-check a verdict or to bisect a hypothesis by hand. Note the semantics: the state (statics, caches, leaked threads) is **not** reset between the victim's repeats — that is the pollution being measured.

### `report` — re-render a verdict

```
java -jar flakydiff-<version>.jar report --verdict verdict.json [--format text|json]
```

## Verdicts

| Verdict | Meaning |
|---|---|
| `ORDER-DEPENDENCY` | Polluter set found, reproduced and confirmed in a fresh process (≥ 4 of 5). `repro` carries the one-liner. |
| `NOT-ISOLATED` | The victim fails or flakes **without any prefix** — order dependency is not the (only) story. With `victim_unstable_in_isolation=yes` it is flaky on its own. |
| `NOT-REPRODUCED` | The full recorded prefix does not reproduce the failure. Likely a race, timing or async issue — outside v1's hypothesis. |
| `UNCONFIRMED` | ddmin found a polluter set, but the stricter fresh-process confirmation (≥ 4 of 5) did not hold. Actual rates are in the verdict and JSON. |
| `INFRA-BROKEN` | Probe infrastructure broke (compilation, classpath, timeouts, setup failures). Comes with diagnostics; no polluters are ever reported here. Fix the environment, not the test. |
| `UNSUPPORTED` | The victim cannot be addressed by the probe (e.g. a parameterized test beyond the fallback) — with the test name in diagnostics. |

Every verdict is also written as JSON (same fields, camelCase) for CI/trend consumption: `type`, `victim`, `polluters`, `evidence`, `reproducedRate`, `confirmedRate`, `reproCommand`, `diagnostics` and the flags below.

## Trust flags — never lies silently

Some facts cannot be proven from XML alone, so they are surfaced in **every** verdict instead of being assumed away:

- `order_unreliable` — `yes` by default; `no` only with `--sequential` **and** exact timestamps **and** no adjacent-class ambiguity. When the order had to be reconstructed from file mtimes, the reason is printed in parentheses.
- `forks_possible` — `yes` unless you pass `--no-forks`. With surefire forking, shared statics do not survive across classes and the tool would "find" dependencies that cannot exist; XML does not reveal forking, so it stays honest by default.
- `victim_unstable_in_isolation`, `marked_flaky_in_run` — printed only when actually set (victim flakes alone / victim had rerun entries in the recorded run).

## Limitations (v1)

- **JUnit 5 (Jupiter) victims only.** JUnit 4 / TestNG → `UNSUPPORTED` (honest, not silent). Parameterized victims are addressed via a class selector plus a name-prefix filter when possible, else `UNSUPPORTED`.
- **Maven projects only for probing.** Gradle XML reports are read fine, but the replay adapter invokes Maven.
- **Forking and parallelism are not detected** from XML — hence the default-on trust flags.
- **Two independent polluters:** the 1-minimal result contains exactly one of them. This is documented delta-debugging behavior, pinned by tests.
- **One hypothesis.** Races, wall-clock and async-wait flakiness are out of scope; `NOT-REPRODUCED` is the honest outcome for those.

## Building from source

```bash
mvn -B verify -P fast   # unit tests (default profile)
mvn -B verify -P full   # + integration: real mvn runs, fresh-JVM probes, e2e matrix
scripts/build-dist.sh   # package + stage dist/: CLI jar, replay jar, HOWTO.txt
```

CI runs both profiles on JDK 17 and 21 ([workflow](.github/workflows/ci.yml)).

## License

[MIT](LICENSE)
