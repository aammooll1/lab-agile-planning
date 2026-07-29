# lab-agile-planning

This repository contains the lab for agile planning.

---

# Windchill Part Loader (12.1.x)

A CSV-driven Java utility that creates and updates `WTPart` objects and their BOM structure
in Windchill PDMLink, with dry-run validation, per-row audit reporting and restartable
re-runs.

> **Not yet compiled against a live codebase.** Read [`docs/API_VERIFICATION.md`](docs/API_VERIFICATION.md)
> before your first build — it lists every call site whose signature needs confirming
> against your 12.1.2 installation, with an honest confidence tag and the fix for each.

## What it does

- Creates parts with number, name, container, folder, view, assembly mode, source, default
  unit and trace code
- Sets soft attributes (IBAs) through the supported logical-attribute framework, not the
  legacy `IBAValueDBService` path
- Assigns soft types (subtypes) so OIR numbering rules and the correct attribute set apply
- Builds parent/child structure as `WTPartUsageLink`, one checkout per assembly rather than
  one per line
- Detects existing parts per view and skips or updates them — a re-run of the same file is
  safe by default
- `--dry-run` validates containers, folders, views, enumerations, soft types and attribute
  names without persisting anything
- Writes one timestamped CSV report per run mapping every source line to its outcome

## Quick start

```sh
# Build and deploy on the Windchill server
ant -Dwt.home=/opt/ptc/Windchill compile
ant -Dwt.home=/opt/ptc/Windchill deploy
windchill stop && windchill start          # server-side classes are not hot-reloaded

# Stage inputs where the METHOD SERVER can read them
cp conf/partloader.properties  $WT_HOME/loadFiles/
cp samples/parts_sample.csv    $WT_HOME/loadFiles/parts.csv
cp samples/bom_sample.csv      $WT_HOME/loadFiles/bom.csv
mkdir -p $WT_HOME/loadFiles/reports

# Validate, then commit
export WC_USER=svc_partloader
./bin/load_parts.sh --dry-run
./bin/load_parts.sh
```

## Input format

**Parts** — `number` and `name` are mandatory, everything else falls back to
`conf/partloader.properties`. Any column named `IBA:<internal attribute name>` becomes a
soft attribute. Unrecognised columns are ignored, so the business can hand over their
working spreadsheet without stripping their own planning columns.

```csv
number,name,containerType,containerName,folderPath,view,partType,source,defaultUnit,IBA:MaterialGrade
BRK-100001,Bracket Body,PRODUCT,Headlamp Program X,/Default/Parts,Design,component,make,ea,EN AW-6082 T6
```

**BOM** — loaded as a second pass so the file does not need to be topologically sorted.

```csv
parentNumber,parentView,childNumber,quantity,defaultUnit
BRK-100000,Design,BRK-100001,1,ea
```

Enumerated columns take **internal values**, never display labels: `make` not "Make", `ea`
not "each". Files built from display labels work in an English DEV environment and fail in a
localised production one.

## How it is put together

```
PartLoaderMain          CLI. Authenticates, invokes one remote method, prints the summary.
  └─ PartLoaderService  Runs INSIDE the method server. Reads files, drives transactions.
       ├─ RowMapper     CSV record -> validated, defaulted domain row.
       ├─ PartFactory   Create / update / skip a single part.
       │    ├─ ContextResolver  Container, folder, view — cached per run.
       │    ├─ SoftTypeResolver Subtype assignment.
       │    ├─ EnumResolver     Strings -> Windchill enumerated types.
       │    ├─ IbaWriter        Soft attributes via PersistableAdapter.
       │    ├─ CheckoutUtil     Checkout / checkin / undo for the update path.
       │    └─ LifecycleUtil    Optional direct state assignment (off by default).
       └─ BomBuilder     Usage links, grouped by parent assembly.
```

The work happens server-side on purpose. A loader that calls Windchill APIs from a remote
client turns every call into an RMI round trip; 40 000 parts becomes hours instead of
minutes. Implementing `wt.method.RemoteAccess` and invoking a single `public static` method
moves the whole loop inside the method server, and only the arguments and the summary cross
the wire.

**The consequence:** every path — config, CSV, reports — is resolved on the *server* host,
not the operator's workstation.

## Design decisions worth knowing about

- **One transaction per row.** Slower than a single bulk transaction, and correct: row
  39 999 failing must not discard the 39 998 good rows before it, and a run-length
  transaction holds database locks that will block interactive users.
- **Blank cell means "no value supplied", never "clear the value".** Otherwise a partial
  re-load silently wipes attributes that were maintained by hand in the UI.
- **Hand-written CSV parser.** Dropping OpenCSV or POI into `WEB-INF/lib` to read a load
  file is a well-trodden way to destabilise a Windchill installation — the jar collides with
  a PTC-shipped version and the conflict surfaces months later, in an unrelated module.
- **Every row is reported, not just failures.** A loader you cannot reconcile against the
  source file cannot be signed off by a business owner.
- **Existing parts are skipped by default.** Every enterprise load is a re-load.

## Safety defaults

These ship off. Turn each on deliberately, per run, and record why.

| Setting | Default | Why |
|---|---|---|
| `loader.update.existing` | `false` | An accidental re-run should report SKIPPED, not iterate every part in the file |
| `loader.folder.autoCreate` | `false` | A typo otherwise builds a parallel folder tree with the wrong access rules |
| `loader.lifecycle.allowDirectStateAssignment` | `false` | Bypasses workflow entirely — legitimate for legacy migration, an audit finding almost anywhere else |
| `loader.iba.strict` | `true` | A loader that reports success while dropping attribute values is worse than one that fails |

## Documentation

- [`docs/API_VERIFICATION.md`](docs/API_VERIFICATION.md) — pre-build checklist, confidence
  tags, fixes
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md) — deployment, service account, performance,
  governance, troubleshooting; also when **not** to use this and use a PTC-shipped mechanism
  instead

## Requirements

Windchill 12.1.x · Java 11 (matching the method server JVM) · Ant · read access to
`$WT_HOME/codebase/WEB-INF/lib`
