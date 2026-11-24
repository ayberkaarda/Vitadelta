package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rules of {@code V5__form_registry_and_ul_notes.sql}: the form registry table, the form foreign
 * keys with the sentinels {@code UNKNOWN} (products) and {@code ANY} (reference rows), and the
 * required citation note of upper limits. Every case runs in a rolled-back transaction.
 */
@SpringBootTest
class FormRegistrySchemaTest extends PostgresIntegrationTest {

  private static final String PRODUCT_ID = "00000000-0000-4000-8000-0000000000f1";

  private static final List<String> BASE =
      List.of(
          "INSERT INTO nutrient VALUES ('FRT_A', 'Form test A', 'VITAMIN', 'mcg', NULL, 1)",
          "INSERT INTO nutrient VALUES ('FRT_B', 'Form test B', 'MINERAL', 'mg', NULL, 2)",
          "INSERT INTO nutrient_form VALUES ('FRT_A_ONE', 'FRT_A', 'Form one of A')",
          "INSERT INTO nutrient_form VALUES ('FRT_B_ONE', 'FRT_B', 'Form one of B')",
          "INSERT INTO ul_set VALUES ('FRT_UL', 'Form test UL', 'Test citation')",
          "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
              + " units_per_serving, servings_per_day, synthetic, created_at, updated_at) VALUES ('"
              + PRODUCT_ID
              + "', 'FRT-P1', 'Fixture Labs', 'Form test', 'TABLET', 60, 1, 1.00, true, now(),"
              + " now())");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private static String productNutrient(String nutrient, String form) {
    return "INSERT INTO product_nutrient (product_id, nutrient_code, amount_per_serving, unit,"
        + " form_code) VALUES ('"
        + PRODUCT_ID
        + "', '"
        + nutrient
        + "', 10, 'mg', "
        + form
        + ")";
  }

  private static String upperLimit(String nutrient, String form, String note) {
    return "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis,"
        + " note) VALUES ('FRT_UL', '"
        + nutrient
        + "', "
        + form
        + ", 10, 'mg', 'TOTAL', "
        + note
        + ")";
  }

  private static String conversion(String nutrient, String form) {
    return "INSERT INTO unit_conversion (nutrient_code, from_unit, to_unit, factor, form_code)"
        + " VALUES ('"
        + nutrient
        + "', 'g', 'mg', 1000, "
        + form
        + ")";
  }

  private void run(boolean accepted, String statement) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          status.setRollbackOnly();
          BASE.forEach(jdbc::execute);
          if (accepted) {
            jdbc.execute(statement);
          } else {
            assertThatThrownBy(() -> jdbc.execute(statement))
                .isInstanceOf(DataAccessException.class);
          }
        });
  }

  @Test
  void sentinelsAndRegisteredFormsOfTheSameNutrientAreAccepted() {
    run(true, productNutrient("FRT_A", "'UNKNOWN'"));
    run(true, productNutrient("FRT_A", "'FRT_A_ONE'"));
    run(true, upperLimit("FRT_A", "'ANY'", "'Test citation'"));
    run(true, upperLimit("FRT_A", "'FRT_A_ONE'", "'Test citation'"));
    run(true, conversion("FRT_A", "'ANY'"));
    run(true, conversion("FRT_A", "'FRT_A_ONE'"));
    run(true, conversion("*", "'ANY'").replace("'g', 'mg'", "'g', 'mcg'"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"'FRT_UNREGISTERED'", "'FRT_B_ONE'", "'ANY'"})
  void productFormsMustBeRegisteredForTheNutrient(String form) {
    run(false, productNutrient("FRT_A", form));
  }

  @ParameterizedTest
  @ValueSource(strings = {"'FRT_UNREGISTERED'", "'FRT_B_ONE'", "'UNKNOWN'"})
  void referenceFormsMustBeRegisteredForTheNutrient(String form) {
    run(false, upperLimit("FRT_A", form, "'Test citation'"));
    run(false, conversion("FRT_A", form));
  }

  @ParameterizedTest
  @ValueSource(strings = {"'ANY'", "'UNKNOWN'", "'lower'", "''"})
  void sentinelsAndMalformedCodesAreNotRegistryRows(String code) {
    run(false, "INSERT INTO nutrient_form VALUES (" + code + ", 'FRT_A', 'Not a form')");
  }

  @Test
  void registryRowsNeedAKnownNutrientAndAName() {
    run(false, "INSERT INTO nutrient_form VALUES ('FRT_X', 'FRT_NOPE', 'Form')");
    run(false, "INSERT INTO nutrient_form VALUES ('FRT_X', 'FRT_A', ' ')");
    run(false, "INSERT INTO nutrient_form VALUES ('FRT_A_ONE', 'FRT_B', 'Duplicate code')");
  }

  @Test
  void aReferencedFormCannotBeDeletedOrMovedToAnotherNutrient() {
    run(
        false,
        productNutrient("FRT_A", "'FRT_A_ONE'")
            + "; DELETE FROM nutrient_form WHERE code = 'FRT_A_ONE'");
    run(
        false,
        productNutrient("FRT_A", "'FRT_A_ONE'")
            + "; UPDATE nutrient_form SET nutrient_code = 'FRT_B' WHERE code = 'FRT_A_ONE'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"NULL", "''", "'   '"})
  void upperLimitsNeedACitationNote(String note) {
    run(false, upperLimit("FRT_A", "'ANY'", note));
  }

  @Test
  void theNoteCheckCoversUpdatesOfExistingRows() {
    run(
        false,
        upperLimit("FRT_A", "'ANY'", "'Test citation'")
            + "; UPDATE upper_limit SET note = '' WHERE ul_set_code = 'FRT_UL'");
  }

  @Test
  void theMigrationAddsTheColumnsAndKeys() {
    assertThat(
            jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND column_name IN ('form_ref', 'note', 'seq')"
                    + " ORDER BY 1",
                String.class))
        .contains(
            "import_job_error.seq",
            "product_nutrient.form_ref",
            "unit_conversion.form_ref",
            "unit_conversion.note",
            "upper_limit.form_ref",
            "upper_limit.note");
    assertThat(
            jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname LIKE '%form_fk' ORDER BY 1",
                String.class))
        .containsExactly(
            "product_nutrient_form_fk", "unit_conversion_form_fk", "upper_limit_form_fk");
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'upper_limit'"
                    + " AND column_name = 'note'",
                String.class))
        .isEqualTo("text");
  }
}
