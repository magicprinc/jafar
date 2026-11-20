package io.jafar.hdump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.index.IndexFormat;
import io.jafar.hdump.index.IndexWriter;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Проверяет через публичный entry point, что indexed-разбор освобождает файлы индексов
 * детерминированно: после close, при повторном открытии и при сбоях.
 */
class IndexedHeapDumpCleanupTest {
  @TempDir Path directory;

  private static final ParserOptions INDEXED =
      ParserOptions.builder().parsingMode(HeapDumpParser.ParsingMode.INDEXED).build();

  @Test
  @DisplayName("После close indexed-дампа каталог индексов удаляется без ожидания GC")
  void closingIndexedDumpReleasesIndexFiles() throws IOException {
    Path heap = heap("heap.hprof");
    try (HeapDump dump = HeapDumpParser.parse(heap, INDEXED)) {
      assertTrue(dump.getObjectCount() > 0);
      assertTrue(Files.exists(indexDir(heap).resolve(IndexFormat.OBJECTS_INDEX_NAME)));
      assertTrue(Files.exists(indexDir(heap).resolve(IndexFormat.CLASSINSTANCES_DATA_INDEX_NAME)));
    }

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  @Test
  @DisplayName("Повторный close indexed-дампа безвреден и не оставляет mapping")
  void closeIsIdempotent() throws IOException {
    Path heap = heap("heap-twice.hprof");
    HeapDump dump = HeapDumpParser.parse(heap, INDEXED);
    dump.close();
    dump.close();

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  @Test
  @DisplayName("Сбой загрузки objectmap.idx освобождает временные mapping проверки индексов")
  void objectMapFailureReleasesTemporaryMappings() throws IOException {
    Path heap = heap("broken-objectmap.hprof");
    writeFastPathIndexes(heap, 1, 0); // objectmap обещает запись, но тела записи нет

    assertThrows(BufferUnderflowException.class, () -> HeapDumpParser.parse(heap, INDEXED));

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  @Test
  @DisplayName("Сбой после открытия objects.idx освобождает уже открытые mapping")
  void partialStartupFailureReleasesOpenedIndexes() throws IOException {
    Path heap = heap("broken-gcroots.hprof");
    writeFastPathIndexes(heap, 0, 1); // gcroots обещает запись, но тела записи нет

    assertThrows(BufferUnderflowException.class, () -> HeapDumpParser.parse(heap, INDEXED));

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  @Test
  @DisplayName("Повторное открытие с готовыми индексами освобождает inbound и retained mapping")
  void reopeningWithCachedIndexesReleasesAllReaders() throws IOException {
    Path heap = heap("reload.hprof");
    ParserOptions options =
        ParserOptions.builder()
            .parsingMode(HeapDumpParser.ParsingMode.INDEXED)
            .computeDominators(true)
            .build();

    try (HeapDump first = HeapDumpParser.parse(heap, options)) {
      assertTrue(first.hasDominators());
    }
    assertTrue(Files.exists(indexDir(heap).resolve(IndexFormat.INBOUND_INDEX_NAME)));
    assertTrue(Files.exists(indexDir(heap).resolve(IndexFormat.RETAINED_INDEX_NAME)));

    try (HeapDump reloaded = HeapDumpParser.parse(heap, options)) {
      assertTrue(reloaded.hasDominators());
    }

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  @Test
  @DisplayName("Сбой открытия data-индекса сохраняет обычный поиск и освобождает offset mapping")
  void optionalDataIndexFailureReleasesOffsetMapping() throws IOException {
    Path heap = heap("broken-optional-index.hprof");
    Path index = indexDir(heap);
    Path data = index.resolve(IndexFormat.CLASSINSTANCES_DATA_INDEX_NAME);
    Path offsets = index.resolve(IndexFormat.CLASSINSTANCES_OFFSET_INDEX_NAME);
    try (HeapDump initial = HeapDumpParser.parse(heap, INDEXED)) {
      assertEquals(10, initial.getObjectCount());
    }
    assertTrue(Files.isRegularFile(offsets));
    assertTrue(Files.isRegularFile(data));

    try (HeapDump reloaded =
        HeapDumpParser.parse(
            heap,
            INDEXED,
            (progress, message) -> {
              if ("Loading retained sizes".equals(message)) {
                // После успешной проверки заголовков оставляем оба файла, но портим magic data.
                try {
                  Files.write(data, new byte[20]);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              }
            })) {
      assertEquals(0, Files.readAllBytes(data)[0]);
      assertEquals(10, reloaded.getObjects().count());
      Files.delete(offsets); // Windows: mapping должен быть закрыт уже при fallback, до close().
    }
  }

  @Test
  @DisplayName("RuntimeException из callback при indexed-разборе освобождает файлы и индексы")
  void callbackRuntimeExceptionReleasesIndexedArtifacts() throws IOException {
    Path heap = heap("callback-runtime.hprof");
    IllegalStateException failure = new IllegalStateException("callback failure");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                HeapDumpParser.parse(
                    heap,
                    INDEXED,
                    (progress, message) -> {
                      if ("Pass 2/2: Building indexes".equals(message)) {
                        throw failure;
                      }
                    }));
    assertSame(failure, thrown);

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  @Test
  @DisplayName("Error из callback при indexed-разборе освобождает файлы и индексы")
  void callbackErrorReleasesIndexedArtifacts() throws IOException {
    Path heap = heap("callback-error.hprof");
    AssertionError failure = new AssertionError("callback error");

    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () ->
                HeapDumpParser.parse(
                    heap,
                    INDEXED,
                    (progress, message) -> {
                      if ("Pass 2/2: Building indexes".equals(message)) {
                        throw failure;
                      }
                    }));
    assertSame(failure, thrown);

    deleteRecursively(indexDir(heap));
    Files.delete(heap);
  }

  private Path heap(String fileName) throws IOException {
    Path heap = directory.resolve(fileName);
    SyntheticHeapDumpGenerator.generateMinimalHeapDump(heap, 10);
    return heap;
  }

  private static Path indexDir(Path heap) {
    return heap.getParent().resolve(heap.getFileName().toString() + ".idx");
  }

  /**
   * Создаёт минимальный набор index-файлов, который checkValidIndexes считает валидным: заголовки
   * корректны, но тела записей могут отсутствовать.
   */
  private static void writeFastPathIndexes(Path heap, int objectMapEntries, int gcRootEntries)
      throws IOException {
    try (IndexWriter writer = new IndexWriter(indexDir(heap))) {
      writer.beginObjectsIndex(0);
      writer.finishObjectsIndex();
      writer.beginObjectMapIndex(objectMapEntries);
      writer.finishObjectMapIndex();
      writer.beginClassMapIndex(0);
      writer.finishClassMapIndex();
      writer.beginGcRootsIndex(gcRootEntries);
      writer.finishGcRootsIndex();
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
