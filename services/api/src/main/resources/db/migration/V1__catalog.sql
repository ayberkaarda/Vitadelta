-- Catalog and reference data (project specification section 5.3).
-- Every write to these tables goes through CatalogWriteTransaction, which bumps catalog_version
-- exactly once as the last statement of the committing transaction; no trigger performs the bump.

-- Reference data -----------------------------------------------------------------------------

CREATE TABLE nutrient (
    code                   varchar(40)   NOT NULL,
    name_en                varchar(200)  NOT NULL,
    category               varchar(20)   NOT NULL,
    canonical_unit         varchar(8)    NOT NULL,
    presence_floor_per_day numeric(14,4),
    sort_order             integer       NOT NULL,
    CONSTRAINT nutrient_pk PRIMARY KEY (code),
    CONSTRAINT nutrient_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT nutrient_name_not_blank CHECK (btrim(name_en) <> ''),
    CONSTRAINT nutrient_category_valid
        CHECK (category IN ('VITAMIN', 'MINERAL', 'COENZYME', 'OTHER')),
    CONSTRAINT nutrient_canonical_unit_valid CHECK (canonical_unit IN ('mg', 'mcg', 'g')),
    CONSTRAINT nutrient_presence_floor_positive
        CHECK (presence_floor_per_day IS NULL
               OR (presence_floor_per_day > 0 AND presence_floor_per_day <> 'NaN'))
);

CREATE TABLE reference_set (
    code            varchar(40)   NOT NULL,
    name            varchar(200)  NOT NULL,
    source_citation varchar(1000) NOT NULL,
    CONSTRAINT reference_set_pk PRIMARY KEY (code),
    CONSTRAINT reference_set_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT reference_set_citation_not_blank CHECK (btrim(source_citation) <> '')
);

CREATE TABLE reference_value (
    reference_set_code varchar(40)   NOT NULL,
    nutrient_code      varchar(40)   NOT NULL,
    amount             numeric(14,4) NOT NULL,
    unit               varchar(8)    NOT NULL,
    CONSTRAINT reference_value_pk PRIMARY KEY (reference_set_code, nutrient_code),
    CONSTRAINT reference_value_set_fk
        FOREIGN KEY (reference_set_code) REFERENCES reference_set (code),
    CONSTRAINT reference_value_nutrient_fk FOREIGN KEY (nutrient_code) REFERENCES nutrient (code),
    CONSTRAINT reference_value_amount_positive CHECK (amount > 0 AND amount <> 'NaN'),
    CONSTRAINT reference_value_unit_valid CHECK (unit IN ('mg', 'mcg', 'g'))
);

CREATE TABLE ul_set (
    code            varchar(40)   NOT NULL,
    name            varchar(200)  NOT NULL,
    source_citation varchar(1000) NOT NULL,
    CONSTRAINT ul_set_pk PRIMARY KEY (code),
    CONSTRAINT ul_set_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT ul_set_citation_not_blank CHECK (btrim(source_citation) <> '')
);

