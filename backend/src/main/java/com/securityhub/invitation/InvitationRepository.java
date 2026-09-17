package com.securityhub.invitation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    /**
     * Sem empresa na assinatura, de propósito e ao contrário de todo o resto do esquema: o
     * índice único parcial de V7 é global, então a checagem que o antecede também precisa ser.
     * Quem chama nunca revela ao usuário em que empresa o convite vivo estava.
     */
    Optional<Invitation> findByEmailAndStatus(String email, InvitationStatus status);

    /** A empresa vem junto porque a prévia pública do convite mostra o nome dela. */
    @EntityGraph(attributePaths = {"company", "invitedBy"})
    Optional<Invitation> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = "invitedBy")
    List<Invitation> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    Optional<Invitation> findByIdAndCompanyId(Long id, Long companyId);
}
