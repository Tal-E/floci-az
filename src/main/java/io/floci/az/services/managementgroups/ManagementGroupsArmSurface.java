package io.floci.az.services.managementgroups;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The ARM resource types {@link ManagementGroupHandler} serves, all tenant-scoped. */
@ApplicationScoped
public class ManagementGroupsArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Management";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            new ArmResourceType(NAMESPACE, "managementGroups", ArmScopeKind.TENANT, LocationPolicy.NONE),
            new ArmResourceType(NAMESPACE, "managementGroups/settings", ArmScopeKind.TENANT, LocationPolicy.NONE),
            new ArmResourceType(NAMESPACE, "managementGroups/subscriptions", ArmScopeKind.TENANT, LocationPolicy.NONE))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
