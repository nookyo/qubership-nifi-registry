package org.qubership.cloud.nifi.registry.security.authorization.database;

import org.apache.nifi.registry.security.authorization.RequestAction;
import org.qubership.cloud.nifi.registry.security.authorization.database.model.PolicyKey;

import java.util.HashSet;
import java.util.Set;

public final class InitialPolicies {
    public static final String TENANTS_RESOURCE = "/tenants";
    public static final String PROXY_RESOURCE = "/proxy";
    public static final String POLICIES_RESOURCE = "/policies";
    public static final String BUCKETS_RESOURCE = "/buckets";
    public static final String ACTUATOR_RESOURCE = "/actuator";
    public static final String SWAGGER_RESOURCE = "/swagger";
    public static final Set<PolicyKey> ADMIN_POLICIES = new HashSet<>();
    static {
        //tenants (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey(TENANTS_RESOURCE, RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey(TENANTS_RESOURCE, RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey(TENANTS_RESOURCE, RequestAction.DELETE));
        //proxy (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey(PROXY_RESOURCE, RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey(PROXY_RESOURCE, RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey(PROXY_RESOURCE, RequestAction.DELETE));
        //policies (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey(POLICIES_RESOURCE, RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey(POLICIES_RESOURCE, RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey(POLICIES_RESOURCE, RequestAction.DELETE));
        //buckets (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey(BUCKETS_RESOURCE, RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey(BUCKETS_RESOURCE, RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey(BUCKETS_RESOURCE, RequestAction.DELETE));
        //actuator (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey(ACTUATOR_RESOURCE, RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey(ACTUATOR_RESOURCE, RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey(ACTUATOR_RESOURCE, RequestAction.DELETE));
        //swagger (R/W/D):
        ADMIN_POLICIES.add(new PolicyKey(SWAGGER_RESOURCE, RequestAction.READ));
        ADMIN_POLICIES.add(new PolicyKey(SWAGGER_RESOURCE, RequestAction.WRITE));
        ADMIN_POLICIES.add(new PolicyKey(SWAGGER_RESOURCE, RequestAction.DELETE));
    }

    public static final Set<PolicyKey> NIFI_NODE_POLICIES = new HashSet<>();
    static {
        //proxy (R/W/D):
        NIFI_NODE_POLICIES.add(new PolicyKey(PROXY_RESOURCE, RequestAction.READ));
        NIFI_NODE_POLICIES.add(new PolicyKey(PROXY_RESOURCE, RequestAction.WRITE));
        NIFI_NODE_POLICIES.add(new PolicyKey(PROXY_RESOURCE, RequestAction.DELETE));
        //buckets (R):
        NIFI_NODE_POLICIES.add(new PolicyKey(BUCKETS_RESOURCE, RequestAction.READ));
    }

    private InitialPolicies() {
    }
}
