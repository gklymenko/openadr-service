package com.qcharge.openadr.model.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "dr_event_signal",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dr_event_signal_event_signal_id",
                columnNames = {"event_id", "signal_id"}
        )
)
public class DrEventSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private DrEvent event;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "signal_id", nullable = false, length = 128)
    private String signalId;

    @Column(name = "signal_name", nullable = false, length = 64)
    private String signalName;

    @Column(name = "signal_type", nullable = false, length = 64)
    private String signalType;

    @Column(name = "current_value", precision = 19, scale = 6)
    private BigDecimal currentValue;

    @Column(name = "item_base_element", length = 128)
    private String itemBaseElement;

    @Column(name = "item_base_type", length = 128)
    private String itemBaseType;

    @Column(name = "item_units", length = 64)
    private String itemUnits;

    @Column(name = "si_scale_code", length = 32)
    private String siScaleCode;

    @OneToMany(mappedBy = "signal", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber ASC")
    private List<DrEventInterval> intervals = new ArrayList<>();

    public void addInterval(DrEventInterval interval) {
        interval.setSignal(this);
        intervals.add(interval);
    }
}
