# Operations guide

## Should you even build this?

Before deploying a custom loader, rule out the options PTC already ships. A customisation
you do not write is a customisation you do not have to revalidate at every upgrade.

| Option | Fits when | Does not fit when |
|---|---|---|
| **Excel bulk create / edit** in the UI | A few hundred rows, business user driven, no scripting | Repeatable, scheduled, or auditable-by-machine loading |
| **`windchill wt.load.LoadFromFile`** with the standard XML loaders | Seeding a fresh environment, demo data, admin objects | Business-facing part data with soft attributes and structure — the XML is unforgiving and error reporting is thin |
| **Windchill Bulk Migrator (WBM)** | Very large one-time migrations, hundreds of thousands of objects | Ongoing operational loading; WBM works at the database layer and needs PTC engagement |
| **ESI / ERP integration** | Master data genuinely owned by ERP | One-off or project-driven loads |
| **This utility** | Repeatable CSV-driven loading with per-row audit, dry-run, restartability and soft attribute support | Volumes where per-row commit is too slow — past roughly 100k objects, talk to PTC about WBM |

The honest sweet spot for this tool is a few hundred to a few tens of thousands of parts,
loaded repeatedly during a programme, by people who need a report they can hand to a
business owner.

---

## Deployment

```sh
# 1. Build on a machine with the Windchill codebase available
ant -Dwt.home=/opt/ptc/Windchill compile

# 2. Deploy the classes into the codebase
ant -Dwt.home=/opt/ptc/Windchill deploy

# 3. RESTART the method server - Windchill does not hot-reload server-side classes
windchill stop
windchill start
```

Directory layout on the server:

```
$WT_HOME/codebase/com/plm/tools/partloader/     compiled classes
$WT_HOME/loadFiles/partloader.properties        configuration
$WT_HOME/loadFiles/parts.csv                    input
$WT_HOME/loadFiles/bom.csv                      input
$WT_HOME/loadFiles/reports/                     one timestamped report per run
```

**All paths are server-side.** The utility executes inside the method server, so a path on
the operator's laptop means nothing to it. This is the single most common "it says file not
found and the file is right there" support call.

---

## Running

Always dry-run first. Always.

```sh
export WT_HOME=/opt/ptc/Windchill
export WC_USER=wcadmin

# Validate everything, persist nothing
./bin/load_parts.sh --dry-run

# Commit
./bin/load_parts.sh
```

Exit codes: `0` clean, `1` completed with row failures, `2` aborted, `3` usage error.
These exist so a scheduler or migration runbook can branch without a human reading the log.

The password comes from `WC_PASSWORD` if set, otherwise the utility prompts. Do not pass
`--password` on the command line: it lands in shell history and in the process table, where
any other account on the server can read it with `ps`.

---

## Which account should run it

Not `wcadmin` reflexively, and not a named person's account.

Create a dedicated service account — `svc_partloader` or similar — and give it exactly the
access it needs in the target contexts. Reasons:

- Every object created carries its creator. Six months later "who loaded these 4 000 parts"
  should have an answer that is not "an administrator".
- A named person's account breaks the day they change roles, and their leaving triggers a
  silent failure of a scheduled job.
- `wcadmin` has access to everything, so an error in the container column loads parts
  somewhere they should not exist rather than failing cleanly.

The one case for administrative privilege is `loader.lifecycle.allowDirectStateAssignment`,
which requires it. That is a further reason to keep that flag off.

---

## Performance

Per-row commit is deliberate (see the comment on `PartLoaderService.runInTransaction`). It
costs throughput and buys restartability. Expect roughly 3–10 parts/second on typical
hardware, dominated by the soft attribute writes.

If a run is too slow:

1. **Check the queues first.** Every part created generates background work — indexing,
   replication, subscriptions. If `wt.queue` is backing up, the loader is not the
   bottleneck. Watch the queue depth in System Configurator during the run.
2. **Run in a maintenance window.** Bulk loading competes with interactive users for the
   same method server threads and the same database.
3. **Split the file and run several method servers.** More effective than tuning the loader,
   provided the files do not touch the same parent assemblies.
4. **Do not batch the transactions to make the number look better.** A one-transaction run
   that fails at row 39 999 loses everything and holds locks for the entire run.

Method server heap matters less than people expect — the loader streams the input file and
holds only the context caches. If you are running out of heap, the cause is almost always
the background queues, not this utility.

---

## Governance

Loading is not a technical activity with a business side-effect. It is a business activity
with a technical implementation, and it needs the same controls as any other data change.

**Before a production run:**

- The file is signed off by the data owner, not by IT. IT owns the mechanism; the business
  owns the content.
- The same file has been loaded successfully in QA against a recent production restore.
  Loading into an empty QA proves nothing about how it behaves against existing data.
- A rollback position exists. For a small load that is a list of created objects from the
  report; for a large one it is a database restore point and an agreed window in which to
  use it.
- The run is on the change calendar. A load that creates 4 000 parts also creates 4 000
  index entries and a replication burst.

**After:**

- Archive the report with the load file. Together they answer "which source row produced
  this object", which is the question an auditor actually asks.
- Reconcile counts with the data owner before declaring the wave complete.

**Standing rules worth writing down:**

- Never point a loader at production for a "quick test". There is no such thing.
- `loader.iba.strict` stays `true`. A loader that reports success while dropping attribute
  values is worse than one that fails.
- `loader.update.existing` stays `false` by default and is enabled per run, consciously.
- Direct lifecycle state assignment requires a recorded decision naming who authorised it
  and why workflow was not used.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Configuration file not found on the server host` | Path is on the workstation, not the server | Put the file under `$WT_HOME/loadFiles` |
| `wt.method.MethodAccessException` | Classes not deployed, or method server not restarted | Redeploy and restart |
| `Container not found: PRODUCT 'X'` | Name mismatch, or wrong container type | Names are exact and case sensitive; check PRODUCT vs LIBRARY |
| `Container name is ambiguous` | Same product name in two organisations | Split the load file per organisation |
| Every row `SKIPPED` on a fresh environment | Parts genuinely exist, or `PartLookup` is matching wrongly | Check one number in the UI before assuming a bug |
| `Part is already checked out` | A designer is working on it | Reschedule; do not force |
| `Invalid unit of measure 'each'` | Display label used instead of internal value | Use `ea` |
| Rows fail only after the first few hundred | Simple rows sort first in most files; the failures are the real data | Read the report, not the first screen of log |
| `Folder does not exist` | Folder path assumes the UI tree, which hides the Default cabinet | Paths are `/Default/...`; the mapper prefixes it, but check the rest |
