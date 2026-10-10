package org.project.mechanic_shop.infrastructure.jpa.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "processed_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedMessageJpaEntity {

	@Id
	@Column(name = "message_id")
	private UUID messageId;

	@Column(name = "message_type", nullable = false)
	private String messageType;

	@Column(name = "processed_at", nullable = false)
	private Instant processedAt;
}
