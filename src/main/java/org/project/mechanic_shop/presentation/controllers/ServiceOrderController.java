package org.project.mechanic_shop.presentation.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderCreateDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderMetricsDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderSagaDto;
import org.project.mechanic_shop.domain.dto.service_order_dto.ServiceOrderStatusDto;
import org.project.mechanic_shop.domain.dto.responses.ApiResponse;
import org.project.mechanic_shop.application.mappers.ServiceOrderMapper;
import org.project.mechanic_shop.domain.entities.service_order.ServiceOrder;
import org.project.mechanic_shop.domain.entities.user.User;
import org.project.mechanic_shop.domain.enums.ServiceOrderStatusEnum;
import org.project.mechanic_shop.application.services.ServiceOrderService;
import org.project.mechanic_shop.application.services.UserService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/service-orders")
@RequiredArgsConstructor
@Tag(name = "Service Orders")
@Slf4j
public class ServiceOrderController {

	private final ServiceOrderService service;
	private final ServiceOrderMapper mapper;

	private static final String SUCCESS_MESSAGE = "success";
	private final UserService userService;

	@Operation(summary = "List active service orders", description = "Returns a paginated list of active orders (excludes COMPLETED, DELIVERED and CANCELED). Fixed sort: IN_PROGRESS → PENDING_APPROVAL → DIAGNOSIS → RECEIVED, oldest first within each group.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated result")
	@GetMapping
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC')")
	public ResponseEntity<ApiResponse> listActive(
		@ParameterObject @PageableDefault(size = 10) Pageable pageable
	) {
		log.info("Listing active service orders");

		Page<ServiceOrder> orders = service.listActiveOrders(pageable);
		var dto = orders.map(mapper::toShortDto);

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, dto));
	}

	@Operation(summary = "Service order metrics", description = "Returns the average actual completion days across all finished service orders. Returns null when no orders have been completed yet.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Metrics returned")
	@GetMapping("/metrics")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC')")
	public ResponseEntity<ApiResponse> getMetrics() {
		log.info("Fetching service order metrics");
		ServiceOrderMetricsDto dto = service.getMetrics();
		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, dto));
	}

	@Operation(summary = "Find service order by ID")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Service order found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Service order not found")
	@GetMapping("/{id}")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC', 'CUSTOMER')")
	public ResponseEntity<ApiResponse> findById(@PathVariable UUID id) {
		log.info("Find service order by External ID: {}", id);

		var serviceOrder = service.findByExternalId(id);
		var dto = mapper.toDto(serviceOrder);

		return ResponseEntity.ok().body(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, dto));
	}

	@Operation(summary = "Get current status of a service order", description = "Lightweight endpoint returning only the status and deadline fields — no labors, parts or full details.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status returned")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Service order not found")
	@GetMapping("/{id}/status")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC', 'CUSTOMER')")
	public ResponseEntity<ApiResponse> getStatus(@PathVariable UUID id) {
		log.info("Get status for service order: {}", id);

		ServiceOrderStatusDto dto = mapper.toStatusDto(service.findByExternalId(id));

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, dto));
	}

	@Operation(
		summary = "Saga state and history of a service order",
		description = "Shows where the distributed transaction of the order is (diagnosis, budget, approval, payment, execution), whether it completed or was compensated, and every step it went through."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Saga returned")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Service order not found")
	@GetMapping("/{id}/saga")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC')")
	public ResponseEntity<ApiResponse> getSaga(@PathVariable UUID id) {
		log.info("Get saga for service order: {}", id);

		ServiceOrderSagaDto dto = service.getSaga(id);

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, dto));
	}

	@Operation(summary = "Open a new service order", description = "Creates a service order with RECEIVED status for the given vehicle and starts its Saga: the order is queued for diagnosis in the Execution Service. Optionally assigns a mechanic.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Service order created — returns the new order's external ID")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vehicle or mechanic not found")
	@PostMapping("/create")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST')")
	public ResponseEntity<ApiResponse> create(@RequestBody @Valid ServiceOrderCreateDto dto) {
		log.info("Try create service order with parameters: {}", dto);

		var serviceOrderCreated = service.createServiceOrder(dto);

		return ResponseEntity.status(HttpStatus.CREATED).body(
			new ApiResponse(HttpStatus.CREATED.value(), SUCCESS_MESSAGE, serviceOrderCreated.getExternalId())
		);
	}

	@Operation(summary = "Deliver vehicle to customer", description = "Closes the service order lifecycle by moving it to DELIVERED. Order must be COMPLETED.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vehicle delivered — order is now DELIVERED")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Order is not COMPLETED")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Service order not found")
	@PostMapping("/{id}/deliver")
	@PreAuthorize("hasAnyRole('RECEPTIONIST', 'ADMIN')")
	public ResponseEntity<ApiResponse> deliverVehicle(@PathVariable UUID id) {
		log.info("Action triggered: Receptionist delivering vehicle for OS {}", id);

		ServiceOrder order = service.deliverVehicle(id);
		ServiceOrderDto dto = mapper.toDto(order);

		return ResponseEntity.ok().body(
			new ApiResponse(
				HttpStatus.OK.value(),
				"Vehicle delivered successfully! OS lifecycle is now closed (DELIVERED).",
				dto
			)
		);
	}

	@Operation(summary = "Search service orders", description = "Returns a paginated list of service orders filtered by license plate, status or responsible mechanic.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated result")
	@GetMapping("/search")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC', 'CUSTOMER')")
	public ResponseEntity<ApiResponse> search(
		@RequestParam(name = "licensePlate", required = false) String licensePlate,
		@RequestParam(name = "status", required = false) ServiceOrderStatusEnum status,
		@RequestParam(name = "mechanicId", required = false) UUID mechanicId,
		@ParameterObject @PageableDefault(
			size = 10,
			sort = "createdAt",
			direction = Sort.Direction.DESC
		) Pageable pageable
	) {
		log.info("Search service orders with filters");

		User mechanic = null;
		if (mechanicId != null) mechanic = userService.findByExternalId(mechanicId);

		Page<ServiceOrder> serviceOrders = service.search(licensePlate, status, pageable, mechanic);
		var listDto = serviceOrders.map(mapper::toShortDto);

		return ResponseEntity.ok().body(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, listDto));
	}
}
