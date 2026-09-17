package com.securityhub.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    /**
     * Used by the password reset request: a deactivated account must not receive a link, and
     * the service cannot tell the two cases apart in the response, so the distinction lives in
     * the query.
     */
    Optional<User> findByEmailAndActiveTrue(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    Optional<User> findByIdAndCompanyIdAndActiveTrue(Long id, Long companyId);

    long countByCompanyIdAndRoleAndActiveTrue(Long companyId, Role role);

    /**
     * The company comes along because the caller builds the DTO outside the transaction that
     * loaded the row — {@code AuthService.refresh} is the case — and the lazy proxy would
     * already be detached.
     */
    @EntityGraph(attributePaths = "company")
    Optional<User> findWithCompanyById(Long id);
}
