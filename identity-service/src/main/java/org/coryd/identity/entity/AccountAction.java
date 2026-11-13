package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.AccountActionType;

/** One ops action on an account - the account history. Never edited; removed only with the account. */
@Entity
@Table(name = "account_actions", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AccountAction extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountActionType action;

    /** The ops admin's user id. Null on suspensions recorded before this history existed. */
    @Column(length = 36)
    private String actorId;

    @Column(length = 150)
    private String actorName;

    @Column(length = 500)
    private String reason;

    /** JSON array of {field, before, after}; null when nothing was edited. */
    @Column(columnDefinition = "TEXT")
    private String changes;
}
