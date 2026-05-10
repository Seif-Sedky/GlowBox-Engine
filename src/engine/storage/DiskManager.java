package engine.storage;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads and writes fixed-size pages to binary .db files on disk.
 *
 * Supports two kinds of files:
 *   Table files  — identified by int tableId, stored as "table_<id>.db"
 *   Named files  — identified by a String name, stored as "<name>.db"
 *                  Used by index structures (e.g. "idx_employees_salary.db")
 *
 * All I/O goes through RandomAccessFile with seek + read/write.
 * One file handle per file, kept open for the session lifetime.
 */
public class DiskManager {

    private final Path dataDirectory;

    /** Open file handles keyed by filename stem (without .db). */
    private final Map<String, RandomAccessFile> openFiles = new HashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public DiskManager(Path dataDirectory) throws IOException {
        this.dataDirectory = dataDirectory;
        Files.createDirectories(dataDirectory);
    }

    // -------------------------------------------------------------------------
    // Table file API  (used by BufferPoolManager and HeapFile)
    // -------------------------------------------------------------------------

    public Page readPage(PageId pageId) throws IOException {
        return readPageInternal(tableKey(pageId.getTableId()), pageId.getPageNumber(), pageId);
    }

    public void writePage(Page page) throws IOException {
        writePageInternal(tableKey(page.getPageId().getTableId()), page);
    }

    public Page allocatePage(int tableId) throws IOException {
        return allocatePageInternal(tableKey(tableId), tableId);
    }

    public int pageCount(int tableId) throws IOException {
        return namedPageCount(tableKey(tableId));
    }

    public void createTableFile(int tableId) throws IOException {
        createFileInternal(tableKey(tableId));
    }

    public void deleteTableFile(int tableId) throws IOException {
        closeHandle(tableKey(tableId));
        Files.deleteIfExists(filePath(tableKey(tableId)));
    }

    // -------------------------------------------------------------------------
    // Named file API  (used directly by index structures)
    // -------------------------------------------------------------------------

    /**
     * Creates a new empty .db file with the given name stem.
     * e.g. name="idx_employees_salary" → idx_employees_salary.db
     */
    public void createNamedFile(String name) throws IOException {
        createFileInternal(name);
    }

    /** Reads one page from a named file by its page number. */
    public Page readNamedPage(String name, int pageNumber) throws IOException {
        PageId id = namedPageId(pageNumber);
        return readPageInternal(name, pageNumber, id);
    }

    /** Writes a page back to a named file using the page's own page number. */
    public void writeNamedPage(String name, Page page) throws IOException {
        writePageInternal(name, page);
    }

    /**
     * Appends a blank page to a named file and returns it.
     * The returned page has a synthetic PageId(0, newPageNumber).
     */
    public Page allocateNamedPage(String name) throws IOException {
        RandomAccessFile file = getHandle(name);
        int newPageNumber = (int)(file.length() / Page.PAGE_SIZE);
        file.seek(file.length());
        file.write(new byte[Page.PAGE_SIZE]);

        PageId id   = namedPageId(newPageNumber);
        Page   page = new Page(id);
        writePageInternal(name, page);
        return page;
    }

    /** Returns the number of pages in a named file (0 if file does not exist). */
    public int namedPageCount(String name) throws IOException {
        Path p = filePath(name);
        if (!Files.exists(p)) return 0;
        return (int)(Files.size(p) / Page.PAGE_SIZE);
    }

    /** Deletes a named file and closes its handle. */
    public void deleteNamedFile(String name) throws IOException {
        closeHandle(name);
        Files.deleteIfExists(filePath(name));
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void shutdown() {
        for (RandomAccessFile raf : openFiles.values()) {
            try { raf.close(); } catch (IOException ignored) {}
        }
        openFiles.clear();
    }

    // -------------------------------------------------------------------------
    // Private shared implementation
    // -------------------------------------------------------------------------

    private Page readPageInternal(String key, int pageNumber, PageId id) throws IOException {
        RandomAccessFile file   = getHandle(key);
        long             offset = (long) pageNumber * Page.PAGE_SIZE;

        if (offset + Page.PAGE_SIZE > file.length())
            throw new IOException("Page " + pageNumber + " does not exist in file " + key + ".db");

        byte[] buf = new byte[Page.PAGE_SIZE];
        file.seek(offset);
        file.readFully(buf);
        return new Page(id, buf);
    }

    private void writePageInternal(String key, Page page) throws IOException {
        RandomAccessFile file   = getHandle(key);
        long             offset = (long) page.getPageId().getPageNumber() * Page.PAGE_SIZE;
        file.seek(offset);
        file.write(page.getData());
        page.clearDirty();
    }

    private Page allocatePageInternal(String key, int tableId) throws IOException {
        RandomAccessFile file         = getHandle(key);
        int              newPageNum   = (int)(file.length() / Page.PAGE_SIZE);
        PageId           id           = new PageId(tableId, newPageNum);

        file.seek(file.length());
        file.write(new byte[Page.PAGE_SIZE]);

        Page page = new Page(id);
        SlottedPageLayout.initPage(page);
        writePageInternal(key, page);
        return page;
    }

    private void createFileInternal(String key) throws IOException {
        Path p = filePath(key);
        if (Files.exists(p))
            throw new IOException("File already exists: " + p);
        Files.createFile(p);
    }

    private RandomAccessFile getHandle(String key) throws IOException {
        if (!openFiles.containsKey(key)) {
            Path p = filePath(key);
            if (!Files.exists(p))
                throw new IOException("No .db file for '" + key
                    + "'. Call createTableFile() or createNamedFile() first.");
            openFiles.put(key, new RandomAccessFile(p.toFile(), "rw"));
        }
        return openFiles.get(key);
    }

    private void closeHandle(String key) throws IOException {
        RandomAccessFile raf = openFiles.remove(key);
        if (raf != null) raf.close();
    }

    private Path   filePath(String key)      { return dataDirectory.resolve(key + ".db"); }
    private String tableKey(int tableId)     { return "table_" + tableId; }

    /**
     * Named file pages use tableId=0 as a placeholder since they bypass
     * the BufferPool and are never cached by PageId in a frame map.
     */
    private PageId namedPageId(int pageNumber) { return new PageId(0, pageNumber); }
}