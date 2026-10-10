package org.project.mechanic_shop.saga;

import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.project.mechanic_shop.application.ports.MechanicServiceRepositoryPort;
import org.project.mechanic_shop.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.application.ports.SagaCommandPublisher;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.application.ports.ServiceOrderRepositoryPort;
import org.project.mechanic_shop.application.ports.StockItemRepositoryPort;
import org.project.mechanic_shop.application.saga.SagaMessage;
import org.project.mechanic_shop.application.saga.SagaParticipant;
import org.project.mechanic_shop.application.saga.SagaProperties;
import org.project.mechanic_shop.application.saga.ServiceOrderSagaOrchestrator;
import org.project.mechanic_shop.application.services.impl.StockItemServiceImpl;
import org.project.mechanic_shop.application.validators.StockItemValidator;
import org.project.mechanic_shop.domain.entities.mechanic_service.MechanicService;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.entities.user.User;
import org.project.mechanic_shop.domain.entities.vehicle.Vehicle;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.domain.enums.StockItemTypeEnum;
import org.project.mechanic_shop.shared.config.observability.ObservabilityReporter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Saga orchestrator wired to in-memory ports. The real orchestrator and the real stock rules
 * run; only the database and the broker are replaced. Used by the unit tests and by the BDD steps,
 * which play the role of the Billing and Execution services by feeding their replies.
 */
public class SagaTestHarness {

	public static final Duration BUDGET_GENERATION_TIMEOUT = Duration.ofMinutes(5);
	public static final Duration APPROVAL_TIMEOUT = Duration.ofHours(72);
	public static final Duration PAYMENT_TIMEOUT = Duration.ofHours(24);

	/** A command the orchestrator wrote to the outbox. */
	public record SentCommand(SagaParticipant participant, UUID sagaId, UUID serviceOrderId, String type, Object payload) {}

	public final MutableClock clock = new MutableClock(Instant.parse("2026-01-10T12:00:00Z"));
	public final ObjectMapper objectMapper = JsonMapper.builder().build();
	public final List<SentCommand> sentCommands = new ArrayList<>();
	public final List<Object> publishedEvents = new ArrayList<>();
	public final InMemorySagaRepository sagaRepository = new InMemorySagaRepository();
	public final InMemoryServiceOrderRepository serviceOrderRepository = new InMemoryServiceOrderRepository();
	public final InMemoryStockItemRepository stockItemRepository = new InMemoryStockItemRepository();
	public final InMemoryMechanicServiceRepository mechanicServiceRepository = new InMemoryMechanicServiceRepository();

	private final Set<UUID> processedMessages = new HashSet<>();
	public final ServiceOrderSagaOrchestrator orchestrator;

	public SagaTestHarness() {
		ApplicationEventPublisher eventPublisher = publishedEvents::add;
		SagaCommandPublisher commandPublisher = (participant, sagaId, serviceOrderId, type, payload) ->
			sentCommands.add(new SentCommand(participant, sagaId, serviceOrderId, type, payload));
		ProcessedMessagePort processedMessagePort = (messageId, type) -> processedMessages.add(messageId);

		var stockItemService = new StockItemServiceImpl(
			stockItemRepository,
			mock(StockItemValidator.class),
			eventPublisher
		);

		orchestrator = new ServiceOrderSagaOrchestrator(
			sagaRepository,
			commandPublisher,
			processedMessagePort,
			serviceOrderRepository,
			mechanicServiceRepository,
			stockItemRepository,
			stockItemService,
			eventPublisher,
			new ObservabilityReporter(),
			new SagaProperties(BUDGET_GENERATION_TIMEOUT, APPROVAL_TIMEOUT, PAYMENT_TIMEOUT),
			objectMapper,
			clock
		);
	}

	// ------------------------------------------------------------------------------------ fixtures

	public MechanicService addCatalogService(String name, String price, int estimatedMinutes) {
		MechanicService service = new MechanicService(null, name, name, estimatedMinutes, new BigDecimal(price));
		service.setExternalId(UUID.randomUUID());
		mechanicServiceRepository.save(service);
		return service;
	}

	public StockItem addStockItem(String name, int quantity, String salePrice) {
		StockItem item = new StockItem();
		item.setExternalId(UUID.randomUUID());
		item.setCode("COD-" + name.hashCode());
		item.setName(name);
		item.setType(StockItemTypeEnum.PART);
		item.setQuantity(quantity);
		item.setPendingDemand(0);
		item.setCostPrice(BigDecimal.ONE);
		item.setSalePrice(new BigDecimal(salePrice));
		stockItemRepository.save(item);
		return item;
	}

	/** Opens an order the way {@code ServiceOrderServiceImpl} does and starts its Saga. */
	public ServiceOrder openOrder(String licensePlate, String customerEmail) {
		User owner = new User();
		owner.setExternalId(UUID.randomUUID());
		owner.setName("Cliente Teste");
		owner.setEmail(customerEmail);

		Vehicle vehicle = new Vehicle();
		vehicle.setExternalId(UUID.randomUUID());
		vehicle.setLicensePlate(licensePlate);
		vehicle.setBrand("Volkswagen");
		vehicle.setModel("Gol");
		vehicle.setYear(2021);
		vehicle.setOwner(owner);

		ServiceOrder order = new ServiceOrder();
		order.setVehicle(vehicle);
		order.setCustomerComplaint("Ruido ao frear");
		order.setOdometerReading(45210);
		order.setStatus(ServiceOrderStatusEnum.RECEIVED);

		ServiceOrder saved = serviceOrderRepository.save(order);
		orchestrator.start(saved);
		return saved;
	}

	// ------------------------------------------------------------------------------------- replies

