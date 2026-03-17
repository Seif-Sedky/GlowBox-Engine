package engine.storage;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads and writes fixed-size pages to binary .db files on disk.
 *
 * One .db file exists per table (and per index). The DiskManager
 * owns the file handles and is the only class in the engine that
 * performs actual I/O. Every other layer talks to the BufferPool,
 * which calls down here only on a cache miss or a dirty eviction.
 *
 *
 *You maintain the connection open using the map, only closed when shutdown
 *You are storing pages within a file, making pages as seperate files will result in too many open 
 *files which will make the OS crash your database
 *
 *Seek is not like buffered reader, you can take your needle and go to wherever you need in the file 
 *
 *
 * Simplicity choices:
 *   - One RandomAccessFile per table, kept open for the lifetime of
 *     the session. No connection pooling or handle recycling needed
 *     for a single-user tool.
 *   - Page N lives at byte offset N * PAGE_SIZE. No header file,
 *     no extent map — the file length divided by PAGE_SIZE tells you
 *     how many pages exist.
 *   - No write-ahead log, no checksums. Crash recovery is out of scope.
 */
public class DiskManager {

    /** Directory where all .db files are stored. */
    private final Path dataDirectory;

    /**
     * Open file handles, keyed by tableId.
     * Opened lazily on first access, closed on shutdown().
     */
    private final Map<Integer, RandomAccessFile> openFiles = new HashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param dataDirectory path to the folder that holds all .db files.
     *                      Created automatically if it does not exist.
     */
    public DiskManager(Path dataDirectory) throws IOException {
        this.dataDirectory = dataDirectory;
        Files.createDirectories(dataDirectory);
    }

    // -------------------------------------------------------------------------
    // Core I/O
    // -------------------------------------------------------------------------

    /**
     * Reads one page from disk into a new Page object.
     *
     * @param pageId identifies which file and which offset to read from
     * @return a Page loaded with the bytes from disk, dirty flag cleared
     */
    public Page readPage(PageId pageId) throws IOException {
        RandomAccessFile file = getFile(pageId.getTableId());
        long offset = pageId.fileOffset();

        if (offset + Page.PAGE_SIZE > file.length())
            throw new IOException("Page " + pageId + " does not exist on disk.");

        byte[] buffer = new byte[Page.PAGE_SIZE];
        file.seek(offset);
        file.readFully(buffer);

        return new Page(pageId, buffer);
    }

    /**
     * Writes a page's bytes to its corresponding position in the file.
     * Clears the dirty flag on the page after a successful write.
     *
     * @param page the page to flush — its PageId determines the file and offset
     */
    public void writePage(Page page) throws IOException {
        RandomAccessFile file = getFile(page.getPageId().getTableId());
        file.seek(page.getPageId().fileOffset());
        file.write(page.getData());
        page.clearDirty();
    }

    /**
     * Allocates a new blank page at the end of the file. (add a page)
     *
     * Extends the file by exactly PAGE_SIZE bytes (written as zeros),
     * initialises a slotted-page header on it, and returns the new page
     * ready for use.
     *
     * @param tableId the table whose file should be extended
     * @return a freshly initialised Page with the next available page number
     */
    public Page allocatePage(int tableId) throws IOException {
        RandomAccessFile file = getFile(tableId);

        int newPageNumber = (int) (file.length() / Page.PAGE_SIZE);
        PageId newId      = new PageId(tableId, newPageNumber);

        // Extend the file with a zero-filled page
        file.seek(file.length());
        file.write(new byte[Page.PAGE_SIZE]);

        // Initialise the slotted-page header in memory and flush immediately
        Page page = new Page(newId);
        SlottedPageLayout.initPage(page);
        writePage(page);

        return page;
    }

    // -------------------------------------------------------------------------
    // File-level operations
    // -------------------------------------------------------------------------

 
    public int pageCount(int tableId) throws IOException {
        Path filePath = filePathFor(tableId);
        if (!Files.exists(filePath)) return 0;
        return (int) (Files.size(filePath) / Page.PAGE_SIZE);
    }


    public void createTableFile(int tableId) throws IOException {
        Path filePath = filePathFor(tableId);
        if (Files.exists(filePath))
            throw new IOException("File already exists for tableId " + tableId + ": " + filePath);
        Files.createFile(filePath);
    }

    /**
     * Deletes the .db file for the given table and closes its handle.
     * Used by DROP TABLE.
     */
    public void deleteTableFile(int tableId) throws IOException {
        closeFile(tableId);
        Files.deleteIfExists(filePathFor(tableId));
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Closes all open file handles.
     * Call this on application shutdown.
     */
    public void shutdown() {
        for (Map.Entry<Integer, RandomAccessFile> entry : openFiles.entrySet()) {
            try { entry.getValue().close(); }
            catch (IOException ignored) {}
        }
        openFiles.clear();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Returns the open file handle for a table, opening it lazily if needed. */
    private RandomAccessFile getFile(int tableId) throws IOException {
        if (!openFiles.containsKey(tableId)) { //check if in stored open connections
            Path filePath = filePathFor(tableId);
            if (!Files.exists(filePath))
                throw new IOException("No .db file found for tableId " + tableId
                    + ". Call createTableFile() first.");
            openFiles.put(tableId, new RandomAccessFile(filePath.toFile(), "rw"));
        }
        return openFiles.get(tableId);
    }

    private void closeFile(int tableId) throws IOException {
        RandomAccessFile raf = openFiles.remove(tableId);
        if (raf != null) raf.close();
    }

    /** Table 3 lives at <dataDirectory>/table_3.db */
    private Path filePathFor(int tableId) {
        return dataDirectory.resolve("table_" + tableId + ".db");
    }
}