package io.jafar.hdump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.internal.HprofReader;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapDumpLifecycleTest {
  @TempDir Path directory;

  @Test
  @DisplayName(
      "Дамп создан в одном thread, а объект прочитан и дамп закрыт в другом; файл удаляется")
  void dumpCanBeReadAndClosedOnAnotherThread() throws Exception {
    Path path = fixture();
    HeapDump dump = HeapDumpParser.parse(path);

    FutureTask<HolderData> queryAndClose =
        new FutureTask<>(
            () -> {
              HeapObject holder = dump.getObjectById(1000).orElseThrow();
              long[] refs =
                  holder.getOutboundReferences().mapToLong(HeapObject::getId).sorted().toArray();
              HolderData data =
                  new HolderData(holder.getId(), holder.getHeapClass().getName(), refs);
              dump.close();
              return data;
            });
    Thread worker = new Thread(queryAndClose, "heap-dump-lifecycle-worker");
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
          dump.close();
        }) {
      worker.start();
      HolderData data = queryAndClose.get(10, TimeUnit.SECONDS);
      assertEquals(1000, data.id());
      assertEquals("Holder", data.className());
      assertArrayEquals(new long[] {2000, 2001}, data.refs());
      // The dump was closed by the worker; deletion proves the mapping was released.
      Files.delete(path);
    }
  }

  private record HolderData(long id, String className, long[] refs) {}

  @Test
  @DisplayName("После close heap dump можно удалить без ожидания GC")
  void closingDumpReleasesFile() throws IOException {
    Path path = fixture();
    try (HeapDump dump = HeapDumpParser.parse(path)) {
      assertEquals(3, dump.getObjectCount());
    }
    Files.delete(path);
  }

  @Test
  @DisplayName("Ошибка callback сохраняется, а открытый heap dump освобождается")
  void callbackFailureReleasesFileAndPropagates() throws IOException {
    Path path = fixture();
    IllegalStateException failure = new IllegalStateException("callback failure");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                HeapDumpParser.parse(
                    path,
                    (progress, message) -> {
                      if (message.equals("Parsing")) {
                        throw failure;
                      }
                    }));
    assertSame(failure, thrown);
    Files.delete(path);
  }

  @Test
  @DisplayName("Ошибка заголовка освобождает файл при незавершённом открытии")
  void invalidHeaderReleasesFile() throws IOException {
    Path path = directory.resolve("invalid.hprof");
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
      out.write("JAVA PROFILE 1.0.2".getBytes(StandardCharsets.US_ASCII));
      out.writeByte(0);
      out.writeInt(3);
      out.writeLong(0);
    }
    assertThrows(IOException.class, () -> HeapDumpParser.parse(path));
    Files.delete(path);
  }

  @Test
  @DisplayName("Error из callback распространяется, а файл освобождается")
  void callbackErrorReleasesFileAndPropagates() throws IOException {
    Path path = fixture();
    AssertionError failure = new AssertionError("callback error");
    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () ->
                HeapDumpParser.parse(
                    path,
                    (progress, message) -> {
                      if (message.equals("Parsing")) {
                        throw failure;
                      }
                    }));
    assertSame(failure, thrown);
    Files.delete(path);
  }

  @Test
  @DisplayName("Закрытие view сохраняет доступ владельцу, а закрытие владельца освобождает файл")
  void closingBorrowedViewDoesNotCloseOwner() throws IOException {
    Path path = fixture();
    try (HprofReader owner = new HprofReader(path)) {
      HprofReader view = owner.createView();
      view.position(0);
      assertEquals('J', view.readU1());
      view.close();
      owner.position(0);
      assertEquals('J', owner.readU1());
    }
    Files.delete(path);
  }

  private Path fixture() throws IOException {
    Path path = directory.resolve("heap.hprof");
    SyntheticHeapDumpGenerator.generateA5InstanceFields(path);
    return path;
  }
}
