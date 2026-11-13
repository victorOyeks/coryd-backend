package org.coryd.payment.platform;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.coryd.payment.service.SubaccountBackfill;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When ops' commission takes effect, every driver's Paystack subaccount is moved to the new split - the same sync that runs
 * at startup, on its own thread (it calls Paystack once per subaccount). Charges made before it finishes still split at
 * the old rate on Paystack's side; see docs/ops-console/settings.md.
 */
@Slf4j
@Component
public class CommissionChangeListener {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "commission-resync");
        thread.setDaemon(true);
        return thread;
    });

    public CommissionChangeListener(PlatformSettings settings, SubaccountBackfill backfill) {
        settings.onChange(setting -> {
            if (setting == PlatformSetting.COMMISSION_PERCENT) {
                executor.execute(() -> {
                    try {
                        backfill.syncSubaccounts();
                    } catch (Exception e) {
                        log.error("Commission changed but the subaccount re-sync failed - it runs again at the next startup: {}", e.getMessage(), e);
                    }
                });
            }
        });
    }
}
