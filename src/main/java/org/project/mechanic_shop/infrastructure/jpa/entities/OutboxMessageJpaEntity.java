package org.project.mechanic_shop.infrastructure.jpa.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A message waiting to be relayed to the broker. Written in the same transaction as the state change. */
@Entity
@Table(name = "outbox_messages", indexes = @Index(name = "idx_outbox_pending", columnList = "published_at, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class OutboxMessageJpaEntity {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String exchange;

	@Column(name = "routing_key", nullable = false)
	private String routingKey;

	@Column(name = "message_type", nullable = false)
	private String messageType;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;
}
