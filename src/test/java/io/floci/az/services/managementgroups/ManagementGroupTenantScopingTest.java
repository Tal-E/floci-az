package io.floci.az.services.managementgroups;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Under {@code parity-overrides.tenant-scoping} the caller's token tenant picks the hierarchy, as it picks the
 * subscriptions {@code GET /subscriptions} lists: each configured tenant has its own root group, its own
 * groups and its own subscriptions, and neither hierarchy shows in the other.
 */
@QuarkusTest
@TestProfile(ManagementGroupTenantScopingTest.TenantScoping.class)
@DisplayName("Management groups: one hierarchy per tenant under tenant scoping")
class ManagementGroupTenantScopingTest {

    private static final String BASE = "/providers/Microsoft.Management";
    private static final String API = "?api-version=2023-04-01";
    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String TEAM_TENANT = "aaaaaaaa-0000-0000-0000-0000000000b7";
    private static final String TEAM_SUB = "aaaaaaaa-1111-0000-0000-0000000000b7";

    public static class TenantScoping implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.parity-overrides.tenant-scoping", "true",
                "floci-az.tenants[0].id", TEAM_TENANT,
                "floci-az.tenants[0].display-name", "Team tenant",
                "floci-az.tenants[0].default-domain", "team.example",
                "floci-az.subscriptions[0].id", TEAM_SUB,
                "floci-az.subscriptions[0].tenant-id", TEAM_TENANT);
        }
    }

    /** An unsigned token for {@code tid}: the emulator reads the claim as a hint and never verifies it. */
    private static String bearer(String tid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(("{\"tid\":\"" + tid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void eachTenantManagesItsOwnHierarchy() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{}")
                .when().put(BASE + "/managementGroups/shared" + API).then().statusCode(200)
                .body("properties.tenantId", equalTo(DEFAULT_TENANT));

        // The team tenant starts from its own root group and does not see the default tenant's group.
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().get(BASE + "/managementGroups" + API).then().statusCode(200)
                .body("value.name", contains(TEAM_TENANT));
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().get(BASE + "/managementGroups/shared" + API).then().statusCode(404);
        given().header("Authorization", bearer(TEAM_TENANT)).contentType("application/json").body("{}")
                .when().put(BASE + "/managementGroups/team-prod" + API).then().statusCode(200)
                .body("properties.tenantId", equalTo(TEAM_TENANT))
                .body("properties.details.parent.id", equalTo(BASE + "/managementGroups/" + TEAM_TENANT));
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().put(BASE + "/managementGroups/team-prod/subscriptions/" + TEAM_SUB + API).then().statusCode(200)
                .body("properties.tenant", equalTo(TEAM_TENANT));
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().post(BASE + "/getEntities" + API).then().statusCode(200)
                .body("value.find { it.name == '" + TEAM_SUB + "' }.properties.parent.id", equalTo(BASE + "/managementGroups/team-prod"))
                .body("value.name", not(hasItem("shared")));
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().post(BASE + "/startTenantBackfill" + API).then().statusCode(200)
                .body("tenantId", equalTo(TEAM_TENANT));

        // The default tenant's hierarchy is untouched: no team group, and the team subscription cannot be placed in it.
        given().when().get(BASE + "/managementGroups" + API).then().statusCode(200)
                .body("value.name", not(hasItem("team-prod")));
        given().when().put(BASE + "/managementGroups/shared/subscriptions/" + TEAM_SUB + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidRequest"));
        given().when().post(BASE + "/getEntities" + API).then().statusCode(200)
                .body("value.name", not(hasItem(TEAM_SUB)));
    }
}
