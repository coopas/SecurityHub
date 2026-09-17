package com.securityhub.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    Optional<User> findByIdAndCompanyIdAndActiveTrue(Long id, Long companyId);

    List<User> findByCompanyIdOrderByNameAsc(Long companyId);

    @Query("select u from User u where u.company.id = :companyId "
            + "and (:role is null or u.role = :role) "
            + "and (:active is null or u.active = :active) "
            + "and (:search is null or lower(u.name) like :search or lower(u.email) like :search) "
            + "order by u.name asc")
    List<User> search(@Param("companyId") Long companyId,
                      @Param("role") Role role,
                      @Param("active") Boolean active,
                      @Param("search") String search);

    long countByCompanyIdAndRoleAndActiveTrue(Long companyId, Role role);
}
