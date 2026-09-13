package com.gustler.backend.api.route.persistence.jpa;

import com.gustler.backend.api.route.domain.Route;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "route_version")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteVersionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private RouteJpaEntity route;

    @Column(name = "turn_sequence")
    private Integer turnSequence;

    @Column(name = "up_first_departure_time")
    private String upFirstDepartureTime;

    @Column(name = "up_last_departure_time")
    private String upLastDepartureTime;

    @Column(name = "down_first_departure_time")
    private String downFirstDepartureTime;

    @Column(name = "down_last_departure_time")
    private String downLastDepartureTime;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_digest", nullable = false)
    private String contentDigest;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    public Long id() {
        return id;
    }

    public Route toRoute() {
        return route.toDomain();
    }

    public Integer turnSequence() {
        return turnSequence;
    }

    public String upFirstDepartureTime() {
        return upFirstDepartureTime;
    }

    public String upLastDepartureTime() {
        return upLastDepartureTime;
    }

    public String downFirstDepartureTime() {
        return downFirstDepartureTime;
    }

    public String downLastDepartureTime() {
        return downLastDepartureTime;
    }
}
