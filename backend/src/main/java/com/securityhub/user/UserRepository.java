package com.securityhub.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    Optional<User> findByIdAndCompanyIdAndActiveTrue(Long id, Long companyId);

    long countByCompanyIdAndRoleAndActiveTrue(Long companyId, Role role);
}
