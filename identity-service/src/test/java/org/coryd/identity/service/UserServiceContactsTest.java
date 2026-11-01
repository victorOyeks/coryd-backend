package org.coryd.identity.service;

import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the Ops Console's directories get for each person: phone, whether they're suspended and why, and when they joined. */
class UserServiceContactsTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    @Test
    void contactsCarryTheSuspensionAndSignUpDate() {
        Instant joined = Instant.parse("2026-01-12T09:00:00Z");
        Instant suspended = Instant.parse("2026-10-01T10:00:00Z");
        User active = User.builder().phone("08011112222").firstName("Ada").lastName("Obi").build();
        active.setId("u-1");
        active.setCreatedAt(joined);
        User off = User.builder().phone("08033334444").firstName("Tunde").lastName("Bakare")
                .suspendedAt(suspended).suspensionReason("Threatened a rider").build();
        off.setId("u-2");
        when(userRepository.findAllById(any())).thenReturn(List.of(active, off));

        List<UserContactResponse> contacts = userService.getContacts(List.of("u-1", "u-2"));

        assertThat(contacts.get(0)).isEqualTo(new UserContactResponse("u-1", "Ada Obi", "Ada", "Obi", "08011112222", null, null, joined));
        assertThat(contacts.get(1).suspendedAt()).isEqualTo(suspended);
        assertThat(contacts.get(1).suspensionReason()).isEqualTo("Threatened a rider");
    }
}
