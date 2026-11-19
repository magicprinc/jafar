package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.utils.CustomByteBuffer;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParserJarCompatibilityTest {
  @Test
  @DisplayName("Multi-release JAR сохраняет доступный в базовом API метод wrap")
  void byteArrayWrappingWorksFromTheActualParserJar() throws Exception {
    Path source =
        Path.of(CustomByteBuffer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertTrue(Files.isRegularFile(source), "the parser must be loaded from its built JAR");
    CustomByteBuffer buffer = CustomByteBuffer.wrap(new byte[] {3, 7});
    try {
      assertEquals(3, buffer.get());
      assertEquals(7, buffer.get());
      assertEquals(0, buffer.remaining());
    } finally {
      buffer.close();
    }
  }

  @Test
  @DisplayName("Чтение JFR из InputStream работает с multi-release JAR")
  void inputStreamParsingUsesTheJarImplementation() throws Exception {
    Path source =
        Path.of(CustomByteBuffer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertTrue(Files.isRegularFile(source), "the parser must be loaded from its built JAR");
    ByteBuffer header = ByteBuffer.allocate(68);
    header.put(new byte[] {'F', 'L', 'R', 0});
    header.putShort((short) 1).putShort((short) 0).putLong(68L);
    for (int i = 0; i < 6; i++) {
      header.putLong(0L);
    }
    header.putInt(0);
    int[] chunks = {0};
    ChunkParserListener listener =
        new ChunkParserListener() {
          @Override
          public boolean onChunkStart(ParserContext context, int index, ChunkHeader chunkHeader) {
            chunks[0]++;
            return true;
          }
        };
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(new ByteArrayInputStream(header.array()), listener);
    }
    assertEquals(1, chunks[0]);
  }
}
