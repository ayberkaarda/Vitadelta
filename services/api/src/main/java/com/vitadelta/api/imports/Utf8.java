package com.vitadelta.api.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Strict UTF-8 decoding: malformed or unmappable input is rejected instead of replaced. */
final class Utf8 {

  private Utf8() {}

  static Optional<String> decode(byte[] content) {
    try {
      return Optional.of(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString());
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
  }
}
