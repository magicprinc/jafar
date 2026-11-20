package io.jafar.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Custom byte buffer interface for efficient reading of JFR data.
 *
 * <p>This interface provides a unified abstraction for reading binary data from JFR recordings,
 * supporting both direct memory mapping and spliced reading for large files.
 */
public interface CustomByteBuffer {
  /**
   * Wraps a byte array with the same byte-order semantics as a memory-mapped JFR file.
   *
   * @param bytes the byte array to wrap
   * @return a custom byte buffer wrapping the array
   */
  static CustomByteBuffer wrap(byte[] bytes) {
    return new ByteBufferWrapper(ByteBuffer.wrap(bytes));
  }

  /**
   * Maps a file path to a custom byte buffer.
   *
   * @param channel the file path to map
   * @return a custom byte buffer for the file
   * @throws IOException if an I/O error occurs during mapping
   */
  static CustomByteBuffer map(Path channel) throws IOException {
    return map(channel, Integer.MAX_VALUE);
  }

  /**
   * Maps a file path to a custom byte buffer with a specified splice size.
   *
   * @param path the file path to map
   * @param spliceSize the maximum size for a single mapped buffer
   * @return a custom byte buffer for the file
   * @throws IOException if an I/O error occurs during mapping
   */
  static CustomByteBuffer map(Path path, int spliceSize) throws IOException {
    long size = Files.size(path);
    if (size > spliceSize) {
      return new SplicedMappedByteBuffer(path, spliceSize);
    } else {
      try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
          FileChannel channel = raf.getChannel()) {
        return new ByteBufferWrapper(channel.map(FileChannel.MapMode.READ_ONLY, 0, size));
      }
    }
  }

  /**
   * Maps a file with an explicitly owned lifetime. Closing the returned buffer releases its
   * mapping; slices borrow that lifetime and must not be accessed after the owner is closed.
   * Callers must externally synchronize reads and close across threads.
   *
   * @param path the file to map
   * @param spliceSize maximum size of each mapped segment
   * @return the owning buffer
   * @throws IllegalArgumentException if spliceSize is not positive
   * @throws IOException if the file cannot be mapped
   */
  static CustomByteBuffer mapScoped(Path path, int spliceSize) throws IOException {
    if (spliceSize <= 0) {
      throw new IllegalArgumentException("spliceSize must be positive");
    }
    long size = Files.size(path);
    Arena arena = Arena.ofShared();
    try {
      if (size > spliceSize) {
        return new SplicedMappedByteBuffer(path, spliceSize, arena);
      }
      try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
          FileChannel channel = file.getChannel()) {
        return new ByteBufferWrapper(
            channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena).asByteBuffer(), arena);
      }
    } catch (IOException | RuntimeException | Error failure) {
      try {
        arena.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /**
   * Creates a slice of this buffer from the current position to the end.
   *
   * @return a new custom byte buffer representing the slice
   */
  CustomByteBuffer slice();

  /**
   * Creates a slice of this buffer with the specified position and length.
   *
   * @param pos the starting position for the slice
   * @param len the length of the slice
   * @return a new custom byte buffer representing the slice
   */
  CustomByteBuffer slice(long pos, long len);

  /**
   * Sets the byte order for this buffer.
   *
   * @param bigEndian the byte order to set
   * @return this buffer for method chaining
   */
  CustomByteBuffer order(ByteOrder bigEndian);

  /**
   * Gets the current byte order of this buffer.
   *
   * @return the current byte order
   */
  ByteOrder order();

  /**
   * Checks if this buffer is using the native byte order.
   *
   * @return true if using native byte order, false otherwise
   */
  boolean isNativeOrder();

  /**
   * Sets the position of this buffer.
   *
   * @param position the new position
   */
  void position(long position);

  /**
   * Gets the current position of this buffer.
   *
   * @return the current position
   */
  long position();

  /**
   * Gets the number of remaining bytes in this buffer.
   *
   * @return the number of remaining bytes
   */
  long remaining();

  /**
   * Reads bytes into the specified buffer.
   *
   * @param buffer the destination buffer
   * @param offset the starting offset in the destination buffer
   * @param length the number of bytes to read
   */
  void get(byte[] buffer, int offset, int length);

  /**
   * Reads a single byte from this buffer.
   *
   * @return the byte value
   */
  byte get();

  /**
   * Reads a short value from this buffer.
   *
   * @return the short value
   */
  short getShort();

  /**
   * Reads an int value from this buffer.
   *
   * @return the int value
   */
  int getInt();

  /**
   * Reads a float value from this buffer.
   *
   * @return the float value
   */
  float getFloat();

  /**
   * Reads a double value from this buffer.
   *
   * @return the double value
   */
  double getDouble();

  /** Marks the current position in this buffer. */
  void mark();

  /** Resets the position to the previously marked position. */
  void reset();

  /**
   * Reads a long value from this buffer.
   *
   * @return the long value
   */
  long getLong();

  /**
   * Returns the total number of bytes in this buffer.
   *
   * @return the buffer limit
   */
  long limit();

  /**
   * Reads a single byte at the given absolute offset without changing the position.
   *
   * @param offset the absolute byte offset
   * @return the byte value
   */
  byte get(long offset);

  /**
   * Reads an int value at the given absolute offset without changing the position.
   *
   * @param offset the absolute byte offset
   * @return the int value
   */
  int getInt(long offset);

  /**
   * Reads a long value at the given absolute offset without changing the position.
   *
   * @param offset the absolute byte offset
   * @return the long value
   */
  long getLong(long offset);

  /**
   * Releases any resources held by this buffer.
   *
   * @throws IOException if an I/O error occurs
   */
  void close() throws IOException;

  /** Wrapper implementation backed by a mapped or array-based {@link ByteBuffer}. */
  class ByteBufferWrapper implements CustomByteBuffer {
    private final ByteBuffer delegate;
    private final boolean nativeOrder;
    private final Arena arena;

    /**
     * Constructs a new ByteBufferWrapper with the specified MappedByteBuffer.
     *
     * @param delegate the MappedByteBuffer to wrap
     */
    public ByteBufferWrapper(MappedByteBuffer delegate) {
      this((ByteBuffer) delegate);
    }

    public ByteBufferWrapper(ByteBuffer delegate) {
      this(delegate, null);
    }

    private ByteBufferWrapper(ByteBuffer delegate, Arena arena) {
      this.delegate = delegate;
      this.arena = arena;
      this.nativeOrder = delegate.order() == ByteOrder.nativeOrder();
      delegate.order(ByteOrder.nativeOrder());
    }

    @Override
    public boolean isNativeOrder() {
      return nativeOrder;
    }

    @Override
    public CustomByteBuffer slice(long pos, long len) {
      return new ByteBufferWrapper(delegate.slice((int) pos, (int) len));
    }

    @Override
    public CustomByteBuffer slice() {
      return new ByteBufferWrapper(delegate.slice());
    }

    @Override
    public CustomByteBuffer order(ByteOrder order) {
      delegate.order(order);
      return this;
    }

    @Override
    public ByteOrder order() {
      return delegate.order();
    }

    @Override
    public void position(long position) {
      delegate.position((int) position);
      //            this.position = (int) position;
    }

    @Override
    public long position() {
      return delegate.position(); // position;
    }

    @Override
    public long remaining() {
      return delegate.remaining(); // length - position;
    }

    @Override
    public void get(byte[] buffer, int offset, int length) {
      delegate.get(buffer, offset, length);
      //            delegate.get(position, buffer, offset, length);
      //            position += length;
    }

    @Override
    public byte get() {
      return delegate.get();
      //            return delegate.get(position++);
    }

    @Override
    public short getShort() {
      return delegate.getShort();
      //            short s = delegate.getShort(position);
      //            position += 2;
      //            return s;
    }

    @Override
    public int getInt() {
      return delegate.getInt();
      //            int i = delegate.getInt(position);
      //            position += 4;
      //            return i;
    }

    @Override
    public float getFloat() {
      return delegate.getFloat();
      //            float f = delegate.getFloat(position);
      //            position += 4;
      //            return f;
    }

    @Override
    public double getDouble() {
      return delegate.getDouble();
      //            double d = delegate.getDouble(position);
      //            position += 8;
      //            return d;
    }

    @Override
    public long getLong() {
      return delegate.getLong();
      //            long l = delegate.getLong(position);
      //            position += 8;
      //            return l;
    }

    @Override
    public void mark() {
      delegate.mark();
      //            mark = position;
    }

    @Override
    public void reset() {
      delegate.reset();
      //            if (mark > -1) {
      //                position = mark;
      //            }
    }

    @Override
    public long limit() {
      return delegate.limit();
    }

    @Override
    public byte get(long offset) {
      return delegate.get((int) offset);
    }

    @Override
    public int getInt(long offset) {
      return delegate.getInt((int) offset);
    }

    @Override
    public long getLong(long offset) {
      return delegate.getLong((int) offset);
    }

    @Override
    public synchronized void close() throws IOException {
      if (arena != null && arena.scope().isAlive()) {
        arena.close();
      }
    }
  }
}
