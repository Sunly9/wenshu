package com.docmind.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "kb_visitor")
@IdClass(KbVisitor.Pk.class)
public class KbVisitor {

    public static class Pk implements Serializable {
        private Long kbId;
        private String visitorId;

        public Pk() {}
        public Pk(Long kbId, String visitorId) { this.kbId = kbId; this.visitorId = visitorId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(kbId, pk.kbId) && Objects.equals(visitorId, pk.visitorId);
        }
        @Override
        public int hashCode() { return Objects.hash(kbId, visitorId); }
    }

    @Id
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Id
    @Column(name = "visitor_id", nullable = false, length = 64)
    private String visitorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected KbVisitor() {}
    public KbVisitor(Long kbId, String visitorId) {
        this.kbId = kbId;
        this.visitorId = visitorId;
    }

    public Long getKbId() { return kbId; }
    public String getVisitorId() { return visitorId; }
    public Instant getCreatedAt() { return createdAt; }
}
