package com.simonrowe.coparent.model;

/**
 * The five fixed shelves of the family's document library. There are no custom sections: the
 * page groups by these, and a sixth would have nowhere to go.
 */
public enum DocumentSection {
  /** Passports, birth certificates, visas. Labelled "Identity and travel". */
  IDENTITY,
  /** Red book, care plans, vaccination records. */
  HEALTH,
  /** Reports, letters and consent forms. */
  SCHOOL,
  /** The child arrangements order and anything else the parents have agreed in writing. */
  AGREEMENTS,
  /** Activities and everything else. */
  OTHER
}
