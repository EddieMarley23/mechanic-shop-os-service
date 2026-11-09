package org.project.mechanic_shop.validators;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.project.mechanic_shop.application.ports.VehicleRepositoryPort;
import org.project.mechanic_shop.application.validators.VehicleValidator;
import org.project.mechanic_shop.domain.entities.vehicle.Vehicle;

@ExtendWith(MockitoExtension.class)
class VehicleValidatorTest {

	private static final String PLATE = "ABC1D23";

	@Mock
	private VehicleRepositoryPort repository;

	private VehicleValidator validator;

	@BeforeEach
	void setUp() {
		validator = new VehicleValidator(repository);
	}

	private static Vehicle vehicle(Long id) {
		return new Vehicle(id, PLATE, "Fiat", "Uno", 2015, "Red", null);
	}

	@Nested
	class Validate {

		@Test
		void acceptsNewVehicleWithUniquePlate() {
			when(repository.findByLicensePlate(PLATE)).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(vehicle(null))).doesNotThrowAnyException();
		}

		@Test
		void rejectsNewVehicleWhenPlateIsTaken() {
			when(repository.findByLicensePlate(PLATE)).thenReturn(Optional.of(vehicle(1L)));

			assertThatThrownBy(() -> validator.validate(vehicle(null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A Vehicle with this license plate already exists: " + PLATE);
		}

		@Test
		void acceptsUpdateWhenThePlateBelongsToTheSameVehicle() {
			when(repository.findByLicensePlate(PLATE)).thenReturn(Optional.of(vehicle(1L)));

			assertThatCode(() -> validator.validate(vehicle(1L))).doesNotThrowAnyException();
		}

		@Test
		void acceptsUpdateWhenThePlateIsFree() {
			when(repository.findByLicensePlate(PLATE)).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(vehicle(1L))).doesNotThrowAnyException();
		}

		@Test
		void rejectsUpdateWhenThePlateBelongsToAnotherVehicle() {
			when(repository.findByLicensePlate(PLATE)).thenReturn(Optional.of(vehicle(2L)));

			assertThatThrownBy(() -> validator.validate(vehicle(1L)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("already exists");
		}
	}

	@Nested
	class ValidateUpdateEligibility {

		@Test
		void rejectsVehicleWithoutId() {
			assertThatThrownBy(() -> validator.validateUpdateEligibility(vehicle(null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("You cannot update an object without an ID");
		}

		@Test
		void acceptsVehicleWithId() {
			assertThatCode(() -> validator.validateUpdateEligibility(vehicle(1L))).doesNotThrowAnyException();
		}
	}
}
