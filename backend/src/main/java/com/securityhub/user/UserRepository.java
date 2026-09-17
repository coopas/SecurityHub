package com.securityhub.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    /**
     * Usado pelo pedido de redefinição de senha: uma conta desativada não deve receber link,
     * e o serviço não pode distinguir os dois casos na resposta, então a distinção fica na
     * consulta.
     */
    Optional<User> findByEmailAndActiveTrue(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    Optional<User> findByIdAndCompanyIdAndActiveTrue(Long id, Long companyId);

    long countByCompanyIdAndRoleAndActiveTrue(Long companyId, Role role);

    /**
     * A empresa vem junto porque quem chama monta o DTO fora da transação que carregou a
     * linha — {@code AuthService.refresh} é o caso — e o proxy lazy já estaria desanexado.
     */
    @EntityGraph(attributePaths = "company")
    Optional<User> findWithCompanyById(Long id);
}
