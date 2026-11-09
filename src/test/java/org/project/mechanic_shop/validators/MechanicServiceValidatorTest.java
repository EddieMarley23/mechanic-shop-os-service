package org.project.mechanic_shop.validators;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.project.mechanic_shop.application.ports.MechanicServiceRepositoryPort;
import org.project.mechanic_shop.application.validators.MechanicServiceValidator;
import org.project.mechanic_shop.domain.entities.mechanic_service.MechanicService;

@ExtendWith(MockitoExtension.class)
class MechanicServiceValidatorTest {

	@Mock
	private MechanicServiceRepositoryPort repository;

	private MechanicServiceValidator validator;

	@BeforeEach
	void setUp() {
		validator = new MechanicServiceValidator(repository);
	}

	private static MechanicService service(Long id, BigDecimal price, int minutes) {
		return new MechanicService(id, "Oil change", "Engine oil and filter", minutes, price);
	}

	@Nested
	class Validate {

		@Test
		void acceptsNewServiceWithUniqueName() {
			when(repository.findByName("Oil change")).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(service(null, BigDecimal.TEN, 30))).doesNotThrowAnyException();
		}

		@Test
		void rejectsNewServiceWhenNameIsTaken() {
			when(repository.findByName("Oil change")).thenReturn(Optional.of(service(7L, BigDecimal.TEN, 30)));

			assertThatThrownBy(() -> validator.validate(service(null, BigDecimal.TEN, 30)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A Service with this name already exists: Oil change");
		}

		@Test
		void acceptsUpdateWhenTheNameBelongsToTheSameService() {
			when(repository.findByName("Oil change")).thenReturn(Optional.of(service(7L, BigDecimal.TEN, 30)));

			assertThatCode(() -> validator.validate(service(7L, BigDecimal.TEN, 30))).doesNotThrowAnyException();
		}

		@Test
		void acceptsUpdateWhenTheNameIsFree() {
			when(repository.findByName("Oil change")).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(service(7L, BigDecimal.TEN, 30))).doesNotThrowAnyException();
		}

		@Test
		void rejectsUpdateWhenTheNameBelongsToAnotherService() {
			when(repository.findByName("Oil change")).thenReturn(Optional.of(service(8L, BigDecimal.TEN, 30)));

			assertThatThrownBy(() -> validator.validate(service(7L, BigDecimal.TEN, 30)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("already exists");
		}

		@Test
		void rejectsNegativePrice() {
			when(repository.findByName("Oil change")).thenReturn(Optional.empty());

			assertThatThrownBy(() -> validator.validate(service(null, new BigDecimal("-0.01"), 30)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Service price cannot be negative.");
		}

		@Test
		void acceptsFreeService() {
			when(repository.findByName("Oil change")).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(service(null, BigDecimal.ZERO, 0))).doesNotThrowAnyException();
		}

		@Test
		void rejectsNegativeEstimatedTime() {
			when(repository.findByName("Oil change")).thenReturn(Optional.empty());

			assertThatThrownBy(() -> validator.validate(service(null, BigDecimal.TEN, -1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Estimated time cannot be negative.");
		}
	}

	@Nested
	class ValidateUpdateEligibility {

		@Test
		void rejectsServiceWithoutId() {
			assertThatThrownBy(() -> validator.validateUpdateEligibility(service(null, BigDecimal.TEN, 30)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("You cannot update an object without an ID");
		}

		@Test
		void acceptsServiceWithId() {
			assertThatCode(() -> validator.validateUpdateEligibility(service(7L, BigDecimal.TEN, 30)))
				.doesNotThrowAnyException();
		}
	}
}
