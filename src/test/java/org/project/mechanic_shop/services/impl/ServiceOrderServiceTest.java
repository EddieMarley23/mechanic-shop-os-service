package org.project.mechanic_shop.services.impl;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.application.ports.ServiceOrderRepositoryPort;
import org.project.mechanic_shop.application.saga.ServiceOrderSagaOrchestrator;
import org.project.mechanic_shop.application.services.*;
import org.project.mechanic_shop.application.services.impl.ServiceOrderServiceImpl;
import org.project.mechanic_shop.domain.dto.service_order_dto.*;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.entities.user.User;
import org.project.mechanic_shop.domain.entities.vehicle.Vehicle;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;
import org.project.mechanic_shop.domain.entities.saga.SagaLogEntry;
import org.project.mechanic_shop.domain.enums.SagaStatusEnum;
import org.project.mechanic_shop.domain.enums.SagaStepEnum;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.domain.enums.StockItemTypeEnum;
import org.project.mechanic_shop.domain.events.NewServiceOrderEvent;
import org.project.mechanic_shop.domain.events.ServiceOrderStatusChangedEvent;
import org.project.mechanic_shop.utils.MechanicServiceHelper;
import org.project.mechanic_shop.utils.UserHelper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceOrderServiceTest {

	private ServiceOrderService service;

	@Mock
	private ServiceOrderRepositoryPort repository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private VehicleService vehicleService;

	@Mock
	private UserService userService;

	@Mock
	private StockItemService stockItemService;

	@Mock
	private MechanicServiceService mechanicServiceCatalog;

	@Mock
	private ServiceOrderSagaOrchestrator sagaOrchestrator;

	@Mock
	private SagaInstanceRepositoryPort sagaRepository;

	@BeforeEach
	void setup() {
		service = new ServiceOrderServiceImpl(
			repository,
			eventPublisher,
			vehicleService,
			userService,
			stockItemService,
			mechanicServiceCatalog,
			sagaOrchestrator,
			sagaRepository
		);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private void authenticateAsCustomer(String email) {
		List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(email, null, authorities)
		);
	}

	@Nested
	class CreateServiceOrder {

		@Test
		void shouldCreateServiceOrderWithoutMechanic() {
			UUID vehicleId = UUID.randomUUID();
			var vehicle = vehicle();
			var dto = new ServiceOrderCreateDto(vehicleId, "Barulho no freio", 125000, null, null, null);

			when(vehicleService.findByExternalId(vehicleId)).thenReturn(vehicle);
			when(repository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

			var created = service.createServiceOrder(dto);

			assertThat(created.getVehicle()).isSameAs(vehicle);
			assertThat(created.getCustomerComplaint()).isEqualTo("Barulho no freio");
			assertThat(created.getOdometerReading()).isEqualTo(125000);
			assertThat(created.getStatus()).isEqualTo(ServiceOrderStatusEnum.RECEIVED);
			assertThat(created.getBudgetTotalAmount()).isEqualByComparingTo("0");
			assertThat(created.getResponsibleMechanic()).isNull();

			verify(eventPublisher, never()).publishEvent(any(NewServiceOrderEvent.class));
		}

		@Test
		void shouldStartTheSagaOfTheCreatedServiceOrder() {
			UUID vehicleId = UUID.randomUUID();
			var dto = new ServiceOrderCreateDto(vehicleId, "Barulho no freio", 125000, null, null, null);

			when(vehicleService.findByExternalId(vehicleId)).thenReturn(vehicle());
			when(repository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

			var created = service.createServiceOrder(dto);

			verify(sagaOrchestrator).start(created);
		}

		@Test
		void shouldPriceInitialItemsAsAPreliminaryEstimate() {
			UUID vehicleId = UUID.randomUUID();
			var mechanicService = MechanicServiceHelper.generateMechanicService();
			mechanicService.setExternalId(UUID.randomUUID());
			var part = stockItem();
			var dto = new ServiceOrderCreateDto(
				vehicleId,
				"Revisao",
				50000,
				null,
				List.of(new ServiceOrderLaborManDto(mechanicService.getExternalId(), 2)),
				List.of(new ServiceOrderStockItemManDto(part.getExternalId(), 3))
			);

			when(vehicleService.findByExternalId(vehicleId)).thenReturn(vehicle());
			when(mechanicServiceCatalog.findByExternalId(mechanicService.getExternalId())).thenReturn(mechanicService);
			when(stockItemService.findByExternalId(part.getExternalId())).thenReturn(part);
			when(repository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

			var created = service.createServiceOrder(dto);

			BigDecimal expected = mechanicService.getPrice().multiply(BigDecimal.valueOf(2)).add(new BigDecimal("60.00"));
			assertThat(created.getLabors()).hasSize(1);
			assertThat(created.getStockItems()).hasSize(1);
			assertThat(created.getBudgetTotalAmount()).isEqualByComparingTo(expected);
		}

		@Test
		void shouldCreateServiceOrderAndPublishEventWhenMechanicIsAssigned() {
			UUID vehicleId = UUID.randomUUID();
			UUID mechanicId = UUID.randomUUID();
			var vehicle = vehicle();
			var mechanic = mechanicUser();
			var dto = new ServiceOrderCreateDto(vehicleId, "Trocar embreagem", 90000, mechanicId, null, null);
			ArgumentCaptor<NewServiceOrderEvent> eventCaptor = ArgumentCaptor.forClass(NewServiceOrderEvent.class);

			when(vehicleService.findByExternalId(vehicleId)).thenReturn(vehicle);
			when(userService.findByExternalId(mechanicId)).thenReturn(mechanic);
			when(repository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

			var created = service.createServiceOrder(dto);

			assertThat(created.getResponsibleMechanic()).isSameAs(mechanic);
			verify(eventPublisher).publishEvent(eventCaptor.capture());
			assertThat(eventCaptor.getValue().serviceOrder()).isSameAs(created);
		}
	}

	@Nested
	class DeliverVehicle {

		@Test
		void shouldDeliverVehicleWhenServiceOrderIsCompleted() {
			UUID externalId = UUID.randomUUID();
			var order = serviceOrder();
			order.setStatus(ServiceOrderStatusEnum.COMPLETED);

			when(repository.findByExternalId(externalId)).thenReturn(Optional.of(order));
			when(repository.save(order)).thenReturn(order);

			var updated = service.deliverVehicle(externalId);

			assertThat(updated.getStatus()).isEqualTo(ServiceOrderStatusEnum.DELIVERED);
		}
	}

	@Nested
	class FindByExternalId {

		@Test
		void shouldFindServiceOrderByExternalId() {
			UUID externalId = UUID.randomUUID();
			var order = serviceOrder();

			when(repository.findByExternalId(externalId)).thenReturn(Optional.of(order));

			var found = service.findByExternalId(externalId);

			assertThat(found).usingRecursiveAssertion().isEqualTo(order);
		}

		@Test
		void shouldThrowExceptionWhenServiceOrderNotFound() {
			UUID externalId = UUID.randomUUID();

			when(repository.findByExternalId(externalId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.findByExternalId(externalId))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("Service Order not found for External ID: " + externalId);
		}

		@Test
		void shouldAllowCustomerToViewTheirOwnServiceOrder() {
			UUID externalId = UUID.randomUUID();
			var order = serviceOrder();
			String ownerEmail = order.getVehicle().getOwner().getEmail();

			when(repository.findByExternalId(externalId)).thenReturn(Optional.of(order));
			authenticateAsCustomer(ownerEmail);

			var found = service.findByExternalId(externalId);

			assertThat(found).isSameAs(order);
		}

		@Test
		void shouldDenyCustomerViewingSomeoneElsesServiceOrder() {
			UUID externalId = UUID.randomUUID();
			var order = serviceOrder();

			when(repository.findByExternalId(externalId)).thenReturn(Optional.of(order));
			authenticateAsCustomer("someone-else@test.com");

			assertThatThrownBy(() -> service.findByExternalId(externalId)).isInstanceOf(AccessDeniedException.class);
		}
	}

	@Nested
	class ListActiveOrders {

		@SuppressWarnings("unchecked")
		@Test
		void shouldCallRepositoryWithCorrectExclusionsAndStatusPriority() {
			Pageable pageable = PageRequest.of(0, 10);
			Page<ServiceOrder> expectedPage = new PageImpl<>(List.of(serviceOrder()));
			ArgumentCaptor<List<ServiceOrderStatusEnum>> excludedCaptor =
				(ArgumentCaptor<List<ServiceOrderStatusEnum>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);

			when(repository.findActiveOrders(
				any(List.class),
				eq(ServiceOrderStatusEnum.IN_PROGRESS),
				eq(ServiceOrderStatusEnum.PENDING_APPROVAL),
				eq(ServiceOrderStatusEnum.DIAGNOSIS),
				eq(ServiceOrderStatusEnum.RECEIVED),
				any(Pageable.class)
			)).thenReturn(expectedPage);

			var result = service.listActiveOrders(pageable);

			assertThat(result).isSameAs(expectedPage);

			verify(repository).findActiveOrders(
				excludedCaptor.capture(),
				eq(ServiceOrderStatusEnum.IN_PROGRESS),
				eq(ServiceOrderStatusEnum.PENDING_APPROVAL),
				eq(ServiceOrderStatusEnum.DIAGNOSIS),
				eq(ServiceOrderStatusEnum.RECEIVED),
				any(Pageable.class)
			);

			assertThat(excludedCaptor.getValue()).containsExactlyInAnyOrder(
				ServiceOrderStatusEnum.COMPLETED,
				ServiceOrderStatusEnum.DELIVERED,
				ServiceOrderStatusEnum.CANCELED
			);
		}
	}

	@Nested
	class Search {

		@Test
		void shouldReturnServiceOrdersWhenSearchCriteriaIsProvided() {
			var order = serviceOrder();
			var user = UserHelper.generateUser();
			Pageable pageable = PageRequest.of(0, 10);
			Page<ServiceOrder> expectedPage = new PageImpl<>(List.of(order));

			when(
				repository.search("ABC1234", ServiceOrderStatusEnum.PENDING_APPROVAL, user, null, pageable)
			).thenReturn(expectedPage);

			var result = service.search("ABC1234", ServiceOrderStatusEnum.PENDING_APPROVAL, pageable, user);

			assertThat(result).isEqualTo(expectedPage);
			verify(repository).search("ABC1234", ServiceOrderStatusEnum.PENDING_APPROVAL, user, null, pageable);
		}

		@Test
		void shouldForceOwnerFilterWhenAuthenticatedAsCustomer() {
			var order = serviceOrder();
			var customer = UserHelper.generateUser();
			customer.setRole("CUSTOMER");
			customer.setEmail("customer@test.com");
			Pageable pageable = PageRequest.of(0, 10);
			Page<ServiceOrder> expectedPage = new PageImpl<>(List.of(order));

			authenticateAsCustomer("customer@test.com");

			when(userService.findByEmail("customer@test.com")).thenReturn(customer);
			when(repository.search(null, null, null, customer, pageable)).thenReturn(expectedPage);

			var result = service.search(null, null, pageable, null);

			assertThat(result).isEqualTo(expectedPage);
			verify(repository).search(null, null, null, customer, pageable);
		}
	}

	@Nested
	class GetMetrics {

		@Test
		void shouldReturnAverageAndCountWhenOrdersExist() {
			when(repository.findAverageCompletionDays()).thenReturn(3.5);
			when(repository.countCompletedOrders()).thenReturn(10L);

			ServiceOrderMetricsDto result = service.getMetrics();

			assertThat(result.averageCompletionDays()).isEqualTo(3.5);
			assertThat(result.totalCompletedOrders()).isEqualTo(10L);
		}

		@Test
		void shouldReturnNullAverageAndZeroCountWhenNoOrdersFinished() {
			when(repository.findAverageCompletionDays()).thenReturn(null);
			when(repository.countCompletedOrders()).thenReturn(0L);

			ServiceOrderMetricsDto result = service.getMetrics();

			assertThat(result.averageCompletionDays()).isNull();
			assertThat(result.totalCompletedOrders()).isZero();
		}
	}

	@Nested
	class GetSaga {

		@Test
		void shouldReturnSagaStateAndHistory() {
			var order = serviceOrder();
			var saga = SagaInstance.start(order.getExternalId(), java.time.Instant.parse("2026-01-01T10:00:00Z"));
			var entry = new SagaLogEntry(
				saga.getId(),
				java.time.Instant.parse("2026-01-01T10:00:00Z"),
				"service-order.opened",
				SagaStepEnum.DIAGNOSIS,
				SagaStatusEnum.RUNNING,
				"Diagnosis requested."
			);

			when(repository.findByExternalId(order.getExternalId())).thenReturn(Optional.of(order));
			when(sagaRepository.findByServiceOrderId(order.getExternalId())).thenReturn(Optional.of(saga));
			when(sagaRepository.findLog(saga.getId())).thenReturn(List.of(entry));

			var dto = service.getSaga(order.getExternalId());

			assertThat(dto.sagaId()).isEqualTo(saga.getId());
			assertThat(dto.step()).isEqualTo(SagaStepEnum.DIAGNOSIS);
			assertThat(dto.status()).isEqualTo(SagaStatusEnum.RUNNING);
			assertThat(dto.history()).hasSize(1);
			assertThat(dto.history().get(0).trigger()).isEqualTo("service-order.opened");
		}

		@Test
		void shouldThrowWhenTheServiceOrderHasNoSaga() {
			var order = serviceOrder();

			when(repository.findByExternalId(order.getExternalId())).thenReturn(Optional.of(order));
			when(sagaRepository.findByServiceOrderId(order.getExternalId())).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.getSaga(order.getExternalId())).isInstanceOf(EntityNotFoundException.class);
		}
	}

	private ServiceOrder serviceOrder() {
		var order = new ServiceOrder();
		order.setId(1L);
		order.setExternalId(UUID.randomUUID());
		order.setVehicle(vehicle());
		order.setCustomerComplaint("Ruido");
		order.setOdometerReading(100000);
		order.setStatus(ServiceOrderStatusEnum.RECEIVED);
		order.setCreatedAt(java.time.LocalDateTime.of(2024, java.time.Month.JANUARY, 1, 10, 0));
		return order;
	}

	private Vehicle vehicle() {
		var vehicle = new Vehicle();
		vehicle.setId(1L);
		vehicle.setExternalId(UUID.randomUUID());
		vehicle.setLicensePlate("ABC1234");
		vehicle.setBrand("Volkswagen");
		vehicle.setModel("Gol");
		vehicle.setYear(2021);
		vehicle.setColor("Prata");
		vehicle.setOwner(UserHelper.generateUser());
		return vehicle;
	}

	private User mechanicUser() {
		var user = UserHelper.generateUser();
		user.setRole("MECHANIC");
		return user;
	}

	private StockItem stockItem() {
		var item = new StockItem();
		item.setId(1L);
		item.setExternalId(UUID.randomUUID());
		item.setCode("PT-01");
		item.setName("Filtro");
		item.setType(StockItemTypeEnum.PART);
		item.setQuantity(10);
		item.setPendingDemand(0);
		item.setCostPrice(new BigDecimal("10.00"));
		item.setSalePrice(new BigDecimal("20.00"));
		return item;
	}
}
