package com.vitadelta.api.catalog;

/** Availability of a product in one country as given by an import document. */
public record AvailabilityValues(String countryCode, AvailabilityStatus status, String note) {}
