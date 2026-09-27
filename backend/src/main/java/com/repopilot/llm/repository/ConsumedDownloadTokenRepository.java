package com.repopilot.llm.repository;

import com.repopilot.llm.entity.ConsumedDownloadToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConsumedDownloadTokenRepository extends JpaRepository<ConsumedDownloadToken, String> {
    Optional<ConsumedDownloadToken> findByJtiHash(String jtiHash);
}
