package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.entity.UserBlock;
import org.coryd.trip.repository.UserBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A rider blocks a driver (or a driver a rider) after a bad trip. From then on neither sees the
 * other's rides, neither can book the other, and notify-me never points one at the other.
 */
@Service
@RequiredArgsConstructor
public class UserBlockService {

    private final UserBlockRepository userBlockRepository;

    @Transactional
    public void block(String blockerId, String blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new BadRequestException("You can't block yourself");
        }
        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            userBlockRepository.save(UserBlock.builder().blockerId(blockerId).blockedId(blockedId).build());
        }
    }

    @Transactional
    public void unblock(String blockerId, String blockedId) {
        userBlockRepository.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    @Transactional(readOnly = true)
    public List<UserBlock> blockedBy(String blockerId) {
        return userBlockRepository.findByBlockerIdOrderByCreatedAtDesc(blockerId);
    }

    /** Ids to hide from this user's results - empty for an anonymous caller. */
    @Transactional(readOnly = true)
    public Set<String> keptApartFrom(String userId) {
        return userId == null ? Set.of() : new HashSet<>(userBlockRepository.findKeptApart(userId));
    }

    @Transactional(readOnly = true)
    public boolean keptApart(String a, String b) {
        return userBlockRepository.existsEitherWay(a, b);
    }
}
