# IngestionServiceApp

## Overview

Parses and cleans `wards-outdated.csv`, a messy legacy export of wards, wings, and specialist departments data, and is the
first stop in the HealthSafe pipeline. Independent Maven module, no parent pom.

Part of the [HealthSafe](../README.md) project.

REST: exposes the cleaned records for `ward-service` (`../ward-service`) to
consume — see [Integration contracts](../README.md#integration-contracts) in the
root README for the endpoint shape.

## Example: one row cleaned

Input (`wards-outdated.csv`, row 6):

```
w-05,east wing ,PAEDIATRICS,five
```

Expected shape after cleaning (exact field names are up to you — this illustrates
the *kind* of transform expected, not a fixed schema to match exactly):

```json
{
  "wardId": "W-05",
  "wing": "East Wing",
  "department": "Paediatrics",
  "bedsAvailable": null,
  "notes": "bedsAvailable was non-numeric ('five') — flagged for follow-up"
}
```

Note this row is also a near-duplicate of `W-05` two rows above it (same real ward,
different ID casing and field values) — deciding how to merge or flag duplicates
like this is part of the exercise. In the shipped fixture the earlier `W-05` row
is the one that survives the merge (`bedsAvailable: 5`, first-seen wins), and this
row's own problem is preserved in `notes` as
`duplicate row issue: bedsAvailable was non-numeric ('five') — flagged for follow-up`,
so nothing is silently thrown away. Check it live with
`curl localhost:7030/wards/W-05`.

## Known data issues

`wards-outdated.csv` is deliberately messy — cleaning it is the point of this service. Look
out for (and handle) at least:

- **Inconsistent casing** in IDs, names, and status/category values (`Active` /
  `active` / `ACTIVE`)
- **Padding** — leading/trailing spaces, and the occasional double space, inside
  fields
- **Duplicate records** for the same real-world entity, written with a different ID
  casing/format and/or slightly different field values
- **Inconsistent date formats** (`YYYY-MM-DD`, `MM/DD/YYYY`, `DD-MM-YYYY`, one- and
  two-digit months/days) and outright invalid dates
- **Missing / placeholder values** — blank fields, `N/A`, `n/a`, `TBD`, `unknown`,
  `-`, `NaN`
- **Invalid or non-numeric values** in numeric columns (negative counts, spelled-out
  numbers, unrealistic values)
- **Inconsistent boolean/flag representations** (`Y`/`N`, `yes`/`no`, `1`/`0`,
  `true`/`FALSE`)
- **Naming/spelling variants** for the same thing (e.g. regional spelling
  differences, synonyms)

### Fixture note

The original 18-row export only contained the first four columns, so it had no
status, date or flag values to clean even though the list above (and the rubric)
calls for them. The `status`, `last_updated` and `special_unit` columns have been
appended with deliberately messy values, plus two rows at the end whose `ward_id`
cannot be identified. **Every original cell is byte-for-byte unchanged**, so the
original problem rows still behave exactly as they did; the cleaner also still
works against a plain 4-column copy of the file (optional columns are read if
present and ignored if not).

## Cleaning decisions

Where the data is ambiguous, the choice was made explicitly rather than guessed:

- **Duplicates merge first-wins** — the first row for a canonical id is the base;
  missing fields are filled from later duplicates, conflicts keep the first-seen
  value. Every merge and conflict lands in `notes`.
- **Untrusted values become `null`**, never `0`/`false`, and always with a note
  saying why (`bedsAvailable was non-numeric ('five') — flagged for follow-up`).
- **Date ambiguity**: `-` separated dates are read day-first (`05-03-2024` →
  5 March), `/` separated dates month-first (`3/5/2024` → 5 March), matching the
  formats listed above. If the preferred reading is impossible (`17/04/2024`),
  the other reading is tried before the value is rejected.
- **Bad rows never abort the parse** — a row without a usable ward id is rejected
  with its line number and reason and reported by `GET /report`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness (`OK`) |
| GET | `/wards` | the cleaned records ward-service consumes |
| GET | `/wards/{id}` | one ward, case/format tolerant (`w-5` works); 404 if unknown |
| GET | `/departments` | distinct departments, sorted |
| GET | `/report` | rows read, wards cleaned, duplicates merged, and every rejected row with its reason |

## Project structure

```
ingestion-service/
├── pom.xml
├── src/main/
│   ├── java/co/wethinkcode/healthsafe/
│   │   ├── IngestionServiceApp.java     (Javalin bootstrap + routes)
│   │   ├── WardCleaner.java             (parse + normalise + merge duplicates)
│   │   └── WardRecord.java              (one cleaned ward)
│   └── resources/wards-outdated.csv
└── src/test/java/co/wethinkcode/healthsafe/WardCleanerTest.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/ingestion-service.jar
```

Listens on port `7030`. The CSV is parsed once at startup; `GET /wards` serves the
result.

## Test

18 JUnit tests in `src/test/java/co/wethinkcode/healthsafe/WardCleanerTest.java`
cover the worked example, duplicate merging, casing/padding, numeric, date, boolean
and status normalisation, and the robustness requirement (malformed rows are
rejected without taking down the parse):

```
mvn test
```

Manually verify it's up:

```
curl http://localhost:7030/health   # -> OK
curl http://localhost:7030/report   # -> what was cleaned, merged and rejected
```