-- amount and basis are NULL together only for exempt rows (a form explicitly without a UL).
-- form_code uses the sentinel ANY for "every form"; UNKNOWN is a product-only sentinel.
CREATE TABLE upper_limit (
    ul_set_code   varchar(40)   NOT NULL,
    nutrient_code varchar(40)   NOT NULL,
    form_code     varchar(40)   NOT NULL,
    amount        numeric(14,4),
    unit          varchar(8)    NOT NULL,
    basis         varchar(20),
    CONSTRAINT upper_limit_pk PRIMARY KEY (ul_set_code, nutrient_code, form_code),
    CONSTRAINT upper_limit_set_fk FOREIGN KEY (ul_set_code) REFERENCES ul_set (code),
    CONSTRAINT upper_limit_nutrient_fk FOREIGN KEY (nutrient_code) REFERENCES nutrient (code),
    CONSTRAINT upper_limit_form_format CHECK (form_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT upper_limit_form_not_unknown CHECK (form_code <> 'UNKNOWN'),
    CONSTRAINT upper_limit_amount_positive
        CHECK (amount IS NULL OR (amount > 0 AND amount <> 'NaN')),
    CONSTRAINT upper_limit_unit_valid CHECK (unit IN ('mg', 'mcg', 'g')),
    CONSTRAINT upper_limit_basis_valid CHECK (basis IS NULL OR basis IN ('TOTAL', 'SUPPLEMENTAL')),
    CONSTRAINT upper_limit_exempt_consistent CHECK ((amount IS NULL) = (basis IS NULL))
);

-- Generic rows use nutrient_code '*' with form_code 'ANY'. nutrient_ref carries the foreign key
-- for nutrient-specific rows and is NULL for generic rows.
CREATE TABLE unit_conversion (
    nutrient_code varchar(40)   NOT NULL,
    from_unit     varchar(8)    NOT NULL,
    to_unit       varchar(8)    NOT NULL,
    factor        numeric(18,8) NOT NULL,
    form_code     varchar(40)   NOT NULL,
    note          varchar(500),
    nutrient_ref  varchar(40) GENERATED ALWAYS AS (NULLIF(nutrient_code, '*')) STORED,
    CONSTRAINT unit_conversion_pk PRIMARY KEY (nutrient_code, from_unit, to_unit, form_code),
    CONSTRAINT unit_conversion_nutrient_fk FOREIGN KEY (nutrient_ref) REFERENCES nutrient (code),
    CONSTRAINT unit_conversion_nutrient_format
        CHECK (nutrient_code = '*' OR nutrient_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT unit_conversion_generic_any CHECK (nutrient_code <> '*' OR form_code = 'ANY'),
    CONSTRAINT unit_conversion_form_format CHECK (form_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT unit_conversion_form_not_unknown CHECK (form_code <> 'UNKNOWN'),
    CONSTRAINT unit_conversion_from_unit_valid CHECK (from_unit IN ('mg', 'mcg', 'g', 'IU')),
    CONSTRAINT unit_conversion_to_unit_valid CHECK (to_unit IN ('mg', 'mcg', 'g')),
    CONSTRAINT unit_conversion_distinct_units CHECK (from_unit <> to_unit),
    CONSTRAINT unit_conversion_factor_positive CHECK (factor > 0 AND factor <> 'NaN')
);

CREATE TABLE country (
    code               varchar(2)  NOT NULL,
    name_en            varchar(200) NOT NULL,
    currency           varchar(3)  NOT NULL,
    reference_set_code varchar(40) NOT NULL,
    ul_set_code        varchar(40) NOT NULL,
    default_locale     varchar(10) NOT NULL,
    CONSTRAINT country_pk PRIMARY KEY (code),
    CONSTRAINT country_reference_set_fk
        FOREIGN KEY (reference_set_code) REFERENCES reference_set (code),
    CONSTRAINT country_ul_set_fk FOREIGN KEY (ul_set_code) REFERENCES ul_set (code),
    CONSTRAINT country_code_format CHECK (code ~ '^[A-Z]{2}$'),
    CONSTRAINT country_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT country_locale_format CHECK (default_locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),
    -- Target of the composite offer foreign key (country_code, currency).
    CONSTRAINT country_code_currency_unique UNIQUE (code, currency)
);

-- Products -----------------------------------------------------------------------------------

CREATE TABLE product (
    id                  uuid         NOT NULL,
    code                varchar(40)  NOT NULL,
    brand               varchar(200) NOT NULL,
    name                varchar(300) NOT NULL,
    dosage_form         varchar(20)  NOT NULL,
    units_per_container integer      NOT NULL,
    units_per_serving   integer      NOT NULL,
    servings_per_day    numeric(4,2) NOT NULL,
    gtin                varchar(14),
    source              varchar(500),
    source_ref          varchar(100),
    source_entry_date   date,
    synthetic           boolean      NOT NULL DEFAULT false,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT product_pk PRIMARY KEY (id),
    CONSTRAINT product_code_unique UNIQUE (code),
    CONSTRAINT product_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{1,39}$'),
    CONSTRAINT product_brand_not_blank CHECK (btrim(brand) <> ''),
    CONSTRAINT product_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT product_dosage_form_valid
        CHECK (dosage_form IN ('TABLET', 'CAPSULE', 'SOFTGEL', 'GUMMY', 'POWDER', 'LIQUID')),
    CONSTRAINT product_units_per_serving_positive CHECK (units_per_serving > 0),
    CONSTRAINT product_units_per_container_enough CHECK (units_per_container >= units_per_serving),
    CONSTRAINT product_servings_per_day_positive
        CHECK (servings_per_day > 0 AND servings_per_day <> 'NaN'),
    CONSTRAINT product_gtin_format CHECK (gtin IS NULL OR gtin ~ '^([0-9]{8}|[0-9]{12,14})$'),
    CONSTRAINT product_source_ref_not_blank CHECK (source_ref IS NULL OR btrim(source_ref) <> ''),
    CONSTRAINT product_real_has_provenance
        CHECK (synthetic OR (source IS NOT NULL AND source_ref IS NOT NULL)),
    CONSTRAINT product_updated_not_before_created CHECK (updated_at >= created_at)
);

-- form_code is the form of the UL-relevant part, or UNKNOWN when the label form is not in the
-- form registry or not stated (the raw text stays in form_label). ANY is not a product form.
CREATE TABLE product_nutrient (
    product_id            uuid          NOT NULL,
    nutrient_code         varchar(40)   NOT NULL,
    amount_per_serving    numeric(14,4) NOT NULL,
    ul_amount_per_serving numeric(14,4),
    unit                  varchar(8)    NOT NULL,
    form_code             varchar(40)   NOT NULL,
    form_label            varchar(200),
    CONSTRAINT product_nutrient_pk PRIMARY KEY (product_id, nutrient_code),
    CONSTRAINT product_nutrient_product_fk
        FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT product_nutrient_nutrient_fk FOREIGN KEY (nutrient_code) REFERENCES nutrient (code),
    CONSTRAINT product_nutrient_amount_positive
        CHECK (amount_per_serving > 0 AND amount_per_serving <> 'NaN'),
    CONSTRAINT product_nutrient_ul_amount_bounds CHECK (
        ul_amount_per_serving IS NULL
        OR (ul_amount_per_serving >= 0
            AND ul_amount_per_serving <> 'NaN'
            AND ul_amount_per_serving <= amount_per_serving)),
    CONSTRAINT product_nutrient_unit_valid CHECK (unit IN ('mg', 'mcg', 'g', 'IU')),
    CONSTRAINT product_nutrient_form_format CHECK (form_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT product_nutrient_form_not_any CHECK (form_code <> 'ANY'),
    -- Fixed IU allow-list, independent of the unit_conversion rows: IU only for VIT_A, VIT_D and
    -- VIT_E, and VIT_A in IU only for the retinol forms (beta-carotene IU is not convertible).
    CONSTRAINT product_nutrient_iu_nutrients
        CHECK (unit <> 'IU' OR nutrient_code IN ('VIT_A', 'VIT_D', 'VIT_E')),
    CONSTRAINT product_nutrient_iu_vitamin_a_form
        CHECK (unit <> 'IU' OR nutrient_code <> 'VIT_A'
               OR form_code IN ('RETINOL', 'RETINYL_ACETATE', 'RETINYL_PALMITATE'))
);

CREATE INDEX product_nutrient_nutrient_idx ON product_nutrient (nutrient_code);

CREATE TABLE product_availability (
    product_id   uuid        NOT NULL,
    country_code varchar(2)  NOT NULL,
    status       varchar(20) NOT NULL,
    note         varchar(500),
    CONSTRAINT product_availability_pk PRIMARY KEY (product_id, country_code),
    CONSTRAINT product_availability_product_fk
        FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT product_availability_country_fk
        FOREIGN KEY (country_code) REFERENCES country (code),
    CONSTRAINT product_availability_status_valid
        CHECK (status IN ('AVAILABLE', 'NOT_SOLD', 'DISCONTINUED'))
);

CREATE INDEX product_availability_country_status_idx
    ON product_availability (country_code, status);

-- Offers -------------------------------------------------------------------------------------

CREATE TABLE offer (
    id           uuid          NOT NULL,
    product_id   uuid          NOT NULL,
    country_code varchar(2)    NOT NULL,
    retailer     varchar(100)  NOT NULL,
    price        numeric(12,2) NOT NULL,
    currency     varchar(3)    NOT NULL,
    in_stock     boolean       NOT NULL,
    url          varchar(2048) NOT NULL,
    checked_at   timestamptz   NOT NULL,
    CONSTRAINT offer_pk PRIMARY KEY (id),
    CONSTRAINT offer_natural_key UNIQUE (product_id, country_code, retailer),
    CONSTRAINT offer_product_fk FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT offer_country_fk FOREIGN KEY (country_code) REFERENCES country (code),
    CONSTRAINT offer_retailer_trimmed CHECK (retailer ~ '^\S(.*\S)?$'),
    CONSTRAINT offer_price_positive CHECK (price > 0 AND price <> 'NaN'),
    CONSTRAINT offer_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    -- Scheme http or https, optional userinfo, a non-empty host (name or bracketed IP literal),
    -- an optional port, then path, query or fragment without whitespace.
    CONSTRAINT offer_url_scheme CHECK (
        url ~* '^https?://([^\s/?#@]*@)?([^\s/?#@:\[\]]+|\[[0-9a-f:.]+\])(:[0-9]{0,5})?([/?#]\S*)?$'),
    -- offer.currency equals its country's currency at every isolation level.
    CONSTRAINT offer_country_currency_fk FOREIGN KEY (country_code, currency)
        REFERENCES country (code, currency) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE INDEX offer_product_country_stock_checked_idx
    ON offer (product_id, country_code, in_stock, checked_at DESC);

-- offer.currency must equal the currency of its country. The country row is share-locked so a
-- concurrent currency change cannot slip between this check and the commit.
CREATE FUNCTION offer_currency_matches_country() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    country_currency varchar(3);
BEGIN
    SELECT c.currency INTO country_currency
    FROM country c
    WHERE c.code = NEW.country_code
    FOR SHARE;
    IF country_currency IS NOT NULL AND country_currency <> NEW.currency THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            CONSTRAINT = 'offer_currency_matches_country',
            MESSAGE = format('currency_mismatch: offer currency %s differs from country %s currency %s',
                             NEW.currency, NEW.country_code, country_currency);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER offer_currency_matches_country
    BEFORE INSERT OR UPDATE OF currency, country_code ON offer
    FOR EACH ROW EXECUTE FUNCTION offer_currency_matches_country();

-- A country's currency cannot change while offers exist for that country.
CREATE FUNCTION country_currency_change_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM offer o WHERE o.country_code = OLD.code) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'check_violation',
            CONSTRAINT = 'country_currency_change_guard',
            MESSAGE = format('country_currency_locked: country %s has offers; currency stays %s',
                             OLD.code, OLD.currency);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER country_currency_change_guard
    BEFORE UPDATE OF currency ON country
    FOR EACH ROW
    WHEN (OLD.currency IS DISTINCT FROM NEW.currency)
    EXECUTE FUNCTION country_currency_change_guard();

-- Catalog version ----------------------------------------------------------------------------

CREATE TABLE catalog_version (
    id        smallint    NOT NULL,
    version   bigint      NOT NULL,
    bumped_at timestamptz NOT NULL,
    CONSTRAINT catalog_version_pk PRIMARY KEY (id),
    CONSTRAINT catalog_version_single_row CHECK (id = 1),
    CONSTRAINT catalog_version_not_negative CHECK (version >= 0)
);

INSERT INTO catalog_version (id, version, bumped_at) VALUES (1, 0, now());

-- Import jobs (their endpoints arrive with the admin imports) --------------------------------

CREATE TABLE import_job (
    id          uuid        NOT NULL,
    kind        varchar(20) NOT NULL,
    status      varchar(20) NOT NULL,
    created_at  timestamptz NOT NULL,
    finished_at timestamptz,
    summary     jsonb,
    CONSTRAINT import_job_pk PRIMARY KEY (id),
    CONSTRAINT import_job_kind_valid CHECK (kind IN ('PRODUCTS_JSON', 'OFFERS_CSV')),
    CONSTRAINT import_job_status_valid CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT import_job_finished_consistent CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT import_job_finished_after_created
        CHECK (finished_at IS NULL OR finished_at >= created_at)
);

CREATE INDEX import_job_created_idx ON import_job (created_at DESC);

CREATE TABLE import_job_error (
    id         uuid          NOT NULL,
    job_id     uuid          NOT NULL,
    row_number integer       NOT NULL,
    message    varchar(2000) NOT NULL,
    CONSTRAINT import_job_error_pk PRIMARY KEY (id),
    CONSTRAINT import_job_error_job_fk
        FOREIGN KEY (job_id) REFERENCES import_job (id) ON DELETE CASCADE,
    CONSTRAINT import_job_error_row_positive CHECK (row_number >= 1),
    CONSTRAINT import_job_error_message_not_blank CHECK (btrim(message) <> '')
);

CREATE INDEX import_job_error_job_row_idx ON import_job_error (job_id, row_number);
