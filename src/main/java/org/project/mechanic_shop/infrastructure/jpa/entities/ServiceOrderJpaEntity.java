package org.project.mechanic_shop.infrastructure.jpa.entities;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.infrastructure.jpa.entities.base.BaseAuditJpaEntity;

@Entity
@Table(name = "service_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOrderJpaEntity extends BaseAuditJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "vehicle_id", nullable = false)
	private VehicleJpaEntity vehicle;

	@Column(name = "customer_complaint", columnDefinition = "TEXT", nullable = false)
	private String customerComplaint;

	@Column(name = "odometer_reading")
	private Integer odometerReading;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mechanic_id")
	private UserJpaEntity responsibleMechanic;

	@Column(name = "mechanic_diagnosis", columnDefinition = "TEXT")
	private String mechanicDiagnosis;

	@OneToMany(mappedBy = "serviceOrder", cascade = CascadeType.ALL, orphanRemoval = true)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private List<ServiceOrderStockItemJpaEntity> stockItems = new ArrayList<>();

	@OneToMany(mappedBy = "serviceOrder", cascade = CascadeType.ALL, orphanRemoval = true)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private List<ServiceOrderLaborJpaEntity> labors = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ServiceOrderStatusEnum status = ServiceOrderStatusEnum.RECEIVED;

	@Column(name = "budget_total_amount", precision = 10, scale = 2)
	private BigDecimal budgetTotalAmount = BigDecimal.ZERO;

	@Column(name = "approval_date")
	private LocalDateTime approvalDate;

	@Column(name = "estimated_completion_date")
	private LocalDateTime estimatedCompletionDate;

	@Column(name = "estimated_completion_days")
	private Integer estimatedCompletionDays;

	@Column(name = "actual_completion_date")
	private LocalDateTime actualCompletionDate;

	@Column(name = "actual_completion_days")
	private Integer actualCompletionDays;
}
