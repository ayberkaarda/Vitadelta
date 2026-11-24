package com.vitadelta.api.catalog.unit;

import com.vitadelta.api.catalog.FormCodes;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable unit conversion table (unit conversion rules of the project specification, section
 * 5.5).
 *
 * <p>Resolution:
 *
 * <ol>
 *   <li>Equal units convert with factor 1.
 *   <li>{@code IU} is converted first, with the nutrient's IU rule for the product form; a rule
 *       with form {@code ANY} matches every form including {@code UNKNOWN}. A nutrient without IU
 *       rules rejects IU ({@link UnitConversionException.Reason#IU_NOT_ALLOWED}); a nutrient whose
 *       IU rules do not cover the form rejects it ({@link
 *       UnitConversionException.Reason#UNSUPPORTED_IU_FORM}). When the {@code ANY} rule is used
 *       while the nutrient also has form-specific IU rules, the result is flagged as assumed
 *       ({@code CONVERSION_ASSUMED}).
 *   <li>Mass units are then converted along the shortest chain of rules, preferring a
 *       nutrient-specific rule over the generic ({@code *}) rule for each step.
 * </ol>
 *
 * Amounts are multiplied exactly; no rounding is applied.
 */
public final class UnitConversionTable {

  private final List<ConversionRule> rules;

  public UnitConversionTable(List<ConversionRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public List<ConversionRule> rules() {
    return rules;
  }

  /** Whether the nutrient has at least one IU conversion rule. */
  public boolean acceptsIu(String nutrientCode) {
    return rules.stream()
        .anyMatch(r -> r.from() == Unit.IU && r.nutrientCode().equals(nutrientCode));
  }

  /**
   * Converts an amount of a nutrient.
   *
   * @param productForm the product's form code, {@code UNKNOWN} when not known
   * @throws UnitConversionException when the conversion is not allowed or not defined
   */
  public ConversionResult convert(
      String nutrientCode, BigDecimal amount, Unit from, String productForm, Unit to) {
    Objects.requireNonNull(nutrientCode, "nutrientCode");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    String form = productForm == null ? FormCodes.UNKNOWN : productForm;
    if (from == to) {
      return new ConversionResult(amount, to, false);
    }
    if (to == Unit.IU) {
      throw new UnitConversionException(
          UnitConversionException.Reason.NO_CONVERSION,
          "conversion into IU is not defined (" + nutrientCode + ")");
    }
    BigDecimal current = amount;
    Unit unit = from;
    boolean assumed = false;
    if (from == Unit.IU) {
      ConversionRule iuRule = resolveIuRule(nutrientCode, form, to);
      assumed = iuRule.isAnyForm() && hasFormSpecificIuRule(nutrientCode);
      current = current.multiply(iuRule.factor());
      unit = iuRule.to();
    }
    BigDecimal factor = massFactor(nutrientCode, form, unit, to);
    return new ConversionResult(current.multiply(factor), to, assumed);
  }

  private ConversionRule resolveIuRule(String nutrientCode, String form, Unit target) {
    List<ConversionRule> iuRules =
        rules.stream()
            .filter(r -> r.from() == Unit.IU && r.nutrientCode().equals(nutrientCode))
            .toList();
    if (iuRules.isEmpty()) {
      throw new UnitConversionException(
          UnitConversionException.Reason.IU_NOT_ALLOWED, "IU is not accepted for " + nutrientCode);
    }
    Optional<ConversionRule> specific =
        FormCodes.UNKNOWN.equals(form)
            ? Optional.empty()
            : preferTarget(
                iuRules.stream().filter(r -> r.formCode().equals(form)).toList(), target);
    return specific
        .or(() -> preferTarget(iuRules.stream().filter(ConversionRule::isAnyForm).toList(), target))
        .orElseThrow(() ->
            new UnitConversionException(
                UnitConversionException.Reason.UNSUPPORTED_IU_FORM,
                "IU of " + nutrientCode + " is not convertible for form " + form));
  }

  private static Optional<ConversionRule> preferTarget(List<ConversionRule> candidates, Unit to) {
    return candidates.stream()
        .filter(r -> r.to() == to)
        .findFirst()
        .or(() -> candidates.stream().findFirst());
  }

  private boolean hasFormSpecificIuRule(String nutrientCode) {
    return rules.stream()
        .anyMatch(
            r -> r.from() == Unit.IU && r.nutrientCode().equals(nutrientCode) && !r.isAnyForm());
  }

  /** Breadth-first search over mass units; each edge prefers the nutrient-specific rule. */
  private BigDecimal massFactor(String nutrientCode, String form, Unit from, Unit to) {
    if (from == to) {
      return BigDecimal.ONE;
    }
    Map<Unit, BigDecimal> reached = new EnumMap<>(Unit.class);
    reached.put(from, BigDecimal.ONE);
    Deque<Unit> queue = new ArrayDeque<>();
    queue.add(from);
    while (!queue.isEmpty()) {
      Unit unit = queue.removeFirst();
      for (Unit next : Unit.values()) {
        if (!next.isMass() || reached.containsKey(next)) {
          continue;
        }
        Optional<ConversionRule> edge = massEdge(nutrientCode, form, unit, next);
        if (edge.isPresent()) {
          BigDecimal factor = reached.get(unit).multiply(edge.get().factor());
          if (next == to) {
            return factor;
          }
          reached.put(next, factor);
          queue.addLast(next);
        }
      }
    }
    throw new UnitConversionException(
        UnitConversionException.Reason.NO_CONVERSION,
        "no conversion from " + from.symbol() + " to " + to.symbol() + " for " + nutrientCode);
  }

  private Optional<ConversionRule> massEdge(String nutrientCode, String form, Unit from, Unit to) {
    Optional<ConversionRule> specific =
        rules.stream()
            .filter(r ->
                r.from() == from
                    && r.to() == to
                    && r.nutrientCode().equals(nutrientCode)
                    && (r.isAnyForm() || r.formCode().equals(form)))
            .min((a, b) -> Boolean.compare(a.isAnyForm(), b.isAnyForm()));
    return specific.or(() ->
        rules.stream().filter(r -> r.from() == from && r.to() == to && r.isGeneric()).findFirst());
  }
}
