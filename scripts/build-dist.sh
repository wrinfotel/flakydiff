#!/usr/bin/env bash
# Сборка дистрибутива flakydiff (план Task 5.4).
#
# Скрипт ТОЛЬКО собирает артефакты локально и складывает их в dist/ вместе с
# HOWTO. Публикация релиза — отдельное ручное действие и только по явному «го»
# пользователя (правило проекта); Maven Central вне v1 (спека §4.2).
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
flakydiff v${version} — поиск минимального набора polluter-тестов (order dependency)
для flaky JVM-теста по XML-отчётам surefire.

Требования: JDK 17+; Maven-проект жертвы; XML-отчёты последнего прогона.
Каждый зонд — свежая JVM; недостоверность порядка/форки/флейк жертвы всегда
видны в вердикте (order_unreliable / forks_possible / victim_unstable_in_isolation).

1) Диагностика (читает XML, зондирует, печатает вердикт §4.4):
   java -jar flakydiff-${version}.jar diagnose \\
     --project <dir> --reports <dir> --victim <FQCN#method> \\
     [--sequential] [--no-forks] [--prefix-file <f>] [--max-classes N] \\
     [--min-fail-ratio 2] [--victim-timeout s] [--probe-timeout s] [--out <dir>]

   Текст вердикта — в stdout; JSON (verdict.json) — в --out.
   Стоимость зондов митигируется: --prefix-file (ручное сужение prefix),
   --max-classes (кап размера prefix), --min-fail-ratio (строже критерий),
   --victim-timeout/--probe-timeout (рамки на зонд). Подробности: --help.

2) Один зонд вручную (воспроизведение без диагностики):
   java -jar flakydiff-${version}.jar replay \\
     --project <dir> --prefix <FQCN[#method][,FQCN...]> --victim <FQCN#method> [--repeat N]
   Exit-коды: 0 = reproduced, 1 = not reproduced, 2 = инфра/ошибка.

3) Повторный рендер вердикта из JSON:
   java -jar flakydiff-${version}.jar report --verdict <verdict.json> [--format text|json]

flakydiff-${version}-replay.jar — зондовый артефакт: подкладывается в classpath
fresh-JVM-зондов последним (classpath проекта пользователя — первым) и ищется
рядом с основным jar; отдельно запускать его не нужно.
EOF

echo "dist/ готов:"
ls -l dist

cat <<'EOF'

Публикация релиза — ВРУЧНУЮ и только по явному «го» (правило проекта), например:
  gh release create v<version> dist/*.jar dist/HOWTO.txt --title "v<version>" --notes "см. HOWTO.txt"
EOF
