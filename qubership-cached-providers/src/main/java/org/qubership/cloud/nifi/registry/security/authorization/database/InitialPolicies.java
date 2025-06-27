package org.qubership.cloud.nifi.registry.security.authorization.database;

import org.apache.nifi.registry.security.authorization.RequestAction;
import org.qubership.cloud.nifi.registry.security.authorization.database.model.PolicyKey;

import java.util.HashSet;
import java.util.Set;

public final class InitialPolicies {
    public static final Set<PolicyKey> ADMIN_POLICIES = new HashSet<>();
    static {
        //tenants (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey("/tenants", RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey("/tenants", RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey("/tenants", RequestAction.DELETE));
        //proxy (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey("/proxy", RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey("/proxy", RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey("/proxy", RequestAction.DELETE));
        //policies (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey("/policies", RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey("/policies", RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey("/policies", RequestAction.DELETE));
        //buckets (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey("/buckets", RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey("/buckets", RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey("/buckets", RequestAction.DELETE));
        //actuator (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey("/actuator", RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey("/actuator", RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey("/actuator", RequestAction.DELETE));
        //swagger (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey("/swagger", RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey("/swagger", RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey("/swagger", RequestAction.DELETE));
    }

    public static final Set<PolicyKey> NIFI_NODE_POLICIES = new HashSet<>();
    static {
        //proxy (R/W/D):
        NIFI_NODE_POLICIES.add(new PolicyKey("/proxy", RequestAction.READ));
        NIFI_NODE_POLICIES.add(new PolicyKey("/proxy", RequestAction.WRITE));
        NIFI_NODE_POLICIES.add(new PolicyKey("/proxy", RequestAction.DELETE));
        //buckets (R):
        NIFI_NODE_POLICIES.add(new PolicyKey("/buckets", RequestAction.READ));
    }

    private InitialPolicies() {
    }
}
