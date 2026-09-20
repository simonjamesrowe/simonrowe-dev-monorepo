package com.simonrowe.coparent.shared;

import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ParentRepository;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Central family-membership and role policy for every CoParent use case. */
@Component
public class CoparentAccessPolicy {

  public static final String ACTIVE = "active";
  public static final String PRIMARY = "primary";

  private final CoparentIdentity identity;
  private final ParentRepository parents;

  public CoparentAccessPolicy(
      final CoparentIdentity identity,
      final ParentRepository parents) {
    this.identity = identity;
    this.parents = parents;
  }

  /** Resolves the caller's active parent row in a family without leaking other tenants. */
  public Parent requireMember(final ObjectId familyId) {
    return parents.findByFamilyIdAndAuth0IdAndStatus(familyId, identity.subject(), ACTIVE)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
  }

  /** Requires the caller to be an active primary parent. */
  public Parent requirePrimary(final ObjectId familyId) {
    final Parent parent = requireMember(familyId);
    if (!PRIMARY.equals(parent.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Primary parent role is required");
    }
    return parent;
  }

  /** Returns the caller identity accessor for services that need attribution. */
  public CoparentIdentity identity() {
    return identity;
  }
}
