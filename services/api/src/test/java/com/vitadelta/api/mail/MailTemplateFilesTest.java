package com.vitadelta.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks of the template files under {@code src/main/resources/mail/} (project specification
 * sections 8.7 and 15.5): both locales hold the same files, no template uses unescaped output, and
 * every file is valid UTF-8.
 */
class MailTemplateFilesTest {

  private static final Path MAIL_ROOT = Path.of("src", "main", "resources", "mail");

  private static final List<String> IDENTITY_TEMPLATES =
      List.of(
          VerifyEmailMail.TEMPLATE,
          AlreadyRegisteredMail.TEMPLATE,
          ResetPasswordMail.TEMPLATE,
          PlanActivatedMail.TEMPLATE,
          PlanExpiredMail.TEMPLATE);

  @Test
  void bothLocalesHoldTheSameFiles() throws IOException {
    Set<String> tr = relativeFiles(MAIL_ROOT.resolve("tr"));
    Set<String> en = relativeFiles(MAIL_ROOT.resolve("en"));
    assertThat(tr).isNotEmpty().isEqualTo(en);
    try (Stream<Path> children = Files.list(MAIL_ROOT)) {
      assertThat(children.map(p -> p.getFileName().toString()).sorted().toList())
          .containsExactly("en", "tr");
    }
  }

  @Test
  void everyIdentityTemplateHasSubjectTextAndHtmlInBothLocales() throws IOException {
    for (MailLocale locale : MailLocale.values()) {
      Set<String> files = relativeFiles(MAIL_ROOT.resolve(locale.directory()));
      for (String template : IDENTITY_TEMPLATES) {
        assertThat(files)
            .contains(template + ".subject.txt", template + ".txt", template + ".html");
      }
    }
  }

  @Test
  void noTemplateUsesUnescapedOutput() throws IOException {
    List<String> hits = new ArrayList<>();
    for (Path file : allFiles(MAIL_ROOT)) {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      if (content.contains("th:utext") || content.contains("data-th-utext")) {
        hits.add(file + ": th:utext");
      }
      if (file.toString().endsWith(".html") && content.contains("[(")) {
        hits.add(file + ": unescaped inline expression");
      }
    }
    assertThat(hits).isEmpty();
  }

  @Test
  void filesAreStrictUtf8AndTurkishCopyKeepsItsLetters() throws IOException {
    boolean turkishLetters = false;
    for (Path file : allFiles(MAIL_ROOT)) {
      String content = decodeStrict(Files.readAllBytes(file), file);
      assertThat(content).doesNotContain("�");
      if (file.startsWith(MAIL_ROOT.resolve("tr")) && content.matches("(?s).*[ığüşöçİĞÜŞÖÇ].*")) {
        turkishLetters = true;
      }
    }
    assertThat(turkishLetters).isTrue();
  }

  private static String decodeStrict(byte[] bytes, Path file) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new AssertionError(file + " is not valid UTF-8", e);
    }
  }

  private static Set<String> relativeFiles(Path directory) throws IOException {
    Set<String> names = new TreeSet<>();
    for (Path file : allFiles(directory)) {
      names.add(directory.relativize(file).toString().replace('\\', '/'));
    }
    return names;
  }

  private static List<Path> allFiles(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths.filter(Files::isRegularFile).sorted().toList();
    }
  }
}
