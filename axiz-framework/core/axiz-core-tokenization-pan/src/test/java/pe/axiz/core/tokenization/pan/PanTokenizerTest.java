package pe.axiz.core.tokenization.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.core.tokenization.TokenizerCapabilities;

class PanTokenizerTest {
  private static final String NUMBER = "4111111111111111";
  private static final Pan PAN = Pan.parse(NUMBER);
  private static final TokenType TYPE = new TokenType("PAYMENT_CARD");
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final Token TOKEN = Token.fromEntropy(new byte[16]);
  private static final TokenizationResult RESULT =
      new TokenizationResult(TOKEN, TYPE, true, Map.of("last4", "1111"));

  @Test
  void delegationPreservesSecurityContextAndClosesSensitiveInputs() {
    RecordingTokenizer delegate = new RecordingTokenizer();
    PanTokenizer facade = new PanTokenizer(delegate, TYPE);
    assertSame(RESULT, facade.tokenize(PAN, DOMAIN, "operation"));
    assertEquals("operation", delegate.purpose);
    delegate.assertContextAndClosedInput();
    assertEquals(Optional.of(TOKEN), facade.lookup(PAN, DOMAIN, "reconciliation"));
    assertEquals("reconciliation", delegate.purpose);
    delegate.assertContextAndClosedInput();
    assertEquals(PAN, facade.detokenize(TOKEN, DOMAIN, "settlement"));
    assertEquals("settlement", delegate.purpose);
    assertSame(TOKEN, delegate.token);
    delegate.assertContextAndClosedInput();
  }

  @Test
  void authorizationFailuresPropagateAndStillEraseTheTemporaryValues() {
    RecordingTokenizer delegate = new RecordingTokenizer();
    delegate.denial = new SecurityException("denied");
    PanTokenizer facade = new PanTokenizer(delegate, TYPE);
    assertSame(
        delegate.denial,
        assertThrows(SecurityException.class, () -> facade.tokenize(PAN, DOMAIN, "operation")));
    delegate.assertContextAndClosedInput();
    assertSame(
        delegate.denial,
        assertThrows(SecurityException.class, () -> facade.lookup(PAN, DOMAIN, "lookup")));
    delegate.assertContextAndClosedInput();
    assertSame(
        delegate.denial,
        assertThrows(SecurityException.class, () -> facade.detokenize(TOKEN, DOMAIN, "reveal")));
  }

  @Test
  void invalidDetokenizedDataIsRejectedAndClosed() {
    RecordingTokenizer delegate = new RecordingTokenizer();
    delegate.revealed = "not-a-pan";
    PanTokenizer facade = new PanTokenizer(delegate, TYPE);
    assertThrows(RuntimeException.class, () -> facade.detokenize(TOKEN, DOMAIN, "reveal"));
    assertThrows(IllegalStateException.class, delegate.value::reveal);
    assertThrows(NullPointerException.class, () -> new PanTokenizer(null, TYPE));
    assertThrows(NullPointerException.class, () -> new PanTokenizer(delegate, null));
  }

  @Test
  void panProfileValidatesCanonicalDigitsAndOnlyExposesLastFour() {
    PanTokenProfile profile = new PanTokenProfile();
    assertEquals("pan.luhn", profile.id());
    assertEquals(1, profile.version());
    assertTrue(profile.cardData());
    try (SensitiveValue input = SensitiveValue.of("4111-1111 1111-1111");
        SensitiveValue canonical = profile.canonicalize(input)) {
      assertEquals(NUMBER, canonical.reveal());
      assertEquals("4111-1111 1111-1111", input.reveal());
      assertNotSame(input, canonical);
      Map<String, String> metadata = profile.metadata(canonical);
      assertEquals(Map.of("last4", "1111"), metadata);
      assertThrows(UnsupportedOperationException.class, () -> metadata.put("pan", NUMBER));
    }
    try (SensitiveValue invalid = SensitiveValue.of("4111111111111112")) {
      assertThrows(RuntimeException.class, () -> profile.canonicalize(invalid));
      assertThrows(RuntimeException.class, () -> profile.metadata(invalid));
    }
  }

  private static final class RecordingTokenizer implements Tokenizer {
    private SensitiveValue value;
    private TokenType type;
    private TokenDomain domain;
    private String purpose;
    private Token token;
    private SecurityException denial;
    private String revealed = NUMBER;

    @Override
    public TokenizerCapabilities capabilities() {
      return TokenizerCapabilities.vault();
    }

    @Override
    public TokenizationResult tokenize(
        TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
      record(type, value, domain, operationId);
      return RESULT;
    }

    @Override
    public Optional<Token> lookup(
        TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
      record(type, value, domain, purpose);
      return Optional.of(TOKEN);
    }

    @Override
    public SensitiveValue detokenize(
        TokenType type, Token token, TokenDomain domain, String purpose) {
      if (denial != null) throw denial;
      this.token = token;
      this.type = type;
      this.domain = domain;
      this.purpose = purpose;
      value = SensitiveValue.of(revealed);
      return value;
    }

    private void record(TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
      this.type = type;
      this.value = value;
      this.domain = domain;
      this.purpose = purpose;
      assertEquals(NUMBER, value.reveal());
      if (denial != null) throw denial;
    }

    void assertContextAndClosedInput() {
      assertSame(TYPE, type);
      assertSame(DOMAIN, domain);
      assertThrows(IllegalStateException.class, value::reveal);
    }

    @Override
    public Optional<TokenDescriptor> describe(TokenType type, Token token, TokenDomain domain) {
      throw new AssertionError("Unexpected describe operation");
    }

    @Override
    public boolean delete(TokenType type, Token token, TokenDomain domain) {
      throw new AssertionError("Unexpected delete operation");
    }
  }
}
