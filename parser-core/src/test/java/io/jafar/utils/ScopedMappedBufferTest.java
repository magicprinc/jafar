package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScopedMappedBufferTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {3, 64})
  @DisplayName("Срез не закрывает владельца, а close владельца освобождает файл")
  void ownerAndBorrowedSliceHaveDistinctLifetimes(int spliceSize) throws IOException {
    Path path = directory.resolve("data.bin");
    Files.write(path, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
    CustomByteBuffer owner = CustomByteBuffer.mapScoped(path, spliceSize);
    CustomByteBuffer slice = owner.slice(2, 5);
    try {
      for (int expected = 2; expected <= 6; expected++) {
        assertEquals(expected, slice.get());
      }
      slice.close();
      assertEquals(7, owner.get(7));
    } finally {
      owner.close();
    }
    owner.close();
    assertThrows(IllegalStateException.class, () -> slice.get(0));
    Files.delete(path);
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 64})
  @DisplayName("Владелец читается и закрывается в другом thread, после чего файл удаляется")
  void ownerCanBeReadAndClosedOnAnotherThread(int spliceSize) throws Exception {
    Path path = directory.resolve("cross-thread-" + spliceSize + ".bin");
    Files.write(path, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
    CustomByteBuffer owner = CustomByteBuffer.mapScoped(path, spliceSize);
    CustomByteBuffer borrowed = owner.slice(2, 5);

    FutureTask<int[]> readAndClose =
        new FutureTask<>(
            () -> {
              int[] values = new int[5];
              for (int i = 0; i < values.length; i++) {
                values[i] = borrowed.get() & 0xFF;
              }
              owner.close();
              return values;
            });
    Thread worker = new Thread(readAndClose, "scoped-map-worker");
    worker.setDaemon(true);
    try (AutoCloseable cleanup =
        () -> {
          worker.interrupt();
          try {
            worker.join(10_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
          }
          if (worker.isAlive()) {
            throw new IllegalStateException("worker did not terminate within 10 seconds");
          }
          owner.close();
        }) {
      worker.start();
      assertArrayEquals(new int[] {2, 3, 4, 5, 6}, readAndClose.get(10, TimeUnit.SECONDS));
      // The owner was closed on the worker; deletion proves its mapping was actually released.
      Files.delete(path);
    }
  }

  @Test
  @DisplayName("Пустой файл после close можно удалить")
  void emptyFileCanBeClosed() throws IOException {
    Path path = Files.createFile(directory.resolve("empty.bin"));
    CustomByteBuffer owner = CustomByteBuffer.mapScoped(path, 4);
    assertEquals(0, owner.limit());
    owner.close();
    Files.delete(path);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Неположительный размер сегмента отклоняется")
  void nonpositiveSpliceSizeIsRejected(int spliceSize) throws IOException {
    Path path = Files.createFile(directory.resolve("invalid.bin"));
    assertThrows(
        IllegalArgumentException.class, () -> CustomByteBuffer.mapScoped(path, spliceSize));
    Files.delete(path);
  }
}
