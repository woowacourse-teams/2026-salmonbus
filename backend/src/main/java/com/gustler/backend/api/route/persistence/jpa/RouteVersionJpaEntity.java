package com.gustler.backend.api.route.persistence.jpa;

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

    private Integer turnSequence;

    private String upFirstDepartureTime;

    private String upLastDepartureTime;

    private String downFirstDepartureTime;

    private String downLastDepartureTime;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false)
    private String contentDigest;

    @Column(nullable = false)
    private OffsetDateTime validFrom;

    private OffsetDateTime validTo;
}
