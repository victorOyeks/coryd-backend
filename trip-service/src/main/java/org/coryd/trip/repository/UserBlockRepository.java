package org.coryd.trip.repository;

import org.coryd.trip.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserBlockRepository extends JpaRepository<UserBlock, String> {

    boolean existsByBlockerIdAndBlockedId(String blockerId, String blockedId);

    long deleteByBlockerIdAndBlockedId(String blockerId, String blockedId);

    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(String blockerId);

    /** Everyone this user blocked, and everyone who blocked them - both are kept apart. */
    @Query("""
            select case when b.blockerId = :userId then b.blockedId else b.blockerId end
            from UserBlock b where b.blockerId = :userId or b.blockedId = :userId
            """)
    List<String> findKeptApart(@Param("userId") String userId);

    @Query("""
            select count(b) > 0 from UserBlock b
            where (b.blockerId = :a and b.blockedId = :b) or (b.blockerId = :b and b.blockedId = :a)
            """)
    boolean existsEitherWay(@Param("a") String a, @Param("b") String b);
}
