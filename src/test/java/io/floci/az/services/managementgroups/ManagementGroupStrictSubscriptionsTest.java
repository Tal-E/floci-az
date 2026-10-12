package io.floci.az.services.managementgroups;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ManagementGroupStrictSubscriptionsTest.StrictProfile.class)
@DisplayName("Management groups: membership applies the subscription checks of the ARM gate")
class ManagementGroupStrictSubscriptionsTest {

    private static final String BASE = "/providers/Microsoft.Management";
    private static final String API = "?api-version=2023-04-01";
    private static final String KNOWN = "aaaaaaaa-3333-3333-3333-000000000001";
    private static final String DISABLED = "aaaaaaaa-3333-3333-3333-000000000002";
    private static final String OTHER_TENANT = "aaaaaaaa-3333-3333-3333-000000000003";

    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.parity-overrides.strict-subscriptions", "true",
                "floci-az.tenants[0].id", "bbbbbbbb-0000-0000-0000-000000000002",
                "floci-az.subscriptions[0].id", KNOWN,
                "floci-az.subscriptions[1].id", DISABLED,
                "floci-az.subscriptions[1].state", "Disabled",
                "floci-az.subscriptions[2].id", OTHER_TENANT,
                "floci-az.subscriptions[2].tenant-id", "bbbbbbbb-0000-0000-0000-000000000002");
        }
    }

    @Test
    void membershipWritesCheckTheSubscription() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{}")
                .when().put(BASE + "/managementGroups/strict-mg" + API).then().statusCode(200);

        given().when().put(BASE + "/managementGroups/strict-mg/subscriptions/" + KNOWN + API)
                .then().statusCode(200);
        given().when().put(BASE + "/managementGroups/strict-mg/subscriptions/cccccccc-0000-0000-0000-000000000009" + API)
                .then().statusCode(404).body("error.code", equalTo("SubscriptionNotFound"));
        given().when().put(BASE + "/managementGroups/strict-mg/subscriptions/" + DISABLED + API)
                .then().statusCode(409).body("error.code", equalTo("ReadOnlyDisabledSubscription"));
        given().when().put(BASE + "/managementGroups/strict-mg/subscriptions/" + OTHER_TENANT + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidRequest"));
        // The other tenant's subscription is not part of this tenant's hierarchy.
        given().when().post(BASE + "/getEntities" + API)
                .then().statusCode(200)
                .body("value.find { it.name == '" + OTHER_TENANT + "' }", equalTo(null))
                .body("value.find { it.name == '" + KNOWN + "' }.properties.parent.id", equalTo(BASE + "/managementGroups/strict-mg"));
    }
}
