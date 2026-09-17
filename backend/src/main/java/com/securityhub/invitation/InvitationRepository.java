package com.securityhub.invitation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    /**
     * No company in the signature, on purpose and unlike all the rest of the schema: the
     * partial unique index of V7 is global, so the check that precedes it has to be global too.
     * The caller never reveals to the user which company the live invitation was in.
     */
    Optional<Invitation> findByEmailAndStatus(String email, InvitationStatus status);

    /** The company comes along because the public preview of the invitation shows its name. */
    @EntityGraph(attributePaths = {"company", "invitedBy"})
    Optional<Invitation> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = "invitedBy")
    List<Invitation> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    Optional<Invitation> findByIdAndCompanyId(Long id, Long companyId);
}
