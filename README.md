# flakydiff

Finds the minimal set of test polluters (order dependency) behind a flaky JVM test, from XML test reports.

**Status:** WIP — implementation in progress, see `docs/plans/2026-09-27-flakydiff-v1-implementation.md`.

## Build & distribution

```bash
scripts/build-dist.sh          # mvn -B clean package + staging в dist/ (2 jars + HOWTO.txt)
```

Артефакты (оба даёт maven-shade, отдельный assembly не нужен):

- `flakydiff-<version>.jar` — CLI (diagnose / replay / report), выполняется `java -jar`;
- `flakydiff-<version>-replay.jar` — зондовый артефакт (ReplayMain), подкладывается в
  classpath fresh-JVM-зондов последним; рядом с основным jar находится автоматически.

Краткий HOWTO — `dist/HOWTO.txt` (тот же текст печатает `scripts/build-dist.sh`).

Публикация: релиз v0.1.0 на GitHub Releases собирается скриптом локально, но
выкладывается **вручную и только по явному «го»** (правило проекта). Maven Central —
вне v1 (спека §4.2).

## Usage (кратко)

```bash
java -jar flakydiff-<version>.jar diagnose --project <dir> --reports <dir> --victim <FQCN#method> [--sequential] [--no-forks] [--prefix-file <f>] [--max-classes N] [--min-fail-ratio 2] [--out <dir>]
java -jar flakydiff-<version>.jar replay   --project <dir> --prefix <FQCN[,FQCN...]> --victim <FQCN#method> [--repeat N]
java -jar flakydiff-<version>.jar report   --verdict <verdict.json> [--format text|json]
```

Каждый зонд — свежая JVM; недостоверность порядка/форки/флейк жертвы всегда видны в
вердикте (`order_unreliable`, `forks_possible`, `victim_unstable_in_isolation`).
