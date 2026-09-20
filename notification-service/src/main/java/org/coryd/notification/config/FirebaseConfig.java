package org.coryd.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${firebase.credentials-path}")
    private String credentialsPath;

    @PostConstruct
    public void initialize() {
        if (!FirebaseApp.getApps().isEmpty()) return;

        try (FileInputStream serviceAccount = new FileInputStream(credentialsPath)) {
            // FirebaseOptions leaves all three timeouts at 0, and google-http-client reads 0 as
            // "wait forever". An unresponsive FCM endpoint would therefore park a
            // notification-dispatch thread permanently, exactly as an unbounded Termii or SMTP
            // call does - same failure, different vendor. Set explicitly so push can fail but
            // never hang.
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .setConnectTimeout(5_000)
                    .setReadTimeout(10_000)
                    .setWriteTimeout(10_000)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase initialized successfully from: {}", credentialsPath);
        } catch (IOException e) {
            log.error("Failed to initialize Firebase - push notifications will be disabled. Path: {}", credentialsPath, e);
        }
    }
}
