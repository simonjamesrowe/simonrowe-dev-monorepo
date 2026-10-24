package com.simonrowe.factory.ticketagent.npm;

/** The npm repair cannot run at all here, for a reason a person has to fix (a grant, a file). */
public class NpmRepairUnavailableException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public NpmRepairUnavailableException(final String message) {
    super(message);
  }
}
