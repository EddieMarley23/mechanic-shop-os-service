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
import org.project.mechanic_shop.application.ports.StockItemRepositoryPort;
import org.project.mechanic_shop.application.validators.StockItemValidator;
import org.project.mechanic_shop.domain.entities.stock_item.StockItem;
import org.project.mechanic_shop.domain.enums.StockItemTypeEnum;

@ExtendWith(MockitoExtension.class)
class StockItemValidatorTest {

	private static final String CODE = "FLT-001";

	@Mock
	private StockItemRepositoryPort repository;

	private StockItemValidator validator;

	@BeforeEach
	void setUp() {
		validator = new StockItemValidator(repository);
	}

	private static StockItem item(Long id, String cost, String sale, int quantity) {
		var item = new StockItem();
		item.setId(id);
		item.setCode(CODE);
		item.setName("Oil filter");
		item.setType(StockItemTypeEnum.values()[0]);
		item.setQuantity(quantity);
		item.setCostPrice(new BigDecimal(cost));
		item.setSalePrice(new BigDecimal(sale));
		return item;
	}

	@Nested
	class Validate {

		@Test
		void acceptsNewItemWithUniqueCode() {
			when(repository.findByCode(CODE)).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(item(null, "10", "15", 5))).doesNotThrowAnyException();
		}

		@Test
		void rejectsNewItemWhenCodeIsTaken() {
			when(repository.findByCode(CODE)).thenReturn(Optional.of(item(3L, "10", "15", 5)));

			assertThatThrownBy(() -> validator.validate(item(null, "10", "15", 5)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A Part with this code already exists: " + CODE);
		}

		@Test
		void acceptsUpdateWhenTheCodeBelongsToTheSameItem() {
			when(repository.findByCode(CODE)).thenReturn(Optional.of(item(3L, "10", "15", 5)));

			assertThatCode(() -> validator.validate(item(3L, "10", "15", 5))).doesNotThrowAnyException();
		}

		@Test
		void acceptsUpdateWhenTheCodeIsFree() {
			when(repository.findByCode(CODE)).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(item(3L, "10", "15", 5))).doesNotThrowAnyException();
		}

		@Test
		void rejectsUpdateWhenTheCodeBelongsToAnotherItem() {
			when(repository.findByCode(CODE)).thenReturn(Optional.of(item(4L, "10", "15", 5)));

			assertThatThrownBy(() -> validator.validate(item(3L, "10", "15", 5)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("already exists");
		}

		@Test
		void rejectsSalePriceBelowCost() {
			when(repository.findByCode(CODE)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> validator.validate(item(null, "10", "9.99", 5)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Sale price cannot be lower than cost price.");
		}

		@Test
		void acceptsSalePriceEqualToCostAndZeroQuantity() {
			when(repository.findByCode(CODE)).thenReturn(Optional.empty());

			assertThatCode(() -> validator.validate(item(null, "10", "10", 0))).doesNotThrowAnyException();
		}

		@Test
		void rejectsNegativeQuantity() {
			when(repository.findByCode(CODE)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> validator.validate(item(null, "10", "15", -1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Stock quantity cannot be negative.");
		}
	}

	@Nested
	class ValidateUpdateEligibility {

		@Test
		void rejectsItemWithoutId() {
			assertThatThrownBy(() -> validator.validateUpdateEligibility(item(null, "10", "15", 5)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("You cannot update an object without an ID");
		}

		@Test
		void acceptsItemWithId() {
			assertThatCode(() -> validator.validateUpdateEligibility(item(3L, "10", "15", 5))).doesNotThrowAnyException();
		}
	}
}
