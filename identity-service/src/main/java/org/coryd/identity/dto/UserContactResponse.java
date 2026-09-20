package org.coryd.identity.dto;

public record UserContactResponse(String userId, String fullName, String firstName, String lastName, String phone) {
}
