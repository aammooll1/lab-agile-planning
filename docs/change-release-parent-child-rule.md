# Windchill PLM — Change-Release Parent/Child Business Rule

Enforces BOM consistency at change release: **a parent assembly cannot be
released through a change unless all of its child components are already released
(or are being released by the same change).**

This stops a released assembly from pointing at In-Work / WIP children.

## What's included

| File | Purpose |
|------|---------|
| `src/ext/windchill/change/rules/ChangeReleaseChildParentRule.java` | Core rule. `validate(changeOrder)` throws on violation; `findViolations(changeOrder)` returns a list. |
| `src/ext/windchill/change/rules/ChangeReleaseValidationException.java` | `WTException` subclass carrying the user-facing failure message. |
| `src/ext/windchill/change/rules/ChangeReleaseRuleConstants.java` | Tunables: released-state names, full-BOM vs single-level, same-change allowance. |
| `src/ext/windchill/change/rules/ChangeReleaseRuleListener.java` | Optional event listener that enforces the rule on the lifecycle pre-promote event (catches releases done outside the workflow). |
| `workflow/ChangeReleaseChildParentExpressionRobot.txt` | Copy/paste snippet for a workflow expression robot (the usual integration point). |
| `conf/wt.properties.snippet` | `xconfmanager` registration for the listener variant. |

## How the rule works

1. From the `WTChangeOrder2` being released, it gathers the resulting ("after")
   objects via `ChangeHelper2.service.getChangeablesAfter(...)` and keeps the
   `WTPart`s — these are the parents being released.
2. For each released parent it walks the BOM with
   `WTPartHelper.service.getUsesWTParts(parent)`.
3. For every child it checks the lifecycle state via
   `LifeCycleHelper.service.getState(...)` against
   `ChangeReleaseRuleConstants.RELEASED_STATE_NAMES`.
4. A child that is itself part of the same change is accepted (co-release),
   controlled by `ALLOW_CHILD_IN_SAME_CHANGE`.
5. Any non-released child produces a violation. If there are violations,
   `validate(...)` throws `ChangeReleaseValidationException` with a detailed,
   user-readable message; otherwise it returns cleanly.

## Configuration

Edit `ChangeReleaseRuleConstants`:

- `RELEASED_STATE_NAMES` — set to match your lifecycle template (default:
  `RELEASED`, `PRODUCTION`, `APPROVED`).
- `VALIDATE_FULL_BOM` — `false` = immediate children only (default);
  `true` = walk the entire multi-level BOM.
- `ALLOW_CHILD_IN_SAME_CHANGE` — `true` (default) lets a parent + its children
  be released together in one change.
- `MAX_BOM_DEPTH` — recursion guard for full-BOM mode.

## Deployment

1. Compile against your Windchill `codebase` (the classes use only standard
   `wt.*` APIs). Place the resulting classes/jar on the methodserver classpath
   (e.g. `codebase/WEB-INF/lib` or your site jar).

   ```bash
   # example — point -cp at your Windchill codebase
   javac -cp "$WT_HOME/codebase:$WT_HOME/codebase/WEB-INF/lib/*" \
         -d build src/ext/windchill/change/rules/*.java
   ```

2. **Pick an integration point:**
   - *Workflow (recommended):* add an expression robot to the change-release
     activity using `workflow/ChangeReleaseChildParentExpressionRobot.txt`.
   - *Event listener (defense in depth):* register
     `ChangeReleaseRuleListener` via `conf/wt.properties.snippet`.

3. Restart the methodserver (or re-publish the workflow template) and test with
   a change that releases an assembly whose child is still In Work — the release
   should be blocked with the violation message.

## Notes / version compatibility

- The change-navigation row shapes returned by `getChangeablesAfter` and
  `getUsesWTParts` are normalised defensively in the code, so the rule tolerates
  the minor API differences across Windchill 11.x / 12.x / 13.x.
- The lifecycle pre-promote event key in `ChangeReleaseRuleListener` is resolved
  reflectively with a string fallback; confirm the correct key for your release
  if you use the listener variant.
