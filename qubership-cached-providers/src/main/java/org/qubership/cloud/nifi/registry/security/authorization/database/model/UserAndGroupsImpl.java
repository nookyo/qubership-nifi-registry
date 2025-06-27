package org.qubership.cloud.nifi.registry.security.authorization.database.model;

import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserAndGroups;

import java.util.Set;

/**
 * Container for User and associated Groups.
 */
public class UserAndGroupsImpl implements UserAndGroups {

    private User user;
    private Set<Group> groups;

    /**
     * Creates instance of UserAndGroupsImpl.
     * @param newUser user
     * @param newGroups set of groups
     */
    public UserAndGroupsImpl(final User newUser, final Set<Group> newGroups) {
        this.user = newUser;
        this.groups = newGroups;
    }

    /**
     * Sets user.
     * @param newUser user to set
     */
    public void setUser(User newUser) {
        this.user = newUser;
    }

    /**
     * Sets groups.
     * @param newGroups groups to set
     */
    public void setGroups(Set<Group> newGroups) {
        this.groups = newGroups;
    }

    /**
     * Gets user.
     * @return user
     */
    @Override
    public User getUser() {
        return this.user;
    }

    /**
     * Gets set of groups for the user.
     * @return set of groups
     */
    @Override
    public Set<Group> getGroups() {
        return this.groups;
    }
}
