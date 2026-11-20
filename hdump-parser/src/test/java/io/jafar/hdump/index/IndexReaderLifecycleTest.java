package io.jafar.hdump.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Проверяет детерминированное освобождение memory-mapped индексов при закрытии и сбоях. */
class IndexReaderLifecycleTest {
  @TempDir Path directory;

  private static final List<String> INDEX_FILES =
      List.of(
          IndexFormat.OBJECTS_INDEX_NAME,
          IndexFormat.INBOUND_INDEX_NAME,
          IndexFormat.RETAINED_INDEX_NAME,
          IndexFormat.CLASSINSTANCES_OFFSET_INDEX_NAME,
          IndexFormat.CLASSINSTANCES_DATA_INDEX_NAME,
          IndexFormat.OBJECTMAP_INDEX_NAME,
          IndexFormat.CLASSMAP_INDEX_NAME,
          IndexFormat.GCROOTS_INDEX_NAME);

  /** Открывает один index reader без привязки к конкретному типу. */
  @FunctionalInterface
  interface IndexReaderFactory {
    AutoCloseable open(Path indexDir) throws IOException;
  }

  @Test
  @DisplayName("После close всех index readers файлы индексов удаляются, повторный close безвреден")
  void closingAllReadersReleasesIndexFiles() throws IOException {
    Path indexDir = directory.resolve("heap.idx");
    writeEmptyIndexes(indexDir);

    try (ObjectIndexReader objects = new ObjectIndexReader(indexDir);
        InboundCountReader inbound = new InboundCountReader(indexDir);
        RetainedSizeReader retained = new RetainedSizeReader(indexDir);
        ClassInstancesOffsetReader offsets = new ClassInstancesOffsetReader(indexDir);
        ClassInstancesDataReader data = new ClassInstancesDataReader(indexDir)) {
      assertEquals(0, objects.getEntryCount());
      assertEquals(0, inbound.getEntryCount());
      assertEquals(0, retained.getEntryCount());
      assertEquals(0, offsets.getClassCount());
      assertEquals(0L, data.getTotalInstances());

      objects.close();
      objects.close();
      inbound.close();
      retained.close();
      offsets.close();
      data.close();
    }

    for (String fileName : INDEX_FILES) {
      Files.delete(indexDir.resolve(fileName));
    }
    Files.delete(indexDir);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readerFactories")
  @DisplayName("Сбой конструктора index reader освобождает mapping невалидного файла")
  void failedConstructorReleasesMapping(String fileName, IndexReaderFactory factory)
      throws IOException {
    Path indexDir = directory.resolve(fileName + ".dir");
    Files.createDirectories(indexDir);
    Path indexFile = indexDir.resolve(fileName);
    Files.write(indexFile, new byte[4]); // magic = 0, каждый reader отвергает такой файл

    assertThrows(IOException.class, () -> factory.open(indexDir));

    Files.delete(indexFile);
    Files.delete(indexDir);
  }

  @Test
  @DisplayName("Сбой на счётчике записей освобождает mapping objects.idx")
  void entryCountOverflowReleasesMapping() throws IOException {
    Path indexDir = directory.resolve("overflow.idx");
    Files.createDirectories(indexDir);
    Path indexFile = indexDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
    ByteBuffer header = ByteBuffer.allocate(IndexFormat.HEADER_SIZE);
    header.putInt(IndexFormat.OBJECTS_INDEX_MAGIC);
    header.putInt(IndexFormat.FORMAT_VERSION);
    header.putLong((long) Integer.MAX_VALUE + 1);
    header.putInt(0);
    Files.write(indexFile, header.array());

    assertThrows(IOException.class, () -> new ObjectIndexReader(indexDir));

    Files.delete(indexFile);
    Files.delete(indexDir);
  }

  static Stream<Arguments> readerFactories() {
    return Stream.of(
        Arguments.of(IndexFormat.OBJECTS_INDEX_NAME, (IndexReaderFactory) ObjectIndexReader::new),
        Arguments.of(IndexFormat.INBOUND_INDEX_NAME, (IndexReaderFactory) InboundCountReader::new),
        Arguments.of(IndexFormat.RETAINED_INDEX_NAME, (IndexReaderFactory) RetainedSizeReader::new),
        Arguments.of(
            IndexFormat.CLASSINSTANCES_OFFSET_INDEX_NAME,
            (IndexReaderFactory) ClassInstancesOffsetReader::new),
        Arguments.of(
            IndexFormat.CLASSINSTANCES_DATA_INDEX_NAME,
            (IndexReaderFactory) ClassInstancesDataReader::new));
  }

  private static void writeEmptyIndexes(Path indexDir) throws IOException {
    try (IndexWriter writer = new IndexWriter(indexDir)) {
      writer.beginObjectsIndex(0);
      writer.finishObjectsIndex();
      writer.beginObjectMapIndex(0);
      writer.finishObjectMapIndex();
      writer.beginClassMapIndex(0);
      writer.finishClassMapIndex();
      writer.beginGcRootsIndex(0);
      writer.finishGcRootsIndex();
      writer.beginClassInstancesOffsetIndex(0);
      writer.finishClassInstancesOffsetIndex();
      writer.beginClassInstancesDataIndex(0);
      writer.finishClassInstancesDataIndex();
      writer.beginInboundIndex(0);
      writer.finishInboundIndex();
    }
    try (RetainedSizeWriter retained = new RetainedSizeWriter(indexDir, 0)) {
      // Пустой retained.idx нужен только для проверки освобождения его mapping.
    }
  }
}
