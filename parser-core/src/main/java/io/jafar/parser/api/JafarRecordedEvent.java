package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Collections;
import java.util.Map;

/**
 * Snapshot of a parsed JFR event.
 *
 * <p>The value map is an unmodifiable view, not a copy; changes to the supplied map remain visible.
 * The record can be stored and processed outside the parsing callback context.
 *
 * <p>The event data is accessible through:
 *
 * <ul>
 *   <li>{@link #type()} - the event type metadata (name, fields, annotations)
 *   <li>{@link #value()} - the event field values as a map
 *   <li>{@link #streamPosition()} - the byte position in the recording stream
 *   <li>{@link #chunkInfo()} - chunk metadata (timing, duration, conversions)
 * </ul>
 */
public record JafarRecordedEvent(
    MetadataClass type,
    Map<String, Object> value,
    long streamPosition,
    Control.ChunkInfo chunkInfo) {
  /** Wraps the supplied value map without copying it. */
  public JafarRecordedEvent {
    value = Collections.unmodifiableMap(value);
  }

  /**
   * Returns the event type name (e.g., "jdk.CPULoad", "jdk.ThreadStart").
   *
   * @return the event type name
   */
  public String typeName() {
    return type.getName();
  }

  /**
   * Returns the event type ID.
   *
   * @return the event type ID
   */
  public long typeId() {
    return type.getId();
  }
}
