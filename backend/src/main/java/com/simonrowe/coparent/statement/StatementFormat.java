package com.simonrowe.coparent.statement;

/** The bank and card exports CoParent can read, each recognised from its own layout. */
public enum StatementFormat {
  STARLING("Starling"),
  MONZO("Monzo"),
  AMEX("American Express"),
  SANTANDER_CURRENT("Santander current account"),
  SANTANDER_CREDIT_CARD("Santander credit card");

  private final String label;

  StatementFormat(final String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** How the account is shown to the parent: the bank, then the last four digits when known. */
  public String account(final String lastFour) {
    return lastFour == null ? label : label + " ··" + lastFour;
  }
}
