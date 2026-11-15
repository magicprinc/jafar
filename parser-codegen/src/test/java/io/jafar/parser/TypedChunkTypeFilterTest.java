package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.types.JFRGCHeapSummary;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The constant pool {@link TypeFilter} of a typed parse belongs to one chunk: it is the closure of
 * the handler types over <em>that chunk's</em> metadata.
 *
 * <p>The typed parser used to keep that closure in one field shared by all chunk threads of a
 * parse, "computed once per session" behind an unsynchronized null check, and the one filter it
 * installed read the field on every call. {@code tck-test.jfr} has two chunks and only the first
 * declares {@code jdk.GCHeapSummary} (287 types against 53). When both chunk threads saw the field
 * unset, both computed; if chunk 2 stored last, chunk 1's filter was left with a closure holding
 * only the four handler root names, skipped the {@code jdk.types.GCWhen} pool, and {@code when()}
 * was {@code null} for all 1,892 events of that parse.
 *
 * <p>Both tests stop each chunk in {@code onMetadata} - after the typed parser has installed its
 * filter, before any constant pool is read - and return {@code false}, so an iteration costs the
 * metadata of two chunks and no events.
 */
class TypedChunkTypeFilterTest {
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  private static final String HANDLED_EVENT = "jdk.GCHeapSummary";

  /** Type of {@code jdk.GCHeapSummary.when}, a constant pool reference. */
  private static final String POOLED_FIELD_TYPE = "jdk.types.GCWhen";

  /** What {@link JFRGCHeapSummary} registers: the event and the types of its getters. */
  private static final Set<String> HANDLER_ROOTS =
      new HashSet<>(Arrays.asList(HANDLED_EVENT, "java.lang.String", "long", "int"));

  /** What one chunk's filter says about that chunk's own metadata. */
  private static final class ChunkView {
    final boolean declaresHandledEvent;
    final boolean hasFilter;
    final Set<String> accepted = new TreeSet<>();

    ChunkView(ParserContext context, MetadataEvent metadata) {
      declaresHandledEvent = context.getMetadataLookup().getClass(HANDLED_EVENT) != null;
      TypeFilter filter = context.get(TypeFilter.class);
      hasFilter = filter != null;
      if (filter != null) {
        for (MetadataClass clz : metadata.getClasses()) {
          if (filter.test(clz)) {
            accepted.add(clz.getName());
          }
        }
      }
    }
  }

  /**
   * Deterministic, one chunk thread: every chunk has a filter, and each accepts what its own
   * metadata reaches from the handler types. On the old code the second chunk had no filter at all.
   */
  @Test
  void everyChunkFiltersByItsOwnMetadata() throws Exception {
    Map<Integer, ChunkView> chunks = new ConcurrentHashMap<>();
    parseMetadataOnly(
        1,
        new ChunkParserListener() {
          @Override
          public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            chunks.put(context.getChunkIndex(), new ChunkView(context, metadata));
            return false;
          }
        });

    assertEquals(new TreeSet<>(Arrays.asList(1, 2)), new TreeSet<>(chunks.keySet()), "chunks");
    // The fixture property everything here rests on; without it the tests would be vacuous.
    assertTrue(chunks.get(1).declaresHandledEvent, "chunk 1 declares " + HANDLED_EVENT);
    assertTrue(!chunks.get(2).declaresHandledEvent, "chunk 2 does not declare " + HANDLED_EVENT);

    for (Map.Entry<Integer, ChunkView> e : chunks.entrySet()) {
      assertTrue(e.getValue().hasFilter, "chunk " + e.getKey() + " has its own type filter");
    }
    assertTrue(
        chunks.get(1).accepted.contains(POOLED_FIELD_TYPE)
            && chunks.get(1).accepted.contains(HANDLED_EVENT),
        "chunk 1 keeps the pools its handled event references, accepted: "
            + chunks.get(1).accepted);
    assertTrue(
        HANDLER_ROOTS.containsAll(chunks.get(2).accepted),
        "chunk 2 declares no handled event, so it keeps only handler root types, accepted: "
            + chunks.get(2).accepted);
  }

  /**
   * The race itself, two chunk threads: once both chunks have been through the typed parser's
   * {@code onMetadata}, chunk 1's filter must still accept the pool its handled event references.
   *
   * <p>The window is the few microseconds between one chunk thread's null check and the other's
   * store, and no listener callback lies inside it, so it cannot be forced; it is aimed at instead.
   * Both chunks are released together from {@code onChunkStart}, and chunk 2 - whose metadata is a
   * fifth the size - then spins for the lead it had in the previous iterations plus a jitter swept
   * over +-200us. Measured on the unfixed code (4 cores): the first hit came within {@value
   * #ITERATIONS} iterations in every one of the runs recorded in the commit message.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES) // a hang guard only, never a timing assertion
  void concurrentChunkDoesNotReplaceAnotherChunksFilter() throws Exception {
    long leadNs = 0; // how much earlier than chunk 1 chunk 2 reaches onMetadata, smoothed
    for (int i = 0; i < ITERATIONS; i++) {
      long jitterNs = ((i % 81) - 40) * 5_000L;
      long spinNs = Math.max(0, leadNs + jitterNs);
      CyclicBarrier started = new CyclicBarrier(2);
      CyclicBarrier filtersInstalled = new CyclicBarrier(2);
      long[] reachedMetadataNs = new long[3];
      AtomicReference<String> failure = new AtomicReference<>();

      parseMetadataOnly(
          2,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
              await(started);
              if (chunkIndex == 2) {
                long until = System.nanoTime() + spinNs;
                while (System.nanoTime() < until) {
                  // spin: a sleep is far coarser than the window
                }
              }
              return true;
            }

            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              reachedMetadataNs[context.getChunkIndex()] = System.nanoTime();
              // Both chunk threads are now past the typed parser's own onMetadata: whatever one
              // could do to the other's filter has been done.
              await(filtersInstalled);
              ChunkView view = new ChunkView(context, metadata);
              if (view.declaresHandledEvent
                  && view.hasFilter
                  && !view.accepted.contains(POOLED_FIELD_TYPE)) {
                failure.set(
                    "chunk "
                        + context.getChunkIndex()
                        + " declares "
                        + HANDLED_EVENT
                        + " but its type filter rejects "
                        + POOLED_FIELD_TYPE
                        + " (every pooled field of the handled events would read as null);"
                        + " it accepts only "
                        + view.accepted);
              }
              return false;
            }
          });

      if (failure.get() != null) {
        fail("iteration " + i + " (chunk 2 held back " + spinNs / 1000 + "us): " + failure.get());
      }
      long observedLeadNs = reachedMetadataNs[1] - (reachedMetadataNs[2] - spinNs);
      leadNs = i == 0 ? observedLeadNs : (leadNs * 7 + observedLeadNs) / 8;
    }
  }

  private static final int ITERATIONS = 2_000;

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(1, TimeUnit.MINUTES);
    } catch (Exception e) {
      throw new IllegalStateException("the other chunk never arrived", e);
    }
  }

  private static void parseMetadataOnly(int parallelism, ChunkParserListener listener)
      throws Exception {
    TypedJafarParser parser =
        ParsingContext.create()
            .withParallelism(parallelism)
            .newTypedParser(RECORDING)
            .withParserListener(listener);
    assertNotNull(parser.handle(JFRGCHeapSummary.class, (event, ctl) -> {}));
    try {
      parser.run();
    } finally {
      parser.close();
    }
  }
}
