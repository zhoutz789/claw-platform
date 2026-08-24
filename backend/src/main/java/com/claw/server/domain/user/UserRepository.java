package com.claw.server.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    Optional<User> findByCamdigikeyRef(String camdigikeyRef);

    List<User> findByDepartmentId(Long departmentId);
}
