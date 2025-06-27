package org.qubership.cloud.nifi.registry.security.authorization.database;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.nifi.registry.security.authorization.AccessPolicy;
import org.apache.nifi.registry.security.authorization.AccessPolicyProviderInitializationContext;
import org.apache.nifi.registry.security.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.registry.security.authorization.ConfigurableAccessPolicyProvider;
import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.RequestAction;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserGroupProvider;
import org.apache.nifi.registry.security.authorization.UserGroupProviderLookup;
import org.apache.nifi.registry.security.authorization.annotation.AuthorizerContext;
import org.apache.nifi.registry.security.authorization.exception.AuthorizationAccessException;
import org.apache.nifi.registry.security.authorization.exception.UninheritableAuthorizationsException;
import org.apache.nifi.registry.security.exception.SecurityProviderCreationException;
import org.apache.nifi.registry.security.exception.SecurityProviderDestructionException;
import org.apache.nifi.registry.util.PropertyValue;
import org.qubership.cloud.nifi.registry.security.authorization.database.mappers.AccessPolicyRowMapper;
import org.qubership.cloud.nifi.registry.security.authorization.database.model.PolicyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CachedDatabaseAccessPolicyProvider
        implements ConfigurableAccessPolicyProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedDatabaseAccessPolicyProvider.class);
    public static final String PROP_USER_GROUP_PROVIDER = "User Group Provider";

    /**
     * The prefix of a property from an AuthorizerConfigurationContext that specifies a NiFi Identity.
     */
    public static final String PROP_NIFI_IDENTITY_PREFIX = "NiFi Identity ";

    /**
     * The name of the property from an AuthorizerConfigurationContext that specifies the initial admin identity.
     */
    public static final String PROP_INITIAL_ADMIN_IDENTITY = "Initial Admin Identity";

    /**
     * A Pattern for identifying properties that represent NiFi Identities.
     */
    public static final Pattern NIFI_IDENTITY_PATTERN = Pattern.compile(PROP_NIFI_IDENTITY_PREFIX + "\\S+");

    /**
     * The name of the property from AuthorizerConfigurationContext
     * that specifies a name of a group for NiFi Identities.
     */
    public static final String PROP_NIFI_GROUP_NAME = "NiFi Group Name";

    private UserGroupProvider userGroupProvider;
    private UserGroupProviderLookup userGroupProviderLookup;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ReadWriteLock dbLock = new ReentrantReadWriteLock();

    private final Map<PolicyKey, String> accessPolicyCacheByResource = new HashMap<>();
    private final Map<String, AccessPolicy> accessPolicyCacheById = new HashMap<>();
    private boolean accessPolicyCacheLoaded = false;

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private final AccessPolicyRowMapper accessPolicyRowMapper = new AccessPolicyRowMapper();

    /**
     * Sets data source.
     * @param newDataSource datasource to set
     */
    @AuthorizerContext
    public void setDataSource(final DataSource newDataSource) {
        this.dataSource = newDataSource;
    }


    /**
     * Initializes provider.
     * @param initializationContext context
     * @throws SecurityProviderCreationException
     */
    public final void initialize(AccessPolicyProviderInitializationContext initializationContext)
            throws SecurityProviderCreationException {
        LOGGER.debug("Initializing {}", this.getClass().getCanonicalName());
        this.userGroupProviderLookup = initializationContext.getUserGroupProviderLookup();
        this.doInitialize(initializationContext);
        LOGGER.debug("Done initializing {}", this.getClass().getCanonicalName());
    }

    /**
     * Configures provider before usage.
     * @param configurationContext context
     * @throws SecurityProviderCreationException
     */
    public final void onConfigured(AuthorizerConfigurationContext configurationContext)
            throws SecurityProviderCreationException {
        try {
            LOGGER.debug("Configuring {}", this.getClass().getCanonicalName());
            PropertyValue userGroupProviderIdentifier = configurationContext.getProperty("User Group Provider");
            if (!userGroupProviderIdentifier.isSet()) {
                throw new SecurityProviderCreationException("The user group provider must be specified.");
            } else {
                this.userGroupProvider = this.userGroupProviderLookup.
                        getUserGroupProvider(userGroupProviderIdentifier.getValue());
                if (this.userGroupProvider == null) {
                    throw new SecurityProviderCreationException(
                            "Unable to locate user group provider with identifier '" +
                                    userGroupProviderIdentifier.getValue() + "'");
                } else {
                    this.doOnConfigured(configurationContext);
                    LOGGER.debug("Done configuring {}", this.getClass().getCanonicalName());
                }
            }
        } catch (Exception e) {
            throw new SecurityProviderCreationException(e);
        }
    }

    /**
     * Configures provider before usage.
     * @param configurationContext context
     * @throws SecurityProviderCreationException
     */
    protected void doOnConfigured(AuthorizerConfigurationContext configurationContext)
            throws SecurityProviderCreationException {
        //initial admin:
        PropertyValue initialAdminIdentProp = configurationContext.getProperty(PROP_INITIAL_ADMIN_IDENTITY);
        String initialAdminIdentity = initialAdminIdentProp.isSet() ? initialAdminIdentProp.getValue() : null;
        if (StringUtils.isNotBlank(initialAdminIdentity)) {
            //create initial admin policies
            configureUserPolicies(initialAdminIdentity, InitialPolicies.ADMIN_POLICIES);
        }
        //nifi identities:
        for (final Map.Entry<String, String> entry : configurationContext.getProperties().entrySet()) {
            final Matcher matcher = NIFI_IDENTITY_PATTERN.matcher(entry.getKey());
            if (matcher.matches() && !StringUtils.isBlank(entry.getValue())) {
                //create initial NiFi identities policies
                configureUserPolicies(entry.getValue(), InitialPolicies.NIFI_NODE_POLICIES);
            }
        }
        //nifi group:
        PropertyValue nifiGroupNameProp = configurationContext.getProperty(PROP_NIFI_GROUP_NAME);
        String nifiGroupName = (nifiGroupNameProp != null && nifiGroupNameProp.isSet()) ?
                nifiGroupNameProp.getValue() : null;

        if (StringUtils.isNotBlank(nifiGroupName)) {
            //create initial NiFi Group policies
            configureGroupPolicies(nifiGroupName, InitialPolicies.NIFI_NODE_POLICIES);
        }
    }

    private void configureUserPolicies(String userIdentity, Set<PolicyKey> policies) {
        User user = userGroupProvider.getUserByIdentity(userIdentity);
        if (user == null) {
            //user not found
            throw new SecurityProviderCreationException("Unable to lookup user with identity = '" + userIdentity +
                    "' to set up initial policies");
        }

        for (PolicyKey key : policies) {
            AccessPolicy ap = getAccessPolicy(key.getResourceIdentifier(), key.getAction());
            if (ap == null) {
                //not found, then create new one:
                ap = new AccessPolicy.Builder().
                        identifierGenerateRandom().
                        resource(key.getResourceIdentifier()).
                        action(key.getAction()).
                        addUser(user.getIdentifier()).
                        build();
                addAccessPolicy(ap);
            } else {
                if (ap.getUsers() == null || !ap.getUsers().contains(user.getIdentifier())) {
                    //update policy:
                    AccessPolicy updatedPolicy = new AccessPolicy.Builder(ap).
                            addUser(user.getIdentifier()).
                            build();
                    updateAccessPolicy(updatedPolicy);
                }
            }
        }
    }

    private void configureGroupPolicies(String groupName, Set<PolicyKey> policies) {
        Validate.notBlank(groupName);
        Group targetGroup = getGroupByName(groupName);

        for (PolicyKey key : policies) {
            AccessPolicy ap = getAccessPolicy(key.getResourceIdentifier(), key.getAction());
            if (ap == null) {
                //not found, then create new one:
                ap = new AccessPolicy.Builder().
                        identifierGenerateRandom().
                        resource(key.getResourceIdentifier()).
                        action(key.getAction()).
                        addGroup(targetGroup.getIdentifier()).
                        build();
                addAccessPolicy(ap);
            } else {
                if (ap.getUsers() == null || !ap.getGroups().contains(targetGroup.getIdentifier())) {
                    //update policy:
                    AccessPolicy updatedPolicy = new AccessPolicy.Builder(ap).
                            addGroup(targetGroup.getIdentifier()).
                            build();
                    updateAccessPolicy(updatedPolicy);
                }
            }
        }
    }

    private Group getGroupByName(String groupName) {
        Set<Group> allGroups = userGroupProvider.getGroups();
        Group targetGroup = null;
        if (allGroups != null) {
            for (Group group : allGroups) {
                if (groupName.equals(group.getName())) {
                    targetGroup = group;
                    break;
                }
            }
        }
        if (targetGroup == null) {
            //group not found
            throw new SecurityProviderCreationException("Unable to lookup group with name = '" + groupName +
                    "' to set up initial policies");
        }
        return targetGroup;
    }

    /**
     * Gets user group provider.
     * @return user group provider
     */
    public UserGroupProvider getUserGroupProvider() {
        return this.userGroupProvider;
    }

    /**
     * Initializes access policy provider.
     * @param initializationContext context
     * @throws SecurityProviderCreationException
     */
    protected void doInitialize(AccessPolicyProviderInitializationContext initializationContext)
            throws SecurityProviderCreationException {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Gets fingerprint for access policies configuration.
     * @return fingerprint for access policies
     * @throws AuthorizationAccessException
     */
    @Override
    public String getFingerprint() throws AuthorizationAccessException {
        throw new UnsupportedOperationException("Fingerprinting is not supported by this provider");
    }

    /**
     * Loads access policies configuration from fingerprint.
     * @param s fingerprint for access policies
     * @throws AuthorizationAccessException
     */
    @Override
    public void inheritFingerprint(String s) throws AuthorizationAccessException {
        throw new UnsupportedOperationException("Fingerprinting is not supported by this provider");
    }

    /**
     * Checks, if access policies configuration can be inherited from fingerprint.
     * @param s fingerprint for access policies
     * @throws AuthorizationAccessException
     */
    @Override
    public void checkInheritability(String s)
            throws AuthorizationAccessException, UninheritableAuthorizationsException {
        throw new UnsupportedOperationException("Fingerprinting is not supported by this provider");
    }

    /**
     * Adds access policy.
     * @param accessPolicy access policy to add
     * @return created access policy
     * @throws AuthorizationAccessException
     */
    @Override
    public AccessPolicy addAccessPolicy(AccessPolicy accessPolicy) throws AuthorizationAccessException {
        Objects.requireNonNull(accessPolicy);
        PolicyKey pk = new PolicyKey(accessPolicy.getResource(), accessPolicy.getAction());

        final String sql =
                "with ins as (insert into app_policy(identifier, resource, action) " +
                        "values (?, ?, ?) returning identifier), " +
                "grps as (insert into app_policy_group (policy_identifier, group_identifier) " +
                "         select ins.identifier, group_ids.col from ins, unnest(?::text[]) group_ids(col))" +
                "insert into app_policy_user (policy_identifier, user_identifier) " +
                "         select ins.identifier, user_ids.col from ins, unnest(?::text[]) user_ids(col)";
        String[] groups = null;
        if (accessPolicy.getGroups() != null) {
            groups = accessPolicy.getGroups().toArray(new String[0]);
        }
        String[] users = null;
        if (accessPolicy.getUsers() != null) {
            users = accessPolicy.getUsers().toArray(new String[0]);
        }

        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final int rowsUpdated = jdbcTemplate.update(sql,
                    accessPolicy.getIdentifier(),
                    accessPolicy.getResource(),
                    accessPolicy.getAction().toString(),
                    groups,
                    users);
            accessPolicyCacheById.put(accessPolicy.getIdentifier(), accessPolicy);
            accessPolicyCacheByResource.put(pk, accessPolicy.getIdentifier());
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return accessPolicy;
    }

    /**
     * Updates access policy.
     * @param accessPolicy access policy to update
     * @return either null, if not found or updated access policy
     * @throws AuthorizationAccessException
     */
    @Override
    public AccessPolicy updateAccessPolicy(AccessPolicy accessPolicy) throws AuthorizationAccessException {
        Objects.requireNonNull(accessPolicy);
        PolicyKey pk = new PolicyKey(accessPolicy.getResource(), accessPolicy.getAction());

        AccessPolicy existingAccessPolicy = getAccessPolicy(accessPolicy.getIdentifier());
        if (existingAccessPolicy == null) {
            return null;
        }

        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final String groupsUpdate =
                    "with grps_ins as (insert into app_policy_group (policy_identifier, group_identifier) " +
                            "         select ? identifier, group_ids.col from unnest(?::text[]) group_ids(col)" +
                            "         where not exists (select 1 from app_policy_group apg " +
                            "               where apg.policy_identifier = ? " +
                            "                   and apg.group_identifier = group_ids.col)) " +
                            "delete from app_policy_group apg  " +
                            "where apg.policy_identifier = ? and apg.group_identifier not in " +
                            "(select group_ids.col from unnest(?::text[]) group_ids(col))";

            String[] groups = null;
            if (accessPolicy.getGroups() != null) {
                groups = accessPolicy.getGroups().toArray(new String[0]);
            }
            int rowsUpdated = jdbcTemplate.update(groupsUpdate,
                    accessPolicy.getIdentifier(),
                    groups,
                    accessPolicy.getIdentifier(),
                    accessPolicy.getIdentifier(),
                    groups);


            final String usersUpdate =
                    "with usrs_ins as (insert into app_policy_user (policy_identifier, user_identifier) " +
                            "         select ? identifier, user_ids.col from unnest(?::text[]) user_ids(col)" +
                            "         where not exists (select 1 from app_policy_user apu " +
                            "               where apu.policy_identifier = ? and apu.user_identifier = user_ids.col)) " +
                            "delete from app_policy_user apu  " +
                            "where apu.policy_identifier = ? and apu.user_identifier not in " +
                            "(select user_ids.col from unnest(?::text[]) user_ids(col))";

            String[] users = null;
            if (accessPolicy.getUsers() != null) {
                users = accessPolicy.getUsers().toArray(new String[0]);
            }
            rowsUpdated = jdbcTemplate.update(usersUpdate,
                    accessPolicy.getIdentifier(),
                    users,
                    accessPolicy.getIdentifier(),
                    accessPolicy.getIdentifier(),
                    users);

            accessPolicyCacheById.put(accessPolicy.getIdentifier(), accessPolicy);
            accessPolicyCacheByResource.put(pk, accessPolicy.getIdentifier());
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return accessPolicy;
    }

    /**
     * Deletes access policy.
     * @param accessPolicy access policy to delete
     * @return either null, if not found, or access policy
     * @throws AuthorizationAccessException
     */
    @Override
    public AccessPolicy deleteAccessPolicy(AccessPolicy accessPolicy) throws AuthorizationAccessException {
        Objects.requireNonNull(accessPolicy);
        PolicyKey pk = new PolicyKey(accessPolicy.getResource(), accessPolicy.getAction());
        int rowsUpdated = 0;
        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final String sql = "delete from app_policy where identifier = ?";
            rowsUpdated = jdbcTemplate.update(sql, accessPolicy.getIdentifier());

            accessPolicyCacheById.remove(accessPolicy.getIdentifier());
            accessPolicyCacheByResource.remove(pk);
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        if (rowsUpdated <= 0) {
            return null;
        }

        return accessPolicy;
    }

    /**
     * Gets all access policies.
     * @return a set of access policies
     * @throws AuthorizationAccessException
     */
    @Override
    public Set<AccessPolicy> getAccessPolicies() throws AuthorizationAccessException {
        if (accessPolicyCacheLoaded) {
            Set<AccessPolicy> ap = null;
            try {
                cacheLock.readLock().lock();
                ap = new HashSet<>();
                for (Map.Entry<String, AccessPolicy> entry : accessPolicyCacheById.entrySet()) {
                    ap.add(entry.getValue());
                }
            } finally {
                cacheLock.readLock().unlock();
            }
            return ap;
        }
        final String policySql = "select ap.identifier, ap.resource, ap.action, " +
                "array_agg(usr.user_identifier) user_ids, " +
                "array_agg(grp.group_identifier) group_ids " +
                "from app_policy ap " +
                "left outer join app_policy_user usr on usr.policy_identifier = ap.identifier " +
                "left outer join app_policy_group grp on grp.policy_identifier = ap.identifier " +
                "group by ap.identifier, ap.resource, ap.action";
        Set<AccessPolicy> apSet = null;
        try {
            dbLock.readLock().lock();
            cacheLock.writeLock().lock();
            //load data from DB:
            final List<AccessPolicy> apList = jdbcTemplate.query(policySql, accessPolicyRowMapper);
            apSet = new HashSet<>(apList);
            //put to cache:
            for (AccessPolicy ap : apList) {
                accessPolicyCacheById.put(ap.getIdentifier(), ap);
                PolicyKey pk = new PolicyKey(ap.getResource(), ap.getAction());
                accessPolicyCacheByResource.put(pk, ap.getIdentifier());
            }
            accessPolicyCacheLoaded = true;
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.readLock().unlock();
        }

        return apSet;
    }

    /**
     * Gets access policy by identifier.
     * @param identifier identifier
     * @return access policy, if found, or null otherwise
     * @throws AuthorizationAccessException
     */
    @Override
    public AccessPolicy getAccessPolicy(String identifier) throws AuthorizationAccessException {
        Validate.notBlank(identifier);

        AccessPolicy ap = null;
        try {
            cacheLock.readLock().lock();
            ap = accessPolicyCacheById.get(identifier);
        } finally {
            cacheLock.readLock().unlock();
        }
        if (ap == null) {
            //try to load:
            ap = getAccessPolicyByIdFromDB(identifier);
        }
        return ap;
    }

    /**
     * Gets access policy by resource identifier and action.
     * @param resourceIdentifier resource
     * @param action action
     * @return access policy, if found, or null otherwise
     * @throws AuthorizationAccessException
     */
    @Override
    public AccessPolicy getAccessPolicy(String resourceIdentifier, RequestAction action)
            throws AuthorizationAccessException {
        Validate.notBlank(resourceIdentifier);

        PolicyKey pk = new PolicyKey(resourceIdentifier, action);
        AccessPolicy ap = null;
        try {
            cacheLock.readLock().lock();
            String apId = accessPolicyCacheByResource.get(pk);
            ap = accessPolicyCacheById.get(apId);
        } finally {
            cacheLock.readLock().unlock();
        }
        if (ap == null) {
            //try to load:
            ap = getAccessPolicyByResourceFromDB(resourceIdentifier, action, pk);
        }
        return ap;
    }

    private AccessPolicy getAccessPolicyByResourceFromDB(String resourceIdentifier, RequestAction action, PolicyKey pk)
            throws AuthorizationAccessException {
        final String policySql = "select ap.identifier, ap.resource, ap.action, " +
                "(select array_agg(user_identifier) from app_policy_user " +
                "where policy_identifier = ap.identifier) user_ids, " +
                "(select array_agg(group_identifier) from app_policy_group " +
                "where policy_identifier = ap.identifier) group_ids " +
                "from app_policy ap where ap.resource = ? and ap.action = ?";
        AccessPolicy ap = null;
        try {
            dbLock.readLock().lock();
            cacheLock.writeLock().lock();
            try {
                ap = jdbcTemplate.queryForObject(policySql,
                        accessPolicyRowMapper, resourceIdentifier, action.toString());
            } catch (EmptyResultDataAccessException ex) {
                return null;
            }

            if (ap == null) {
                return null;
            }
            accessPolicyCacheByResource.put(pk, ap.getIdentifier());
            accessPolicyCacheById.put(ap.getIdentifier(), ap);
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.readLock().unlock();
        }
        return ap;
    }


    private AccessPolicy getAccessPolicyByIdFromDB(String identifier) throws AuthorizationAccessException {
        final String policySql = "select ap.identifier, ap.resource, ap.action, " +
                "(select array_agg(user_identifier) from app_policy_user " +
                "where policy_identifier = ap.identifier) user_ids, " +
                "(select array_agg(group_identifier) from app_policy_group " +
                "where policy_identifier = ap.identifier) group_ids " +
                "from app_policy ap where ap.identifier = ?";
        AccessPolicy ap = null;

        try {
            dbLock.readLock().lock();
            cacheLock.writeLock().lock();
            try {
                ap = jdbcTemplate.queryForObject(policySql,
                        accessPolicyRowMapper, identifier);
            } catch (EmptyResultDataAccessException ex) {
                return null;
            }

            if (ap == null) {
                return null;
            }
            //put to resource cache as well:
            PolicyKey pk = new PolicyKey(ap.getResource(), ap.getAction());
            accessPolicyCacheByResource.put(pk, ap.getIdentifier());
            accessPolicyCacheById.put(ap.getIdentifier(), ap);
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.readLock().unlock();
        }
        return ap;
    }

    /**
     * Pre-destruction handler.
     * @throws SecurityProviderDestructionException
     */
    @Override
    public void preDestruction() throws SecurityProviderDestructionException {

    }
}
