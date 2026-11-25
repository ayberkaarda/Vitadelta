package com.vitadelta.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Decimal output of the application JSON mapper ({@link JsonConfig}). */
class JsonConfigTest {

  private static JsonMapper mapper() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new JsonConfig().plainDecimals().customize(builder);
    return builder.build();
  }

  @Test
  void decimalsAreWrittenPlainWithTheirScale() {
    JsonMapper json = mapper();
    assertThat(json.writeValueAsString(new BigDecimal("0.00000001"))).isEqualTo("0.00000001");
    assertThat(json.writeValueAsString(new BigDecimal("3E-8"))).isEqualTo("0.00000003");
    assertThat(json.writeValueAsString(new BigDecimal("1E+2"))).isEqualTo("100");
    assertThat(json.writeValueAsString(new BigDecimal("0.02087500"))).isEqualTo("0.02087500");
    assertThat(json.writeValueAsString(new BigDecimal("199.0000"))).isEqualTo("199.0000");
    assertThat(json.writeValueAsString(Map.of("rate", List.of(new BigDecimal("1.0E-9")))))
        .isEqualTo("{\"rate\":[0.0000000010]}");
  }

  @Test
  void withoutTheCustomizerTheDefaultWouldBeScientific() {
    assertThat(JsonMapper.builder().build().writeValueAsString(new BigDecimal("0.00000001")))
        .isEqualTo("1E-8");
  }
}
