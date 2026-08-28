package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.RouteContentDigest;
import com.gustler.backend.collector.RouteTimetable;
import com.gustler.backend.collector.RouteVersionContent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity(name = "CollectorRouteVersion")
@Table(name = "route_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteVersionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id")
    private Long routeId;

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
    @Column(name = "content_digest")
    private String contentDigest;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    public RouteVersionJpaEntity(
        final long routeId,
        Integer turnSequence,
        RouteVersionContent content,
        OffsetDateTime validFrom
    ) {
        this.routeId = routeId;
        this.turnSequence = turnSequence;
        this.contentDigest = content.contentDigest().value();
        this.validFrom = validFrom;
        applyTimetable(content.timetable());
    }

    public void closeAt(
        OffsetDateTime closedAt
    ) {
        if (validTo != null) {
            throw new IllegalStateException("판본 %d 는 %s 에 이미 닫혔다".formatted(id, validTo));
        }
        this.validTo = closedAt;
    }

    public void revise(
        RouteTimetable timetable
    ) {
        applyTimetable(timetable);
    }

    public RouteVersionContent content() {
        return new RouteVersionContent(new RouteContentDigest(contentDigest), timetable());
    }

    private RouteTimetable timetable() {
        return new RouteTimetable(
            upFirstDepartureTime,
            upLastDepartureTime,
            downFirstDepartureTime,
            downLastDepartureTime
        );
    }

    private void applyTimetable(
        RouteTimetable timetable
    ) {
        this.upFirstDepartureTime = timetable.upFirstDepartureTime();
        this.upLastDepartureTime = timetable.upLastDepartureTime();
        this.downFirstDepartureTime = timetable.downFirstDepartureTime();
        this.downLastDepartureTime = timetable.downLastDepartureTime();
    }
}
