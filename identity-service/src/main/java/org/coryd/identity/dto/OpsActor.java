package org.coryd.identity.dto;

/** Who did something on an account: the ops admin's user id and name. Both null on history recorded before names were kept. */
public record OpsActor(String id, String name) {
}
