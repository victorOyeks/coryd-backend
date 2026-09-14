package org.coryd.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Thin wrapper over Spring Mail (plain SMTP). Kept behind a single method so
 * swapping to a transactional-email provider later only touches this class.
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public EmailService(JavaMailSender mailSender,
                         @Value("${mail.from-address}") String fromAddress,
                         @Value("${mail.from-name}") String fromName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public boolean send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromName + " <" + fromAddress + ">");
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            return false;
        }
    }

    /** Same contract as send(), but the body is HTML - used by the ops reports. */
    public boolean sendHtml(List<String> recipients, String subject, String html) {
        if (recipients == null || recipients.isEmpty()) {
            log.warn("No recipients configured for \"{}\" - not sending", subject);
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, fromName);
            helper.setTo(recipients.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send HTML email \"{}\": {}", subject, e.getMessage());
            return false;
        }
    }
}
