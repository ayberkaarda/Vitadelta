-- Form registry and stored upper-limit citation notes (owner decision for Phase 4).
-- V3 and V4 are reserved for the notification/outbox and FX migrations of Phase 5; Flyway runs
-- with out-of-order enabled so that they can still be applied after this migration.
-- Like every catalog table, nutrient_form is written only through CatalogWriteTransaction.

-- Form registry ------------------------------------------------------------------------------

-- ANY and UNKNOWN are sentinels of the reference and product tables, never registry rows.
CREATE TABLE nutrient_form (
    code          varchar(40)  NOT NULL,
    nutrient_code varchar(40)  NOT NULL,
    name_en       varchar(200) NOT NULL,
    CONSTRAINT nutrient_form_pk PRIMARY KEY (code),
    CONSTRAINT nutrient_form_nutrient_fk FOREIGN KEY (nutrient_code) REFERENCES nutrient (code),
    CONSTRAINT nutrient_form_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT nutrient_form_not_sentinel CHECK (code NOT IN ('ANY', 'UNKNOWN')),
    CONSTRAINT nutrient_form_name_not_blank CHECK (btrim(name_en) <> ''),
    -- Target of the composite (form, nutrient) foreign keys below: a form belongs to one nutrient.
    CONSTRAINT nutrient_form_code_nutrient_unique UNIQUE (code, nutrient_code)
);

-- Databases migrated before this version already hold validated form codes. They are registered
-- here under their own code as name so that the foreign keys below can be created; the seed
-- loader replaces the names from data/reference/forms.csv. On a new database these statements
-- insert nothing.
INSERT INTO nutrient_form (code, nutrient_code, name_en)
SELECT DISTINCT ON (form_code) form_code, nutrient_code, form_code
FROM (
    SELECT form_code, nutrient_code FROM product_nutrient WHERE form_code <> 'UNKNOWN'
    UNION
    SELECT form_code, nutrient_code FROM upper_limit WHERE form_code <> 'ANY'
    UNION
    SELECT form_code, nutrient_code FROM unit_conversion
    WHERE form_code <> 'ANY' AND nutrient_code <> '*'
) used
ORDER BY form_code, nutrient_code;

-- Form references ----------------------------------------------------------------------------
-- Each table gets a generated column that is NULL for its sentinel and the form code otherwise;
-- the composite foreign key (form, nutrient) then requires a registered form of the same
-- nutrient for every non-sentinel row (MATCH SIMPLE skips the NULL sentinel rows). The sentinel
-- CHECK constraints of V1 stay in place.

ALTER TABLE product_nutrient
    ADD COLUMN form_ref varchar(40) GENERATED ALWAYS AS (NULLIF(form_code, 'UNKNOWN')) STORED,
    ADD CONSTRAINT product_nutrient_form_fk
        FOREIGN KEY (form_ref, nutrient_code) REFERENCES nutrient_form (code, nutrient_code);

ALTER TABLE upper_limit
    ADD COLUMN form_ref varchar(40) GENERATED ALWAYS AS (NULLIF(form_code, 'ANY')) STORED,
    ADD CONSTRAINT upper_limit_form_fk
        FOREIGN KEY (form_ref, nutrient_code) REFERENCES nutrient_form (code, nutrient_code);

-- Generic rows (nutrient_code '*') always use ANY (unit_conversion_generic_any), so their
-- form_ref is NULL and they never reach the foreign key.
ALTER TABLE unit_conversion
    ADD COLUMN form_ref varchar(40) GENERATED ALWAYS AS (NULLIF(form_code, 'ANY')) STORED,
    ADD CONSTRAINT unit_conversion_form_fk
        FOREIGN KEY (form_ref, nutrient_code) REFERENCES nutrient_form (code, nutrient_code);

CREATE INDEX product_nutrient_form_ref_idx ON product_nutrient (form_ref, nutrient_code);
CREATE INDEX upper_limit_form_ref_idx ON upper_limit (form_ref, nutrient_code);
CREATE INDEX unit_conversion_form_ref_idx ON unit_conversion (form_ref, nutrient_code);

-- Upper-limit citation notes -----------------------------------------------------------------
-- Every row of data/reference/upper_limits.csv carries a citation note, which is now stored.
-- A citation cannot be derived in SQL, so rows that exist before this migration get an empty
-- note; the seed loader replaces it from the file. The check is NOT VALID for exactly that
-- reason: it is enforced for every row inserted or updated from now on (the seed rewrites every
-- legacy row), while the legacy rows are not rechecked here.
ALTER TABLE upper_limit ADD COLUMN note text NOT NULL DEFAULT '';
ALTER TABLE upper_limit ALTER COLUMN note DROP DEFAULT;
ALTER TABLE upper_limit
    ADD CONSTRAINT upper_limit_note_not_blank CHECK (btrim(note) <> '') NOT VALID;

-- Import job errors ----------------------------------------------------------------------------
-- The import job API lists errors by row number and then in detection order; seq records the
-- detection order within a job (0-based). Rows written before this version keep 0.
ALTER TABLE import_job_error
    ADD COLUMN seq integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT import_job_error_seq_not_negative CHECK (seq >= 0);

DROP INDEX import_job_error_job_row_idx;
CREATE INDEX import_job_error_job_row_idx ON import_job_error (job_id, row_number, seq);
