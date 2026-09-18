package com.team.dating_backend.auth.repository;

import com.team.dating_backend.auth.entity.UserAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthAccountRepository extends JpaRepository<UserAuthAccount, Long> {}
