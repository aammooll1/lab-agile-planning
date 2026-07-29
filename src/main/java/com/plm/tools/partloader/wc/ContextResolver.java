package com.plm.tools.partloader.wc;

import java.util.HashMap;
import java.util.Map;

import com.plm.tools.partloader.util.LoaderException;
import com.plm.tools.partloader.util.LoaderLog;

import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.folder.Folder;
import wt.folder.FolderHelper;
import wt.folder.SubFolder;
import wt.inf.container.WTContainer;
import wt.inf.container.WTContainerRef;
import wt.inf.library.WTLibrary;
import wt.org.WTOrganization;
import wt.pdmlink.PDMLinkProduct;
import wt.query.QuerySpec;
import wt.query.SearchCondition;
import wt.util.WTException;
import wt.vc.views.View;
import wt.vc.views.ViewHelper;

/**
 * Resolves and caches the Windchill context objects a part needs: container, folder, view.
 *
 * <p>Caching is not a micro-optimisation here. A 40 000 row load in a single product
 * context would otherwise execute 120 000 identical queries against the container, folder
 * and view tables. On a production database shared with interactive users that is the
 * difference between a loader that runs in the maintenance window and one that gets killed
 * by the DBA. The cache is per-run and per-instance, so it cannot go stale across runs.</p>
 */
public class ContextResolver {

    public static final String TYPE_PRODUCT = "PRODUCT";
    public static final String TYPE_LIBRARY = "LIBRARY";
    public static final String TYPE_ORG = "ORG";

    private final boolean autoCreateFolders;

    private final Map<String, WTContainerRef> containerCache = new HashMap<String, WTContainerRef>();
    private final Map<String, Folder> folderCache = new HashMap<String, Folder>();
    private final Map<String, View> viewCache = new HashMap<String, View>();

    public ContextResolver(boolean autoCreateFolders) {
        this.autoCreateFolders = autoCreateFolders;
    }

    // ---------------------------------------------------------------- container

    /**
     * @param type PRODUCT, LIBRARY or ORG
     * @param name the container name exactly as shown in the Windchill UI
     */
    public WTContainerRef resolveContainer(String type, String name) throws WTException, LoaderException {
        String key = type + "|" + name;
        WTContainerRef cached = containerCache.get(key);
        if (cached != null) {
            return cached;
        }

        Class<?> containerClass;
        if (TYPE_PRODUCT.equals(type)) {
            containerClass = PDMLinkProduct.class;
        } else if (TYPE_LIBRARY.equals(type)) {
            containerClass = WTLibrary.class;
        } else if (TYPE_ORG.equals(type)) {
            containerClass = WTOrganization.class;
        } else {
            throw new LoaderException("Unsupported container type '" + type
                    + "'. Expected one of " + TYPE_PRODUCT + ", " + TYPE_LIBRARY + ", " + TYPE_ORG);
        }

        QuerySpec qs = new QuerySpec(containerClass);
        qs.appendWhere(new SearchCondition(containerClass, WTContainer.NAME, SearchCondition.EQUAL, name),
                new int[] { 0 });
        QueryResult qr = PersistenceHelper.manager.find(qs);

        if (!qr.hasMoreElements()) {
            throw new LoaderException("Container not found: " + type + " '" + name + "'");
        }
        WTContainer container = (WTContainer) qr.nextElement();
        if (qr.hasMoreElements()) {
            // Product and library names are unique per organisation, not globally. If a
            // multi-org installation returns more than one hit the load file is ambiguous
            // and must be corrected rather than guessed at.
            throw new LoaderException("Container name '" + name + "' is ambiguous - more than one "
                    + type + " matches. Qualify the load file or run per organisation.");
        }

        WTContainerRef ref = WTContainerRef.newWTContainerRef(container);
        containerCache.put(key, ref);
        LoaderLog.debug("Resolved container " + key);
        return ref;
    }

    // ---------------------------------------------------------------- folder

    public Folder resolveFolder(WTContainerRef containerRef, String folderPath) throws WTException, LoaderException {
        String key = containerRef.getId() + "|" + folderPath;
        Folder cached = folderCache.get(key);
        if (cached != null) {
            return cached;
        }

        Folder folder = FolderHelper.service.getFolder(folderPath, containerRef);
        if (folder == null) {
            if (!autoCreateFolders) {
                throw new LoaderException("Folder '" + folderPath + "' does not exist in the target container. "
                        + "Create it in the UI, or enable loader.folder.autoCreate.");
            }
            folder = createFolderPath(containerRef, folderPath);
        }
        folderCache.put(key, folder);
        return folder;
    }

    /**
     * Creates missing intermediate folders.
     *
     * <p>Deliberately opt-in. Auto-creating folders is convenient during migration dry runs
     * and dangerous in production: a typo in the load file silently produces a parallel
     * folder tree, and folder structure usually carries access-control rules that the new
     * folder will not inherit as intended.</p>
     */
    private Folder createFolderPath(WTContainerRef containerRef, String folderPath) throws WTException, LoaderException {
        String[] segments = folderPath.split("/");
        StringBuilder built = new StringBuilder();
        Folder current = null;

        for (String segment : segments) {
            if (segment.length() == 0) {
                continue;
            }
            built.append('/').append(segment);
            Folder existing = FolderHelper.service.getFolder(built.toString(), containerRef);
            if (existing != null) {
                current = existing;
                continue;
            }
            if (current == null) {
                throw new LoaderException("Cabinet '" + built + "' does not exist and cabinets are never "
                        + "auto-created by this loader");
            }
            SubFolder sub = SubFolder.newSubFolder(segment, current);
            current = (Folder) PersistenceHelper.manager.store(sub);
            LoaderLog.warn("Auto-created folder " + built);
        }
        if (current == null) {
            throw new LoaderException("Could not resolve or create folder path '" + folderPath + "'");
        }
        return current;
    }

    // ---------------------------------------------------------------- view

    public View resolveView(String viewName) throws WTException, LoaderException {
        View cached = viewCache.get(viewName);
        if (cached != null) {
            return cached;
        }
        View view = ViewHelper.service.getView(viewName);
        if (view == null) {
            throw new LoaderException("View '" + viewName + "' is not defined. Check Utilities > View Management "
                    + "(views are case sensitive; the OOTB names are 'Design' and 'Manufacturing').");
        }
        viewCache.put(viewName, view);
        return view;
    }

    /** Clears all caches. Called between phases so BOM resolution cannot use stale folders. */
    public void clear() {
        containerCache.clear();
        folderCache.clear();
        viewCache.clear();
    }
}
