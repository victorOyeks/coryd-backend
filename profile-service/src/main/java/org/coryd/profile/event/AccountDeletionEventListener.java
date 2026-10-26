package org.coryd.profile.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserDeletedEvent;
import org.coryd.profile.service.CloudinaryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * An account was deleted: everything profile-service holds about the person goes - rider and driver
 * profiles, the NIN check and its audit trail (the full NIN lives there), the licence, vehicles,
 * documents, emergency contacts, saved addresses and favourites - and the uploaded files behind them are
 * deleted from Cloudinary. Nothing here is needed once the person is gone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionEventListener {

    /** Uploaded files: (public id, url) columns per table, keyed on the person's id column. */
    private static final List<String> FILES = List.of(
            "SELECT government_id_public_id, government_id_url FROM profile.driver_profiles WHERE user_id = ?",
            "SELECT exterior_photo_public_id, exterior_photo_url FROM profile.vehicles WHERE driver_id = ?",
            "SELECT interior_photo_public_id, interior_photo_url FROM profile.vehicles WHERE driver_id = ?",
            "SELECT document_public_id, document_url FROM profile.driver_documents WHERE driver_id = ?",
            "SELECT selfie_public_id, selfie_url FROM profile.identity_verifications WHERE user_id = ?");

    private static final List<String> ERASE = List.of(
            "DELETE FROM profile.favorite_drivers WHERE rider_id = ? OR driver_id = ?",
            "DELETE FROM profile.emergency_contacts WHERE rider_id = ?",
            "DELETE FROM profile.saved_addresses WHERE rider_id = ?",
            "DELETE FROM profile.verification_checks WHERE driver_id = ?",
            "DELETE FROM profile.verification_cases WHERE driver_id = ?",
            "DELETE FROM profile.driver_documents WHERE driver_id = ?",
            "DELETE FROM profile.vehicles WHERE driver_id = ?",
            "DELETE FROM profile.smile_id_checks WHERE user_id = ?",
            "DELETE FROM profile.nin_lookup_misses WHERE user_id = ?",
            "DELETE FROM profile.identity_verifications WHERE user_id = ?",
            "DELETE FROM profile.rider_profiles WHERE user_id = ?",
            "DELETE FROM profile.driver_profiles WHERE user_id = ?");

    private final JdbcTemplate jdbc;
    private final CloudinaryService cloudinaryService;

    @KafkaListener(topics = KafkaTopics.USER_DELETED)
    @Transactional
    public void onDeleted(UserDeletedEvent event) {
        String userId = event.getUserId();
        List<String[]> files = new ArrayList<>();
        FILES.forEach(sql -> files.addAll(jdbc.query(sql, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, userId)));

        int erased = 0;
        for (String sql : ERASE) {
            erased += sql.contains("OR") ? jdbc.update(sql, userId, userId) : jdbc.update(sql, userId);
        }

        int deletedFiles = 0;
        for (String[] file : files) {
            if (file[0] == null || file[0].isBlank()) {
                continue;
            }
            try {
                cloudinaryService.delete(file[0], CloudinaryService.resourceTypeFromUrl(file[1]));
                deletedFiles++;
            } catch (Exception e) {
                // Best effort - the records are gone either way, and the file is no longer reachable from CoRyd.
                log.warn("Account {} deleted but Cloudinary file {} couldn't be removed: {}", userId, file[0], e.getMessage());
            }
        }
        log.info("Account {} deleted - {} profile records and {} uploaded files erased", userId, erased, deletedFiles);
    }
}
