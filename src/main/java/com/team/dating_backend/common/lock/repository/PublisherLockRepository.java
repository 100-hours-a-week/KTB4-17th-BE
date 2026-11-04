package com.team.dating_backend.common.lock.repository;

import com.team.dating_backend.common.lock.entity.PublisherLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PublisherLockRepository extends JpaRepository<PublisherLock, String> {

    @Transactional
    @Modifying
    @Query(value = """
        UPDATE publisher_locks
        SET owner_id = NULL, locked_until = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
        WHERE lock_name = :lockName AND owner_id = :ownerId
        """, nativeQuery = true)
    int release(@Param("lockName") String lockName, @Param("ownerId") String ownerId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE publisher_locks
            SET owner_id = :ownerId,
                locked_until = TIMESTAMPADD(
                    MICROSECOND, :lockTtlMicros, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE lock_name = :lockName
              AND (
                  owner_id IS NULL
                  OR owner_id = :ownerId
                  OR locked_until <= CURRENT_TIMESTAMP(6)
              )
            """,
        nativeQuery = true
    )
    int tryAcquireOrExtend(
        @Param("lockName") String lockName,
        @Param("ownerId") String ownerId,
        @Param("lockTtlMicros") long lockTtlMicros);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE publisher_locks
            SET owner_id = :ownerId,
                locked_until = TIMESTAMPADD(
                    MICROSECOND, :lockTtlMicros, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE lock_name = :lockName
              AND (
                  owner_id IS NULL
                  OR owner_id = :ownerId
                  OR locked_until <= CURRENT_TIMESTAMP(6)
              )
            """,
        nativeQuery = true
    )
    int tryAcquire(
        @Param("lockName") String lockName,
        @Param("ownerId") String ownerId,
        @Param("lockTtlMicros") long lockTtlMicros);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE publisher_locks
            SET locked_until = TIMESTAMPADD(
                    MICROSECOND, :lockTtlMicros, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE lock_name = :lockName
              AND owner_id = :ownerId
              AND locked_until > CURRENT_TIMESTAMP(6)
            """,
        nativeQuery = true
    )
    int tryRenew(
        @Param("lockName") String lockName,
        @Param("ownerId") String ownerId,
        @Param("lockTtlMicros") long lockTtlMicros);
}
