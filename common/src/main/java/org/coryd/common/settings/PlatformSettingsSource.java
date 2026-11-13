package org.coryd.common.settings;

import org.coryd.common.kafka.events.PlatformSettingChangedEvent;

import java.util.List;

/** Where PlatformSettingsBootstrap gets every setting from: trip-service's internal endpoint, or its own database inside trip-service. */
public interface PlatformSettingsSource {

    /** Every setting ops ever set, as it stands now. Throws if the source can't be reached. */
    List<PlatformSettingChangedEvent> fetchAll();
}
