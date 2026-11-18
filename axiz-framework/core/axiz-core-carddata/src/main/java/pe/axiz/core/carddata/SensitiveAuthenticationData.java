package pe.axiz.core.carddata;

import pe.axiz.core.sensitive.SelfMasking;

/** Authentication data that must never be persisted or published. */
public interface SensitiveAuthenticationData extends SelfMasking {}
