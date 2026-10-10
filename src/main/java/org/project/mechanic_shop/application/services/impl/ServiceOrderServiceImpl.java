package org.project.mechanic_shop.application.services.impl;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.application.services.*;
import org.project.mechanic_shop.domain.entities.mechanic_service.MechanicService;

import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.service_order_labor.ServiceOrderLabor;
import org.project.mechanic_shop.domain.entities.service_order_stock_item.ServiceOrderStockItem;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.entities.user.User;
import org.project.mechanic_shop.domain.entities.vehicle.Vehicle;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderCreateDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderLaborManDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderMetricsDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderStockItemManDto;
import org.project.mechanic_shop.domain.events.NewServiceOrderEvent;
import org.project.mechanic_shop.domain.events.ServiceOrderStatusChangedEvent;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.domain.enums.UserRoleEnum;
import org.project.mechanic_shop.application.ports.SagaInstanceRepositoryPort;
import org.project.mechanic_shop.application.ports.ServiceOrderRepositoryPort;
import org.project.mechanic_shop.application.saga.ServiceOrderSagaOrchestrator;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderSagaDto;
import org.project.mechanic_shop.domain.entities.saga.SagaInstance;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceOrderServiceImpl implements ServiceOrderService {

	private final ServiceOrderRepositoryPort serviceOrderRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final VehicleService vehicleService;
	private final UserService userService;
	private final StockItemService stockItemService;
	private final MechanicServiceService mechanicServiceCatalog;
	private final ServiceOrderSagaOrchestrator sagaOrchestrator;
	private final SagaInstanceRepositoryPort sagaRepository;

	private static final String SERVICE_ORDER_NOT_FOUND_MSG = "Service Order not found";

	@Override
	@Transactional
	public ServiceOrder createServiceOrder(ServiceOrderCreateDto dto) {
		log.info("Creating new Service Order for vehicle: {}", dto.vehicleExternalId());

		ServiceOrder serviceOrder = new ServiceOrder();
		serviceOrder.setVehicle(vehicleService.findByExternalId(dto.vehicleExternalId()));
		serviceOrder.setCustomerComplaint(dto.customerComplaint());
		serviceOrder.setOdometerReading(dto.odometerReading());
		serviceOrder.setStatus(ServiceOrderStatusEnum.RECEIVED);

		if (dto.mechanicExternalId() != null) {
			serviceOrder.setResponsibleMechanic(userService.findByExternalId(dto.mechanicExternalId()));
		}

		applyInitialItems(serviceOrder, dto);

		ServiceOrder savedOrder = serviceOrderRepository.save(serviceOrder);

		sagaOrchestrator.start(savedOrder);

		if (savedOrder.getResponsibleMechanic() != null) {
			eventPublisher.publishEvent(new NewServiceOrderEvent(savedOrder));
		}

		log.info("Service Order created successfully. ID: {}", savedOrder.getId());
		return savedOrder;
	}

	private void applyInitialItems(ServiceOrder order, ServiceOrderCreateDto dto) {
		boolean hasLabors = dto.labors() != null && !dto.labors().isEmpty();
		boolean hasParts  = dto.parts()  != null && !dto.parts().isEmpty();

		if (!hasLabors && !hasParts) return;

		BigDecimal total = BigDecimal.ZERO;

		if (hasLabors) total = total.add(addLabors(order, dto.labors()));
		if (hasParts)  total = total.add(addParts(order, dto.parts()));

		order.setBudgetTotalAmount(total);
	}

	private BigDecimal addLabors(ServiceOrder order, List<ServiceOrderLaborManDto> labors) {
		BigDecimal total = BigDecimal.ZERO;
		for (ServiceOrderLaborManDto laborDto : labors) {
			MechanicService mechanicService = mechanicServiceCatalog.findByExternalId(laborDto.mechanicServiceExternalId());
			BigDecimal unitPrice  = mechanicService.getPrice();
			BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(laborDto.quantity()));

			ServiceOrderLabor labor = new ServiceOrderLabor();
			labor.setMechanicService(mechanicService);
			labor.setQuantity(laborDto.quantity());
			labor.setUnitPrice(unitPrice);
			labor.setTotalPrice(totalPrice);

			order.addLabor(labor);
			total = total.add(totalPrice);
		}
		return total;
	}

	private BigDecimal addParts(ServiceOrder order, List<ServiceOrderStockItemManDto> parts) {
		BigDecimal total = BigDecimal.ZERO;
		for (ServiceOrderStockItemManDto partDto : parts) {
			StockItem stockItem = stockItemService.findByExternalId(partDto.partExternalId());
			BigDecimal unitPrice  = stockItem.getSalePrice();
			BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(partDto.quantity()));

			ServiceOrderStockItem orderPart = new ServiceOrderStockItem();
			orderPart.setStockItem(stockItem);
			orderPart.setStockItemType(stockItem.getType());
			orderPart.setQuantity(partDto.quantity());
			orderPart.setUnitPrice(unitPrice);
			orderPart.setTotalPrice(totalPrice);

			order.addStockItem(orderPart);
			total = total.add(totalPrice);
		}
		return total;
	}

	@Transactional
	@Override
	public ServiceOrder deliverVehicle(UUID externalId) {
		log.info("Action triggered: Delivering vehicle for OS {}", externalId);

		ServiceOrder order = serviceOrderRepository
			.findByExternalId(externalId)
			.orElseThrow(() -> new EntityNotFoundException(SERVICE_ORDER_NOT_FOUND_MSG));

		if (order.getStatus() != ServiceOrderStatusEnum.COMPLETED) {
			throw new IllegalStateException("Only COMPLETED orders can be delivered to the customer.");
		}

		ServiceOrderStatusEnum oldStatus = order.getStatus();

		order.setStatus(ServiceOrderStatusEnum.DELIVERED);

		ServiceOrder updatedOrder = serviceOrderRepository.save(order);

		eventPublisher.publishEvent(
			new ServiceOrderStatusChangedEvent(
				order,
				oldStatus,
				ServiceOrderStatusEnum.DELIVERED
			)
		);

		return updatedOrder;
	}

	@Override
	@Transactional(readOnly = true)
	public ServiceOrder findByExternalId(UUID externalId) {
		ServiceOrder order = serviceOrderRepository
			.findByExternalId(externalId)
			.orElseThrow(() -> {
				log.warn("Service Order not found. Target External ID: {}", externalId);
				return new EntityNotFoundException("Service Order not found for External ID: " + externalId);
			});

		assertCustomerOwnsOrder(order);

		return order;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<ServiceOrder> search(
		String licensePlate,
		ServiceOrderStatusEnum status,
		Pageable pageable,
		User mechanic
	) {
		log.info("Searching service orders with filters - licensePlate: {}, status: {}", licensePlate, status);

		User ownerFilter = resolveAuthenticatedCustomer();

		return serviceOrderRepository.search(licensePlate, status, mechanic, ownerFilter, pageable);
	}

	private void assertCustomerOwnsOrder(ServiceOrder order) {
		if (!isAuthenticatedAsCustomer()) return;

		String authenticatedEmail = SecurityContextHolder.getContext().getAuthentication().getName();
		String ownerEmail = order.getVehicle() != null && order.getVehicle().getOwner() != null
			? order.getVehicle().getOwner().getEmail()
			: null;

		if (ownerEmail == null || !ownerEmail.equalsIgnoreCase(authenticatedEmail)) {
			throw new AccessDeniedException("You can only access your own service orders.");
		}
	}

	private User resolveAuthenticatedCustomer() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (!isAuthenticatedAsCustomer(auth)) return null;

		return userService.findByEmail(auth.getName());
	}

	private boolean isAuthenticatedAsCustomer() {
		return isAuthenticatedAsCustomer(SecurityContextHolder.getContext().getAuthentication());
	}

	private boolean isAuthenticatedAsCustomer(Authentication auth) {
		return auth != null &&
			auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + UserRoleEnum.CUSTOMER));
	}

	@Override
	@Transactional(readOnly = true)
	public Page<ServiceOrder> listActiveOrders(Pageable pageable) {
		log.info("Listing active service orders with business ordering");

		List<ServiceOrderStatusEnum> excluded = List.of(
			ServiceOrderStatusEnum.COMPLETED,
			ServiceOrderStatusEnum.DELIVERED,
			ServiceOrderStatusEnum.CANCELED
		);

		Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

		return serviceOrderRepository.findActiveOrders(
			excluded,
			ServiceOrderStatusEnum.IN_PROGRESS,
			ServiceOrderStatusEnum.PENDING_APPROVAL,
			ServiceOrderStatusEnum.DIAGNOSIS,
			ServiceOrderStatusEnum.RECEIVED,
			unsorted
		);
	}

	@Override
	@Transactional(readOnly = true)
	public ServiceOrderSagaDto getSaga(UUID externalId) {
		ServiceOrder order = findByExternalId(externalId);

		SagaInstance saga = sagaRepository
			.findByServiceOrderId(order.getExternalId())
			.orElseThrow(() -> new EntityNotFoundException("Saga not found for Service Order: " + externalId));

		return ServiceOrderSagaDto.from(saga, sagaRepository.findLog(saga.getId()));
	}

	@Override
	@Transactional(readOnly = true)
	public ServiceOrderMetricsDto getMetrics() {
		return new ServiceOrderMetricsDto(
			serviceOrderRepository.findAverageCompletionDays(),
			serviceOrderRepository.countCompletedOrders()
		);
	}
}
