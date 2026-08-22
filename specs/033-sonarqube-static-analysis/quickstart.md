# Quickstart: verifying this feature locally

**Feature**: 033-sonarqube-static-analysis

Everything here runs from the repository root with no network account and no
credential. It is the complete set of checks available before the SonarQube Cloud
account exists.

## 1. Frontend coverage produces a non-empty LCOV report

```bash
cd frontend && npm run test:coverage
test -s coverage/lcov.info && echo "PASS: lcov.info present and non-empty" \
                           || echo "FAIL: lcov.info missing or empty"
```

Expected: 67 test files run, `coverage/lcov.info` written. `coverage/` is
gitignored (`.gitignore:69`) and ESLint-ignored, so nothing to clean up.

## 2. Frontend lint still exits 0

```bash
cd frontend && npm run lint; echo "exit=$?"
```

Expected: `exit=0` with 5 `react-refresh/only-export-components` **warnings** and
0 errors. If errors appear, the lint step must not land blocking — see FR-012.

## 3. `software-factory` coverage produces a report, and read the number

```bash
./gradlew :software-factory:jacocoTestReport
test -s software-factory/build/reports/jacoco/test/jacocoTestReport.xml \
  && echo "PASS: report present" || echo "FAIL: report missing"
```

Then read the actual percentage — this number is reported to the operator so a
floor can be chosen in follow-up work:

```bash
python3 - <<'PY'
import xml.etree.ElementTree as ET
r = ET.parse('software-factory/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for c in r.findall('counter'):
    if c.get('type') == 'INSTRUCTION':
        cov, mis = int(c.get('covered')), int(c.get('missed'))
        print(f"software-factory instruction coverage: {cov/(cov+mis):.1%}")
PY
```

Expected: a report exists and a percentage prints. **No build failure regardless
of the number** — there is deliberately no verification rule (FR-017).

## 4. Backend coverage is unchanged

```bash
./gradlew :backend:jacocoTestReport :backend:jacocoTestCoverageVerification
```

Expected: passes, as today. The 0.78 floor is untouched by this feature. Slow —
it runs the Testcontainers suite.

## 5. The `sonar` task graph resolves

```bash
./gradlew sonar --dry-run
```

Expected: `:sonar SKIPPED`, `BUILD SUCCESSFUL`. Note it pulls in **no** compile
tasks — which is why CI names `classes testClasses sonar` explicitly (research
R3).

## 6. Every path in the Sonar properties exists

The check that catches the feature's likeliest silent failure. Run after steps
1, 3 and 4 so all three reports exist.

```bash
fail=0
for p in \
  backend/build/reports/jacoco/test/jacocoTestReport.xml \
  software-factory/build/reports/jacoco/test/jacocoTestReport.xml \
  frontend/coverage/lcov.info \
  frontend/tsconfig.app.json \
  frontend/src \
  frontend/tests
do
  if [ -e "$p" ]; then echo "  ok   $p"; else echo "  MISS $p"; fail=1; fi
done
[ $fail -eq 0 ] && echo "PASS: all sonar property paths resolve" \
                || echo "FAIL: a sonar property points at nothing"
```

A missing report is **not** an analysis error — Sonar reports 0% and carries on.
That is why this is an explicit assertion rather than something the build catches.

## 7. The nine coverage exclusions still mirror each other

```bash
echo "--- jacocoExcludes (backend/build.gradle.kts) ---"
sed -n '/^val jacocoExcludes/,/^)/p' backend/build.gradle.kts | grep -c '"'
echo "--- sonar.coverage.exclusions (build.gradle.kts) ---"
sed -n '/sonar.coverage.exclusions/,/)/p' build.gradle.kts | grep -oc 'com/simonrowe'
```

Expected: both report 9. This catches the count drifting; it cannot catch an entry
being edited to point somewhere else. The runbook carries that obligation
(FR-020).

## 8. Compilation for analysis works

```bash
./gradlew classes testClasses
```

Expected: passes. This is what CI runs before `sonar` so sonar-java has bytecode
to analyse. Does not run any test.

## What cannot be verified here

| Not verifiable | Why | Verified instead by |
| --- | --- | --- |
| A real analysis | Needs the account and `SONAR_TOKEN`. A tokenless run takes ~10 min then fails (research R2) — do not attempt it. | Operator's first PR after the checklist |
| Server-side property interpretation | `-Dsonar.scanner.dumpToFile` does not work here; the scanner contacts the server first (research R4) | Operator's first PR |
| Sonar/JaCoCo percentages agreeing | Needs a real analysis | Operator's first PR |
| PR decoration | Needs the GitHub App installed | Operator's first PR |
| The `sonar` job skipping cleanly | Needs a real workflow run | The pull request for this change itself |
| The three signal reads in the skill | Needs a live project | Operator's first PR after the checklist |
