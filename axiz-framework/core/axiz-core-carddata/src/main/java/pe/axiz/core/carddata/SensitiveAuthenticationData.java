package pe.axiz.core.carddata;

import pe.axiz.core.sensitive.SelfMasking;

/** Datos de autenticación que nunca deben persistirse ni publicarse. */
public interface SensitiveAuthenticationData extends SelfMasking {}
