package com.securityhub.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Apaga em massa para que o DELETE chegue ao banco antes do INSERT do pedido novo: a
     * unicidade de user_id recusaria os dois convivendo, e o flush do Hibernate ordena
     * inserts antes de deletes.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from PasswordResetToken t where t.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
