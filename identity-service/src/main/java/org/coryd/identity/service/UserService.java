package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.util.Names;
import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserContactResponse getContact(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new UserContactResponse(user.getId(), Names.join(user.getFirstName(), user.getLastName()), user.getFirstName(), user.getLastName(), user.getPhone());
    }

    /** Batch form of getContact() for a page of rows - unknown ids are simply left out. */
    @Transactional(readOnly = true)
    public List<UserContactResponse> getContacts(List<String> userIds) {
        return userRepository.findAllById(userIds).stream()
                .map(user -> new UserContactResponse(user.getId(), Names.join(user.getFirstName(), user.getLastName()),
                        user.getFirstName(), user.getLastName(), user.getPhone()))
                .toList();
    }

    /**
     * Signup only captures a phone, so the display name is whatever profile-service holds -
     * mirrored here so it can go in the JWT and the auth response without a cross-service call
     * on every token issue. profile-service pushes this on every profile save.
     */
    @Transactional
    public void updateName(String userId, String firstName, String lastName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String first = trimToNull(firstName);
        String last = trimToNull(lastName);
        if (first == null && last == null) {
            return;
        }
        if (Objects.equals(first, user.getFirstName()) && Objects.equals(last, user.getLastName())) {
            return;
        }
        user.setFirstName(first == null ? user.getFirstName() : first);
        user.setLastName(last == null ? user.getLastName() : last);
        userRepository.save(user);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
