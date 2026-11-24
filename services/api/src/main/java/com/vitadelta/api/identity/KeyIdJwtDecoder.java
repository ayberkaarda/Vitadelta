package com.vitadelta.api.identity;

import com.nimbusds.jose.JWSObject;
import java.text.ParseException;
import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Selects the HS256 decoder of the token's {@code kid} header. Tokens without a {@code kid}, with
 * an unknown {@code kid} or that are not signed JWS objects are rejected before any signature
 * check. Exception messages never contain token material.
 */
final class KeyIdJwtDecoder implements JwtDecoder {

  private final Map<String, JwtDecoder> decodersByKeyId;

  KeyIdJwtDecoder(Map<String, JwtDecoder> decodersByKeyId) {
    this.decodersByKeyId = Map.copyOf(decodersByKeyId);
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    String keyId;
    try {
      keyId = JWSObject.parse(token).getHeader().getKeyID();
    } catch (ParseException | RuntimeException e) {
      throw new BadJwtException("The token is not a signed JWT");
    }
    JwtDecoder decoder = keyId == null ? null : decodersByKeyId.get(keyId);
    if (decoder == null) {
      throw new BadJwtException("The token key id is unknown");
    }
    return decoder.decode(token);
  }
}
