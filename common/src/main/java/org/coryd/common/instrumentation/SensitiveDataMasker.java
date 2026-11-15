package org.coryd.common.instrumentation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataMasker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            // Auth & credentials
            "password", "confirmPassword", "confirm_password",
            "secret", "secretKey", "secret_key",
            "apiKey", "api_key", "apikey",
            "token", "accessToken", "access_token",
            "refreshToken", "refresh_token",
            "authorization", "code", "otp", "otpCode", "otp_code",

            // Personal identifiable info
            "email", "emailAddress", "email_address",
            "phoneNumber", "phone_number", "phone", "mobile",
            "governmentIdUrl", "government_id_url",
            "dateOfBirth", "date_of_birth", "dob",
            "nin", "idNumber", "id_number", "bvn",

            // Financial
            "cardNumber", "card_number", "pan", "maskedPan",
            "cvv", "cvc", "securityCode",
            "accountNumber", "account_number",
            "bankAccount", "bank_account",

            // Keys & secrets
            "privateKey", "private_key",
            "publicKey", "public_key",
            "gatewayReference", "gateway_reference"
    );

    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "cookie", "set-cookie",
            "x-api-key", "x-internal-api-key", "x-paystack-signature",
            "x-auth-token", "x-csrf-token"
    );

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(\\+?\\d{1,3}[- ]?)?\\d{10,14}\\b");

    private SensitiveDataMasker() {}

    public static String maskJsonBody(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = MAPPER.readTree(json);
            maskNode(root);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return maskPlainText(json);
        }
    }

    /**
     * maskJsonBody for a body that may be large: at most maxChars of the masked result, followed by a note of how much was
     * left out. The same fields are masked the same way - a JSON body is streamed token by token through the same field
     * rules (only a string value directly under a sensitive field name is masked, as in maskJsonBody), and the read stops
     * once the output is full, so a large body costs no more than its first maxChars. A body that isn't JSON is masked as
     * plain text, then cut. A JSON body cut short (a request held only in part) keeps what was masked before the cut.
     * maxChars of zero or less means no limit - maskJsonBody over the whole body.
     */
    public static String maskBody(byte[] body, int maxChars) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (maxChars <= 0) {
            return maskJsonBody(new String(body, StandardCharsets.UTF_8));
        }
        StringWriter out = new StringWriter();
        boolean wroteAny = false;
        boolean full = false;
        try (JsonParser parser = MAPPER.getFactory().createParser(body)) {
            JsonGenerator generator = MAPPER.getFactory().createGenerator(out).useDefaultPrettyPrinter();
            try {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.VALUE_STRING) {
                        String name = parser.currentName();
                        String value = parser.getText();
                        generator.writeString(name != null && parser.getParsingContext().inObject() && isSensitiveField(name)
                                ? maskByFieldType(name, value) : value);
                    } else {
                        generator.copyCurrentEvent(parser);
                    }
                    wroteAny = true;
                    generator.flush();
                    if (out.getBuffer().length() >= maxChars) {
                        full = true;
                        break;
                    }
                }
            } finally {
                generator.close();
            }
        } catch (IOException | RuntimeException e) {
            if (!wroteAny) {
                return plainText(body, maxChars);
            }
            // Not well-formed past this point (or held only in part): keep what was masked before it, nothing after.
            full = true;
        }
        String masked = out.toString();
        if (!full && masked.length() <= maxChars) {
            return masked;
        }
        return cut(masked, maxChars) + "\n... [truncated: " + body.length + " bytes in all]";
    }

    /** A body that isn't JSON: masked as plain text over twice what's shown (so a value straddling the cut is masked whole), then cut. */
    private static String plainText(byte[] body, int maxChars) {
        int take = (int) Math.min(body.length, 2L * maxChars);
        String masked = maskPlainText(new String(body, 0, take, StandardCharsets.UTF_8));
        if (take == body.length && masked.length() <= maxChars) {
            return masked;
        }
        return cut(masked, maxChars) + "\n... [truncated: " + body.length + " bytes in all]";
    }

    private static String cut(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    public static String maskHeaderValue(String headerName, String value) {
        if (headerName == null || value == null) return value;
        if (SENSITIVE_HEADER_NAMES.contains(headerName.toLowerCase())) {
            return mask(value);
        }
        return value;
    }

    public static boolean isSensitiveHeader(String headerName) {
        return headerName != null && SENSITIVE_HEADER_NAMES.contains(headerName.toLowerCase());
    }

    public static String maskPlainText(String text) {
        if (text == null) return null;
        String masked = EMAIL_PATTERN.matcher(text).replaceAll(mr -> maskEmail(mr.group()));
        masked = PHONE_PATTERN.matcher(masked).replaceAll(mr -> maskPhone(mr.group()));
        return masked;
    }

    private static void maskNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode value = entry.getValue();
                if (isSensitiveField(fieldName) && value.isTextual()) {
                    obj.set(fieldName, new TextNode(maskByFieldType(fieldName, value.asText())));
                } else if (value.isObject() || value.isArray()) {
                    maskNode(value);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                maskNode(arr.get(i));
            }
        }
    }

    private static boolean isSensitiveField(String fieldName) {
        return SENSITIVE_FIELD_NAMES.contains(fieldName) ||
               SENSITIVE_FIELD_NAMES.contains(fieldName.toLowerCase());
    }

    private static String maskByFieldType(String fieldName, String value) {
        if (value == null || value.isBlank()) return value;
        String lower = fieldName.toLowerCase();
        if (lower.contains("email")) return maskEmail(value);
        if (lower.contains("phone") || lower.contains("mobile")) return maskPhone(value);
        if (lower.contains("account") || lower.contains("card") || lower.contains("pan")) return maskAccountNumber(value);
        return mask(value);
    }

    private static String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***@***";
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    public static String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.length() <= 4) return "****";
        return "***" + phone.substring(phone.length() - 4);
    }

    private static String maskAccountNumber(String number) {
        if (number.length() <= 4) return "****";
        return "****" + number.substring(number.length() - 4);
    }

    private static String mask(String value) {
        if (value.length() <= 3) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 1);
    }
}
