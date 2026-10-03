package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.profile.dto.AddEmergencyContactRequest;
import org.coryd.profile.entity.EmergencyContact;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * One set of emergency contacts per person, shared by their rider and driver profiles - the list here
 * is the source of truth, and both profile screens show its primary. RiderProfile.emergencyContactName/
 * Phone is only a cache of that primary, kept for older readers of the raw rider profile.
 */
@Service
@RequiredArgsConstructor
public class EmergencyContactService {

    private final EmergencyContactRepository emergencyContactRepository;
    private final RiderProfileRepository riderProfileRepository;

    public List<EmergencyContact> getContacts(String riderId) {
        return emergencyContactRepository.findByRiderIdOrderByCreatedAtAsc(riderId);
    }

    public Optional<EmergencyContact> getPrimary(String userId) {
        return emergencyContactRepository.findByRiderIdAndPrimaryTrue(userId);
    }

    /** The single contact on the profile forms - edits the primary in place, or creates it. Both fields
     * absent leaves the contact alone, so an older app that doesn't send them can't wipe it. */
    @Transactional
    public void applyFromProfileForm(String userId, String name, String phone) {
        boolean hasName = name != null && !name.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (!hasName && !hasPhone) {
            return;
        }
        if (!hasName || !hasPhone) {
            throw new BadRequestException("Add both a name and a phone number for your emergency contact");
        }

        EmergencyContact primary = emergencyContactRepository.findByRiderIdAndPrimaryTrue(userId).orElse(null);
        if (primary == null) {
            emergencyContactRepository.clearPrimaryForRider(userId);
            primary = EmergencyContact.builder().riderId(userId).primary(true).build();
        }
        primary.setName(name.trim());
        primary.setPhone(phone.trim());
        syncPrimaryOntoProfile(userId, emergencyContactRepository.save(primary), false);
    }

    @Transactional
    public EmergencyContact addContact(String riderId, AddEmergencyContactRequest request) {
        boolean isFirstContact = !emergencyContactRepository.existsByRiderId(riderId);

        EmergencyContact contact = emergencyContactRepository.save(EmergencyContact.builder()
                .riderId(riderId)
                .name(request.name())
                .phone(request.phone())
                .primary(isFirstContact)
                .build());

        if (isFirstContact) {
            syncPrimaryOntoProfile(riderId, contact, true);
        }
        return contact;
    }

    @Transactional
    public EmergencyContact selectPrimary(String riderId, String contactId) {
        emergencyContactRepository.clearPrimaryForRider(riderId);
        int updated = emergencyContactRepository.setPrimary(contactId, riderId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Emergency contact not found");
        }

        // First load of this row in this transaction - guaranteed to see the update just made, not a stale cached instance.
        EmergencyContact contact = emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency contact not found"));
        syncPrimaryOntoProfile(riderId, contact, true);
        return contact;
    }

    /** createIfMissing keeps the list endpoints' existing behaviour of creating a rider profile; the
     * profile-form path passes false so a driver saving their contact doesn't get a blank rider profile. */
    private void syncPrimaryOntoProfile(String userId, EmergencyContact contact, boolean createIfMissing) {
        RiderProfile profile = riderProfileRepository.findByUserId(userId)
                .orElseGet(() -> createIfMissing ? RiderProfile.builder().userId(userId).build() : null);
        if (profile == null) {
            return;
        }
        profile.setEmergencyContactName(contact.getName());
        profile.setEmergencyContactPhone(contact.getPhone());
        riderProfileRepository.save(profile);
    }
}
