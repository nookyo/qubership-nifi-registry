package org.qubership.cloud.nifi.registry.security.authorization.database.model;

import org.apache.nifi.registry.security.authorization.RequestAction;

import java.util.Objects;

/**
 * Container class for pair resourceIdentifier and action, serving as key for Access Policy.
 */
public class PolicyKey {
    private String resourceIdentifier;
    private RequestAction action;

    /**
     * Creates policy key.
     * @param newResourceIdentifier resource identifier
     * @param newAction action
     */
    public PolicyKey(final String newResourceIdentifier, final RequestAction newAction) {
        this.resourceIdentifier = newResourceIdentifier;
        this.action = newAction;
    }

    /**
     * Gets resource identifier.
     * @return resource identifier
     */
    public String getResourceIdentifier() {
        return resourceIdentifier;
    }

    /**
     * Sets resource identifier.
     * @param newResourceIdentifier resource identifier
     */
    public void setResourceIdentifier(String newResourceIdentifier) {
        this.resourceIdentifier = newResourceIdentifier;
    }

    /**
     * Gets action.
     * @return action
     */
    public RequestAction getAction() {
        return action;
    }

    /**
     * Sets action.
     * @param newAction action
     */
    public void setAction(RequestAction newAction) {
        this.action = newAction;
    }

    /**
     * Compares this object with another object.
     * @param o object to compare with
     * @return true, if equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PolicyKey policyKey = (PolicyKey) o;
        return Objects.equals(resourceIdentifier, policyKey.resourceIdentifier) && action == policyKey.action;
    }

    /**
     * Gets hashcode for this object.
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(resourceIdentifier, action);
    }
}
