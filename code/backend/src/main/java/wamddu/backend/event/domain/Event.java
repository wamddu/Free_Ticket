package wamddu.backend.event.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "events")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private String location;

    @Column(nullable = false)
    private String bannerImageUrl;

    @Column(nullable = false)
    private String mainImageUrl;

    private String category;

    private String description;

    private Integer running_time;

    private String descriptionImageUrl;

    // 켜면 주문 전에 대기열 입장권이 필요하다(docs/design/waiting-queue.md).
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean queueEnabled;
}