	public SagaMessage reply(ServiceOrder order, String type, Object payload) {
		SagaInstance saga = sagaOf(order);
		return new SagaMessage(
			UUID.randomUUID(),
			saga.getId(),
			order.getExternalId(),
			type,
			clock.instant(),
			"correlation-test",
			objectMapper.valueToTree(payload)
		);
	}

	public void receive(ServiceOrder order, String type, Object payload) {
		orchestrator.handleReply(reply(order, type, payload));
	}

	public void receive(ServiceOrder order, String type) {
		receive(order, type, Map.of());
	}

	// ----------------------------------------------------------------------------------- inquiries

	public SagaInstance sagaOf(ServiceOrder order) {
		return sagaRepository.findByServiceOrderId(order.getExternalId()).orElseThrow();
	}

	public ServiceOrder reload(ServiceOrder order) {
		return serviceOrderRepository.findByExternalId(order.getExternalId()).orElseThrow();
	}

	public int stockOf(StockItem item) {
		return stockItemRepository.findByExternalId(item.getExternalId()).orElseThrow().getQuantity();
	}

	public List<String> sentCommandTypes() {
		return sentCommands.stream().map(SentCommand::type).toList();
	}

	public SentCommand lastCommand(String type) {
		return sentCommands
			.reversed()
			.stream()
			.filter(c -> c.type().equals(type))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Command not sent: " + type + ". Sent: " + sentCommandTypes()));
	}

	// ------------------------------------------------------------------------------ in-memory ports

	public static class MutableClock extends java.time.Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		public void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public java.time.Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}

	public static class InMemorySagaRepository implements SagaInstanceRepositoryPort {

		private final Map<UUID, SagaInstance> sagas = new HashMap<>();
		public final List<SagaLogEntry> log = new ArrayList<>();

		@Override
		public SagaInstance save(SagaInstance saga) {
			sagas.put(saga.getId(), saga);
			return saga;
		}

		@Override
		public Optional<SagaInstance> findWithLockById(UUID id) {
			return Optional.ofNullable(sagas.get(id));
		}

		@Override
		public Optional<SagaInstance> findByServiceOrderId(UUID serviceOrderId) {
			return sagas.values().stream().filter(s -> s.getServiceOrderId().equals(serviceOrderId)).findFirst();
		}

		@Override
		public List<UUID> findExpiredIds(Instant now) {
			return sagas
				.values()
				.stream()
				.filter(s -> s.getStatus() == SagaStatusEnum.RUNNING)
				.filter(s -> s.getStepDeadline() != null && s.getStepDeadline().isBefore(now))
				.map(SagaInstance::getId)
				.toList();
		}

		@Override
		public void appendLog(SagaLogEntry entry) {
			log.add(entry);
		}

		@Override
		public List<SagaLogEntry> findLog(UUID sagaId) {
			return log.stream().filter(e -> e.sagaId().equals(sagaId)).toList();
		}
	}

	public static class InMemoryServiceOrderRepository implements ServiceOrderRepositoryPort {

		private final Map<UUID, ServiceOrder> orders = new HashMap<>();

		@Override
		public ServiceOrder save(ServiceOrder order) {
			if (order.getExternalId() == null) order.setExternalId(UUID.randomUUID());
			orders.put(order.getExternalId(), order);
			return order;
		}

		@Override
		public Optional<ServiceOrder> findByExternalId(UUID externalId) {
			return Optional.ofNullable(orders.get(externalId));
		}

		public void delete(UUID externalId) {
			orders.remove(externalId);
		}

		@Override
		public Double findAverageCompletionDays() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Long countCompletedOrders() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Page<ServiceOrder> findActiveOrders(
			List<ServiceOrderStatusEnum> excludedStatuses,
			ServiceOrderStatusEnum inProgress,
			ServiceOrderStatusEnum pendingApproval,
			ServiceOrderStatusEnum diagnosis,
			ServiceOrderStatusEnum received,
			Pageable pageable
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Page<ServiceOrder> search(
			String licensePlate,
			ServiceOrderStatusEnum status,
			User mechanic,
			User owner,
			Pageable pageable
		) {
			throw new UnsupportedOperationException();
		}
	}

	public static class InMemoryStockItemRepository implements StockItemRepositoryPort {

		private final Map<UUID, StockItem> items = new HashMap<>();

		@Override
		public StockItem save(StockItem item) {
			items.put(item.getExternalId(), item);
			return item;
		}

		public void delete(UUID externalId) {
			items.remove(externalId);
		}

		@Override
		public Optional<StockItem> findByCode(String code) {
			return items.values().stream().filter(i -> i.getCode().equals(code)).findFirst();
		}

		@Override
		public Optional<StockItem> findByExternalId(UUID externalId) {
			return Optional.ofNullable(items.get(externalId));
		}

		@Override
		public Optional<StockItem> findWithLockByExternalId(UUID externalId) {
			return findByExternalId(externalId);
		}

		@Override
		public Page<StockItem> search(String code, String name, Pageable pageable) {
			throw new UnsupportedOperationException();
		}
	}

	public static class InMemoryMechanicServiceRepository implements MechanicServiceRepositoryPort {

		private final Map<UUID, MechanicService> services = new HashMap<>();

		@Override
		public MechanicService save(MechanicService ms) {
			services.put(ms.getExternalId(), ms);
			return ms;
		}

		@Override
		public Optional<MechanicService> findByName(String name) {
			return services.values().stream().filter(s -> s.getName().equals(name)).findFirst();
		}

		@Override
		public Optional<MechanicService> findByExternalId(UUID externalId) {
			return Optional.ofNullable(services.get(externalId));
		}

		@Override
		public Page<MechanicService> search(String name, Pageable pageable) {
			throw new UnsupportedOperationException();
		}
	}
}
