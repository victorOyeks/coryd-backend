package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.profile.dto.AddEmergencyContactRequest;
import org.coryd.profile.entity.EmergencyContact;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RiderProfile.emergencyContactName/Phone are kept as a denormalized cache of whichever contact
 * here is primary - trip-service's Share Trip/SOS flow reads those two flat fields today and
 * this way it never has to change to know about the underlying list.
 */
@Service
@RequiredArgsConstructor
public class EmergencyContactService {

    private final EmergencyContactRepository emergencyContactRepository;
    private final RiderProfileRepository riderProfileRepository;

    public List<EmergencyContact> getContacts(String riderId) {
        return emergencyContactRepository.findByRiderIdOrderByCreatedAtAsc(riderId);
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
            syncPrimaryOntoProfile(riderId, contact);
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
        syncPrimaryOntoProfile(riderId, contact);
        return contact;
    }

    private void syncPrimaryOntoProfile(String riderId, EmergencyContact contact) {
        RiderProfile profile = riderProfileRepository.findByUserId(riderId)
                .orElseGet(() -> RiderProfile.builder().userId(riderId).build());
        profile.setEmergencyContactName(contact.getName());
        profile.setEmergencyContactPhone(contact.getPhone());
        riderProfileRepository.save(profile);
    }
}
