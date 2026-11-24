package com.vitadelta.api.catalog.unit;

/** A requested conversion is not allowed or not defined by the conversion table. */
public class UnitConversionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Machine-readable reasons; {@link #code()} is the lower-case import error code. */
  public enum Reason {
    /** IU given for a nutrient that has no IU conversion (only VIT_A, VIT_D and VIT_E have one). */
    IU_NOT_ALLOWED("iu_not_allowed"),
    /** IU given for a form the nutrient's IU conversion does not cover (VIT_A beta-carotene). */
    UNSUPPORTED_IU_FORM("unsupported_iu_form"),
    /** No chain of rules leads from the source to the target unit. */
    NO_CONVERSION("no_conversion");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final Reason reason;
  private final String detail;

  public UnitConversionException(Reason reason, String detail) {
    super(reason.code() + ": " + detail);
    this.reason = reason;
    this.detail = detail;
  }

  /** The message without the reason code. */
  public String detail() {
    return detail;
  }

  public Reason reason() {
    return reason;
  }
}
