package io.floci.az.services.policyinsights;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmRequestBody;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmTypeRegistry;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.core.directory.Subscription;
import io.floci.az.services.arm.ArmHandler;
import io.floci.az.services.policy.evaluation.PolicyEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An on-demand compliance scan, what {@code triggerEvaluation} starts in Azure: every resource the ARM
 * index lists in the scope (and every resource group) is evaluated against the assignments that apply,
 * and the results replace the stored states. The index entry names a resource but carries no
 * {@code properties}, so each resource is read in full through the router first, with the newest
 * api-version its type declares; a resource that cannot be read keeps its stored verdicts. Runs
 * synchronously, so the operation it starts is already complete when its status is polled.
 */
@ApplicationScoped
public class ComplianceScanner {

    private static final Logger LOG = Logger.getLogger(ComplianceScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ArmHandler armHandler;
    private final Instance<ArmRouter> router;
    private final ArmTypeRegistry types;
    private final AzureDirectory directory;
    private final ResourceGroups resourceGroups;
    private final PolicyEngine engine;
    private final ComplianceStore store;

    @Inject
    public ComplianceScanner(ArmHandler armHandler, Instance<ArmRouter> router, ArmTypeRegistry types,
                             AzureDirectory directory, ResourceGroups resourceGroups, PolicyEngine engine,
                             ComplianceStore store) {
        this.armHandler = armHandler;
        this.router = router;
        this.types = types;
        this.directory = directory;
        this.resourceGroups = resourceGroups;
        this.engine = engine;
        this.store = store;
    }

    /**
     * Scans one subscription, or one of its resource groups when {@code resourceGroup} is given; returns the
     * resources evaluated. {@code origin} is the request that triggered the scan, whose credentials the
     * per-resource reads carry.
     */
    public int scan(String subscriptionId, String resourceGroup, AzureRequest origin) {
        int evaluated = 0;
        for (ResourceGroup group : resourceGroups.list(subscriptionId)) {
            if (resourceGroup != null && !group.name().equalsIgnoreCase(resourceGroup)) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>(group.body());
            evaluate("/subscriptions/" + subscriptionId + "/resourceGroups/" + group.name(), body);
            evaluated++;
        }
        for (Map<String, Object> resource : armHandler.indexedResources(subscriptionId, resourceGroup, true)) {
            Map<String, Object> entry = new LinkedHashMap<>(ArmResources.stripInternal(resource));
            if (!(entry.get("id") instanceof String id)) {
                continue;
            }
            Map<String, Object> body = read(id, origin);
            if (body == null) {
                LOG.debugf("Policy compliance scan: %s could not be read in full, its stored verdicts are kept", id);
                continue;
            }
            evaluate(id, body);
            evaluated++;
        }
        LOG.infof("Policy compliance scan of %s%s evaluated %d resources", subscriptionId,
            resourceGroup == null ? "" : "/" + resourceGroup, evaluated);
        return evaluated;
    }

    /** Scans every subscription the directory knows, on behalf of {@code origin}. */
    public int scanAll(AzureRequest origin) {
        int evaluated = 0;
        for (Subscription subscription : directory.subscriptions()) {
            evaluated += scan(subscription.id(), null, origin);
        }
        return evaluated;
    }

    /** The resource as its handler serves it, with the newest api-version its declared type knows; null when unreadable. */
    private Map<String, Object> read(String id, AzureRequest origin) {
        if (origin == null || router == null || router.isUnsatisfied()) {
            return null;
        }
        ArmResourceType type = types.match(ArmResourceId.parse(id).orElse(null));
        List<String> apiVersions = type == null ? List.of() : types.apiVersions(type);
        if (apiVersions.isEmpty()) {
            return null;
        }
        try {
            Response current = router.get().dispatchInternal("GET", id, Map.of("api-version", apiVersions.getFirst()), origin);
            if (current.getStatus() != 200) {
                return null;
            }
            Object entity = current.getEntity();
            if (entity instanceof String text) {
                Object parsed = MAPPER.readValue(text, Object.class);
                return parsed instanceof Map<?, ?> ? MAPPER.convertValue(parsed, new TypeReference<>() { }) : null;
            }
            // A deep copy: the handler may have handed out the map it stores, and the evaluation fills computed fields.
            return entity == null ? null : MAPPER.convertValue(entity, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException | IOException e) {
            LOG.debugf(e, "Policy compliance scan: reading %s failed", id);
            return null;
        }
    }

    private void evaluate(String id, Map<String, Object> body) {
        ArmResourceId resourceId = ArmResourceId.parse(id).orElse(null);
        if (resourceId == null) {
            return;
        }
        ArmCall call = new ArmCall(null, resourceId, null, "PUT", null, ArmRequestBody.of(body), null);
        if (!PolicyEngine.evaluates(call)) {
            return;
        }
        // Read-only: a scan applies no append and must not write into the body its handler holds.
        PolicyEngine.Outcome outcome = engine.evaluate(new PolicyEngine.Request(call, body, null, Map.of(), false, true));
        store.forgetResource(id);
        store.record(resourceId, body, outcome.evaluations());
    }
}
