# API verification checklist

**Read this before your first build.**

This utility was written against the documented Windchill 12.1.x SDK but has **not been
compiled against a live 12.1.2 codebase**. Windchill's API is not published as a stable
public contract; signatures move between releases and PTC does not treat that as a breaking
change. So rather than pretend the code is verified, every call site whose exact signature
carries risk is listed here with an honest confidence tag and the fix if it does not compile.

Confidence tags:

- **[Certain]** — core API, stable for a decade, would be surprising to see change.
- **[Likely]** — strong recollection of the signature, consistent with how the API is used
  elsewhere, but worth a javadoc check.
- **[Guessing]** — the shape of the call is right; the exact class or method name may differ
  in your release.

The fastest way to check any of these is the javadoc shipped with your installation at
`$WT_HOME/codebase/wt/clients/library/javadoc`, or simply decompiling the class from
`$WT_HOME/codebase/WEB-INF/lib/wnc*.jar`.

---

## 1. Soft type assignment — **[Guessing]** — highest risk item

`src/main/java/com/plm/tools/partloader/wc/SoftTypeResolver.java`

```java
TypeIdentifier identifier = TypeIdentifierHelper.getTypeIdentifier(typeId);
TypeDefinitionReference reference = TypeIdentifierUtilityHelper.service.getTypeDefinitionReference(identifier);
part.setTypeDefinitionReference(reference);
```

The type-management API (`com.ptc.core.meta.*`) is the least stable area of the SDK and the
helper classes have been reorganised more than once. If these imports do not resolve, look
for the equivalents in `com.ptc.core.meta.common` and `com.ptc.core.meta.type.common`.

**Workaround if you cannot resolve it quickly:** leave the `softType` column empty. Every
part is then created as a plain `wt.part.WTPart` and everything else in the loader works.
That is a real limitation though, not a shrug — see the class comment for why subtypes
matter to OIR numbering and the attribute set.

## 2. Checkout return type — **[Likely]**

`wc/CheckoutUtil.java`

```java
CheckoutLink link = WorkInProgressHelper.service.checkout(part, checkoutFolder, note);
return (WTPart) link.getWorkingCopy();
```

Some releases return the `Workable` directly. The alternative is in a comment on the line
above. Only affects the update path; creation is unaffected.

## 3. Enumerated types — **[Likely]**

`wc/EnumResolver.java`

| Call | Note |
|---|---|
| `Source.toSource("make")` | [Likely] stable |
| `PartType.toPartType("separable")` | [Likely] — UI label is "Assembly Mode"; if `wt.part.PartType` does not exist look for `wt.part.AssemblyMode` |
| `Quantity.QuantityUnit.toQuantityUnit("ea")` | [Likely] — `QuantityUnit` is a nested class of `wt.part.Quantity`; if the import fails try top-level `wt.part.QuantityUnit` |
| `TraceCode.toTraceCode("untraced")` | [Likely] |
| `part.setDefaultTraceCode(traceCode)` | [Likely] — may be `setTraceCode` |

Also confirm the **internal** values in `wt/part/partResource.rbInfo` for your installation.
Customers extend these enumerations routinely and the loader must be given internal values,
never display labels.

## 4. Usage link quantity — **[Likely]**

`wc/BomBuilder.java`

```java
link.setAmount(row.getQuantity());
link.setUnit(unit);
```

If these are not present, the aggregate form is `link.setQuantity(Quantity.newQuantity(amount, unit))`.

## 5. Find number / line number on usage links — **not implemented**

`BomRow` parses `findNumber` and `lineNumber`, and `BomBuilder.unappliedColumnNote()`
reports loudly when they are populated, but nothing writes them. Whether they are hard
attributes on `WTPartUsageLink` or soft attributes depends on your data model, so guessing
would produce silent data loss.

To implement: if they are soft attributes, reuse `IbaWriter.apply(link, values, strict)` —
the link is a `Persistable` and the same adapter works. If hard, add the setters to
`BomBuilder` next to `setAmount`.

## 6. Navigation to existing usage links — **[Likely]**

`wc/BomBuilder.java`

```java
PersistenceHelper.manager.navigate(parent, WTPartUsageLink.USES_ROLE, WTPartUsageLink.class, false);
```

Confirm the fourth argument's meaning — `false` should return the **link** objects rather
than the far-side masters. If it returns masters, the cast to `WTPartUsageLink` throws
`ClassCastException` on the first assembly.

## 7. Dry-run IBA validation — **[Guessing]**, already guarded

`wc/IbaWriter.validate(...)` uses a `PersistableAdapter` constructor that takes a type
identifier string rather than an object. If that constructor does not exist, the method
catches `Throwable` and returns a message saying pre-validation is unavailable; the run
continues and attributes are still fully validated at commit time. Nothing breaks — you
simply lose one dry-run check.

## 8. Remaining lower-risk call sites — **[Likely]**

| File | Call |
|---|---|
| `PartFactory` | `part.setNumber(...)` / `part.setName(...)` on `WTPart` (delegate to the master) |
| `PartFactory` | `part.setContainerReference(WTContainerRef)` — alternative `setContainer(WTContainer)` |
| `PartFactory` | `part.setView(View)` — alternative `ViewHelper.assignToView(part, view)` after store |
| `PartFactory` | `FolderHelper.assignLocation((FolderEntry) part, folder)` |
| `ContextResolver` | `FolderHelper.service.getFolder(String, WTContainerRef)` |
| `ContextResolver` | `SubFolder.newSubFolder(String name, Folder parent)` — check argument order |
| `PartLookup` | `WTPartStandardConfigSpec.newWTPartStandardConfigSpec(View, State)` and `ConfigHelper.service.filteredIterationsOf(...)` |
| `CheckoutUtil` | `working.getPersistInfo().getCreator().getFullName()` |
| `LifecycleUtil` | `LifeCycleHelper.service.setLifeCycleState(LifeCycleManaged, State)` |
| `PartLoaderMain` | `RemoteMethodServer.invoke(method, class, target, Class[], Object[])` — argument **order** |

`PersistenceHelper.manager.store/modify/find`, `QuerySpec`, `SearchCondition`,
`wt.pom.Transaction` and `wt.method.RemoteAccess` are **[Certain]**.

---

## Suggested order of work

1. `ant -Dwt.home=... compile` and fix whatever does not resolve. Expect items 1–4.
2. Load **five rows** into a scratch product in DEV with `--dry-run`.
3. Same five rows for real. Open them in the UI and check number, name, folder, view,
   assembly mode, source, unit and every soft attribute against the source file.
4. Re-run the identical file. Every row must come back `SKIPPED`. If anything reports
   `CREATED` you have duplicate masters and the lookup in `PartLookup` is wrong — stop and
   fix that before going near a real data set.
5. Only then the BOM file, and check the structure in the Structure tab, not just the log.
