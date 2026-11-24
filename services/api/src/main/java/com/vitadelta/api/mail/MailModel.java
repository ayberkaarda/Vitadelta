package com.vitadelta.api.mail;

import java.util.Map;

/**
 * Typed content of one e-mail template. The template files are {@code
 * mail/<locale>/<templateName>.subject.txt}, {@code .txt} and {@code .html}. The renderer adds
 * {@code homeUrl} and {@code privacyUrl} to every model.
 */
public interface MailModel {

  /** Template base name, lowercase letters, digits and hyphens. */
  String templateName();

  /** Template variables; links must come from {@code links} only. */
  Map<String, Object> variables(MailLinks links, MailLocale locale);
}
