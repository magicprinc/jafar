package io.jafar.shell.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lombok.Getter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LombokIntegrationTest {
  @Test
  @DisplayName("Lombok генерирует доступный во время выполнения getter")
  void generatedGetterIsCallable() {
    assertEquals("fixture", new Fixture().getName());
  }

  private static final class Fixture {
    @Getter private final String name = "fixture";
  }
}
