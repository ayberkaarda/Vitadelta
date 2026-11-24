package com.vitadelta.api.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvParserTest {

  @Test
  void parsesHeaderAndRowsWithLf() {
    List<CsvRecord> records = CsvParser.parse("a,b\n1,2\n");
    assertThat(records).hasSize(2);
    assertThat(records.get(0).fields()).containsExactly("a", "b");
    assertThat(records.get(1).fields()).containsExactly("1", "2");
    assertThat(records.get(1).number()).isEqualTo(2);
    assertThat(records.get(1).size()).isEqualTo(2);
    assertThat(records.get(1).get(1)).isEqualTo("2");
  }

  @Test
  void parsesCrlfAndMissingFinalLineBreak() {
    List<CsvRecord> records = CsvParser.parse("a,b\r\n1,2\r\n3,4");
    assertThat(records)
        .extracting(CsvRecord::fields)
        .containsExactly(List.of("a", "b"), List.of("1", "2"), List.of("3", "4"));
  }

  @Test
  void ignoresLeadingByteOrderMark() {
    assertThat(CsvParser.parse("﻿a,b\n").getFirst().fields()).containsExactly("a", "b");
  }

  @Test
  void handlesQuotedFieldsWithCommaQuoteAndNewline() {
    List<CsvRecord> records =
        CsvParser.parse("x,y\n\"Store, Moda\",\"say \"\"hi\"\"\"\n\"multi\nline\",z\nnext,row\n");
    assertThat(records.get(1).fields()).containsExactly("Store, Moda", "say \"hi\"");
    assertThat(records.get(2).fields()).containsExactly("multi\nline", "z");
    assertThat(records.get(3).number()).isEqualTo(4);
    assertThat(records.get(3).line()).isEqualTo(5);
  }

  @Test
  void trimsUnquotedWhitespaceButKeepsQuotedWhitespace() {
    List<CsvRecord> records = CsvParser.parse(" a \t, \" b \" \n");
    assertThat(records.getFirst().fields()).containsExactly("a", " b ");
  }

  @Test
  void skipsEmptyLinesWithoutCountingThem() {
    List<CsvRecord> records = CsvParser.parse("h\n\n\r\nv1\n\nv2\n");
    assertThat(records).extracting(CsvRecord::number).containsExactly(1, 2, 3);
    assertThat(records).extracting(r -> r.get(0)).containsExactly("h", "v1", "v2");
  }

  @Test
  void keepsEmptyFields() {
    assertThat(CsvParser.parse("a,,c,\n").getFirst().fields()).containsExactly("a", "", "c", "");
    assertThat(CsvParser.parse("\"\"\n").getFirst().fields()).containsExactly("");
  }

  @Test
  void emptyInputHasNoRecords() {
    assertThat(CsvParser.parse("")).isEmpty();
  }

  @Test
  void rejectsUnterminatedQuote() {
    assertThatThrownBy(() -> CsvParser.parse("a\n\"open,b\n"))
        .isInstanceOfSatisfying(
            CsvFormatException.class,
            e -> {
              assertThat(e.line()).isEqualTo(2);
              assertThat(e.getMessage()).contains("unterminated");
            });
  }

  @Test
  void rejectsTextAfterClosingQuote() {
    assertThatThrownBy(() -> CsvParser.parse("\"a\"b,c\n"))
        .isInstanceOf(CsvFormatException.class)
        .hasMessageContaining("after closing quote");
  }

  @Test
  void rejectsQuoteInsideUnquotedField() {
    assertThatThrownBy(() -> CsvParser.parse("ab\"c\n"))
        .isInstanceOf(CsvFormatException.class)
        .hasMessageContaining("unexpected double quote");
  }
}
