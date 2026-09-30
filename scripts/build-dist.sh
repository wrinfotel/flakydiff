#!/usr/bin/env bash
# Builds the flakydiff distribution (plan Task 5.4).
#
# This script ONLY builds the artifacts locally and places them into dist/ along
# with the HOWTO. Publishing a release is a separate manual step that happens
# only on the user's explicit "go" (project rule); Maven Central is out of scope
# for v1 (spec §4.2).
set -euo pipefail

cd "$(dirname "$0")/.."

version="$(mvn -B help:evaluate -Dexpression=project.version -q -DforceStdout)"
main_jar="target/flakydiff-${version}.jar"
replay_jar="target/flakydiff-${version}-replay.jar"

echo "== mvn -B clean package"
mvn -B clean package

for f in "$main_jar" "$replay_jar"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: artifact not found: $f" >&2
    exit 1
  fi
done

rm -rf dist
mkdir -p dist
cp "$main_jar" "$replay_jar" dist/

cat > dist/HOWTO.txt <<EOF
flakydiff v${version} — finds a minimal set of polluter tests (order dependency)
behind a flaky JVM test from surefire XML reports.

Requirements: JDK 17+; the victim's Maven project; XML reports of the latest run.
Every probe is a fresh JVM; unreliable ordering / forks / a flaky victim are
always visible in the verdict (order_unreliable / forks_possible / victim_unstable_in_isolation).

1) Diagnostics (reads the XML, probes, prints the §4.4 verdict):
   java -jar flakydiff-${version}.jar diagnose \\
     --project <dir> --reports <dir> --victim <FQCN#method> \\
     [--sequential] [--no-forks] [--prefix-file <f>] [--max-classes N] \\
     [--min-fail-ratio 2] [--victim-timeout s] [--probe-timeout s] [--out <dir>]

   Verdict text goes to stdout; JSON (verdict.json) goes to --out.
   Probe cost is mitigated by: --prefix-file (manual prefix narrowing),
   --max-classes (prefix size cap), --min-fail-ratio (stricter criterion),
   --victim-timeout/--probe-timeout (probe budgets). See --help for details.

2) A single manual probe (reproduction without diagnostics):
   java -jar flakydiff-${version}.jar replay \\
     --project <dir> --prefix <FQCN[#method][,FQCN...]> --victim <FQCN#method> [--repeat N]
   Exit codes: 0 = reproduced, 1 = not reproduced, 2 = infra/error.

3) Re-render a verdict from JSON:
   java -jar flakydiff-${version}.jar report --verdict <verdict.json> [--format text|json]

flakydiff-${version}-replay.jar — the probe artifact: it is appended to the classpath
of fresh-JVM probes last (the user's project classpath comes first) and is looked up
next to the main jar; there is no need to run it separately.
EOF

echo "dist/ ready:"
ls -l dist

cat <<'EOF'

Release publishing is MANUAL and happens only on an explicit "go" (project rule), e.g.:
  gh release create v<version> dist/*.jar dist/HOWTO.txt --title "v<version>" --notes "see HOWTO.txt"
EOF
