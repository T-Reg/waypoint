package dev.waypoint.gateway.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.gateway.routing.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantValidatorTest {

    @Test
    void appliesDefaults() throws ValidationException {
        Tenant tenant = TenantValidator.validate("acme", new TenantSpec(List.of("/weather"), 600, null, null));
        assertThat(tenant.disabled()).isFalse();
        assertThat(tenant.auditRawPath()).isFalse();
        assertThat(tenant.allows("/weather/today")).isTrue();
        assertThat(tenant.allows("/weatherx")).isFalse();
        assertThat(tenant.toSpec().requestsPerMinute()).isEqualTo(600);
    }

    @Test
    void emptyAllowlistAllowsNothing() throws ValidationException {
        assertThat(TenantValidator.validate("acme", new TenantSpec(null, 1, true, true)).allows("/weather")).isFalse();
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> TenantValidator.validate("acme", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TenantValidator.validate("ACME!", new TenantSpec(List.of("/x/"), 0, null, null)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).hasSize(3));
        assertThatThrownBy(() -> TenantValidator.validate("acme", new TenantSpec(List.of(), null, null, null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TenantValidator.validate("acme", new TenantSpec(List.of(), 100_001, null, null))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TenantValidator.validate(null, new TenantSpec(List.of(), 1, null, null))).isInstanceOf(ValidationException.class);
    }
}
