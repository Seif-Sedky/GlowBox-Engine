package engine.storage;

/**
 * A fixed-size, in-memory representation of one disk page.
 *
 * Page owns a raw byte buffer and a dirty flag, and knows nothing about what is
 * stored inside it. Interpretation of the bytes is left entirely to
 * SlottedPageLayout or other
 * 
 * This clean separation means the same Page type flows through every layer: the
 * buffer pool caches it, the disk manager reads/writes it, and every access
 * method operates on it without any of them needing to agree on layout.
 *
 * Size is deliberately small (256 bytes) so that splits and buffer evictions
 * happen frequently and produce interesting visualizations.
 */
public final class Page {

	/** Every page in the engine is exactly this many bytes. */
	public static final int PAGE_SIZE = 256;
	private final PageId pageId;
	private final byte[] data;
	/**
	 * True when in-memory content differs from what is on disk. The buffer pool
	 * checks this flag before deciding whether a write-back is needed on eviction.
	 */
	private boolean dirty;

	public Page(PageId pageId) {
		this.pageId = pageId;
		this.data = new byte[PAGE_SIZE];
		this.dirty = false;
	}

	/**
	 * Wraps a byte array read from disk into a Page.
	 */
	public Page(PageId pageId, byte[] source) {
		if (source.length != PAGE_SIZE)
			throw new IllegalArgumentException(
					"Source buffer must be exactly " + PAGE_SIZE + " bytes, got " + source.length);
		this.pageId = pageId;
		this.data = new byte[PAGE_SIZE];
		System.arraycopy(source, 0, this.data, 0, PAGE_SIZE);
		this.dirty = false;
	}

	/**
	 * Callers that write to this array MUST call markDirty() afterwards. Returning
	 * the array directly (rather than a copy) avoids an allocation on every single
	 * read, which matters inside tight scan loops.
	 */
	public byte[] getData() {
		return data;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void markDirty() {
		dirty = true;
	}

	public void clearDirty() {
		dirty = false;
	} // called by DiskManager after flush

	public PageId getPageId() {
		return pageId;
	}

	public String toString() {
		return "Page{id=" + pageId + ", dirty=" + dirty + "}";
	}

	/*
	 * 1. The "Dirty Flag" Guarantee (Data Safety) By forcing the rest of the engine
	 * to go through your write methods, you guarantee that dirty = true is
	 * triggered every single time a byte changes. If you just exposed the raw
	 * byte[] array, other classes could modify data silently, and the Buffer Pool
	 * would throw the page in the trash without saving it to the disk.
	 * 
	 * 
	 * 2. Garbage Collector Immunity (Memory Speed) Methods like readInt() do math
	 * directly on the existing bytes and return a primitive. If you used a generic
	 * readBytes() method for everything, Java would be forced to create millions of
	 * tiny 4-byte arrays every time you searched your B+ Tree. The Garbage
	 * Collector would constantly freeze your engine to clean up the mess.
	 */

	public byte readByte(int offset) {
		checkBounds(offset, 1);
		return data[offset];
	}

	public void writeByte(int offset, byte value) {
		checkBounds(offset, 1);
		data[offset] = value;
		dirty = true;
	}

	public short readShort(int offset) {
		checkBounds(offset, 2);
		return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
	}

	public void writeShort(int offset, short value) {
		checkBounds(offset, 2);
		data[offset] = (byte) ((value >> 8) & 0xFF);
		data[offset + 1] = (byte) (value & 0xFF);
		dirty = true;
	}

	public int readInt(int offset) {
		checkBounds(offset, 4);
		return ((data[offset] & 0xFF) << 24) // Grab byte 1, cut the garbage, push to the front
				| ((data[offset + 1] & 0xFF) << 16) // Grab byte 2, cut the garbage, push to position 2
				| ((data[offset + 2] & 0xFF) << 8) // Grab byte 3, cut the garbage, push to position 3
				| (data[offset + 3] & 0xFF); // Grab byte 4, cut the garbage, drop it at the end
	}

	public void writeInt(int offset, int value) {
		checkBounds(offset, 4);
		data[offset] = (byte) ((value >> 24) & 0xFF);
		data[offset + 1] = (byte) ((value >> 16) & 0xFF);
		data[offset + 2] = (byte) ((value >> 8) & 0xFF);
		data[offset + 3] = (byte) (value & 0xFF);
		dirty = true;
	}

	public void writeBytes(int destOffset, byte[] src, int srcOffset, int length) {
		checkBounds(destOffset, length);
		System.arraycopy(src, srcOffset, data, destOffset, length);
		dirty = true;
	}

	public void readBytes(int srcOffset, byte[] dest, int destOffset, int length) {
		checkBounds(srcOffset, length);
		System.arraycopy(data, srcOffset, dest, destOffset, length);
	}

	private void checkBounds(int offset, int length) {
		if (offset < 0 || offset + length > PAGE_SIZE)
			throw new IndexOutOfBoundsException(
					"Access [" + offset + ", " + (offset + length) + ") out of page bounds [0, " + PAGE_SIZE + ")");
	}

}