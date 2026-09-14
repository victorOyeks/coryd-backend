package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserContactResponse getContact(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new UserContactResponse(user.getId(), user.getFullName(), user.getPhone());
    }

    /**
     * Signup only captures a phone, so the display name is whatever profile-service holds -
     * mirrored here so it can go in the JWT and the auth response without a cross-service call
     * on every token issue. profile-service pushes this on every profile save.
     */
    @Transactional
    public void updateFullName(String userId, String fullName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (fullName == null || fullName.isBlank() || fullName.equals(user.getFullName())) {
            return;
        }
        user.setFullName(fullName.trim());
        userRepository.save(user);
    }
}
