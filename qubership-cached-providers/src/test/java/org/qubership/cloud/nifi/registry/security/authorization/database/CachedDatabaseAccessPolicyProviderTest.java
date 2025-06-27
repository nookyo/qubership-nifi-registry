package org.qubership.cloud.nifi.registry.security.authorization.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.registry.security.authorization.AccessPolicy;
import org.apache.nifi.registry.security.authorization.AccessPolicyProviderInitializationContext;
import org.apache.nifi.registry.security.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.RequestAction;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserGroupProvider;
import org.apache.nifi.registry.security.authorization.UserGroupProviderInitializationContext;
import org.apache.nifi.registry.security.authorization.UserGroupProviderLookup;
import org.apache.nifi.registry.security.exception.SecurityProviderCreationException;
import org.apache.nifi.registry.util.PropertyValue;
import org.apache.nifi.registry.util.StandardPropertyValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.nifi.registry.security.authorization.database.mappers.MapperUtils;
import org.qubership.cloud.nifi.registry.security.authorization.database.model.PolicyKey;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("DockerBased")
@Testcontainers
public class CachedDatabaseAccessPolicyProviderTest {

    private static final String POSTGRES_IMAGE = "postgres:16.8";
    private static final String DB_NAME = "testDb";
    private static final String USER = "postgres";
    private static final String PWD = "password";
    private static DataSource dataSource;

    @Container
    private static JdbcDatabaseContainer postgresContainer;

    private final CachedDatabaseAccessPolicyProvider provider = new CachedDatabaseAccessPolicyProvider();
    private final CachedDatabaseUserGroupProvider userGroupProvider = new CachedDatabaseUserGroupProvider();

    static {
        postgresContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(DB_NAME)
                .withUsername(USER)
                .withPassword(PWD)
                .withInitScript("db/migration/postgres/V8__AddUserGroupPolicy.sql");
        postgresContainer.start();
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgresContainer.getJdbcUrl());
        hikariConfig.setUsername(USER);
        hikariConfig.setPassword(PWD);
        hikariConfig.setMinimumIdle(5);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setLeakDetectionThreshold(4000);
        dataSource = new HikariDataSource(hikariConfig);
    }

    @AfterAll
    public static void tearDown() {
        postgresContainer.stop();
    }

    @BeforeEach
    public void setUp() throws SQLException {
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("delete from app_policy where 1=1")) {
            prSt.executeUpdate();
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("delete from app_policy_group where 1=1")) {
            prSt.executeUpdate();
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("delete from app_policy_user where 1=1")) {
            prSt.executeUpdate();
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("delete from ugp_user where 1=1")) {
            prSt.executeUpdate();
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("delete from ugp_group where 1=1")) {
            prSt.executeUpdate();
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("delete from ugp_user_group where 1=1")) {
            prSt.executeUpdate();
        }

        userGroupProvider.setDataSource(dataSource);
        UserGroupProviderInitializationContext ugpInitializationContext =
        mock(UserGroupProviderInitializationContext.class);
        userGroupProvider.initialize(ugpInitializationContext);

        provider.setDataSource(dataSource);
        AccessPolicyProviderInitializationContext initializationContext =
                mock(AccessPolicyProviderInitializationContext.class);
        UserGroupProviderLookup lookup = mock(UserGroupProviderLookup.class);
        when(initializationContext.getUserGroupProviderLookup()).thenReturn(lookup);
        when(lookup.getUserGroupProvider("database-user-group-provider")).thenReturn(userGroupProvider);
        when(lookup.getUserGroupProvider("database-user-group-provider1")).thenReturn(null);
        provider.initialize(initializationContext);
    }

    private void configureUserGroupProvider(String... initialUserIdentities) {
        final Map<String, String> configProperties = new HashMap<>();

        for (int i = 0; i < initialUserIdentities.length; i++) {
            final String initialUserIdentity = initialUserIdentities[i];
            configProperties.put(CachedDatabaseUserGroupProvider.PROP_INITIAL_USER_IDENTITY_PREFIX + (i + 1),
                    initialUserIdentity);
        }

        final AuthorizerConfigurationContext configurationContext = mock(AuthorizerConfigurationContext.class);
        when(configurationContext.getProperties()).thenReturn(configProperties);

        userGroupProvider.onConfigured(configurationContext);
    }

    private void configureInvalidProvider(String initialAdminIdentity, String nifiGroupName, String... nifiIdentities) {
        final Map<String, String> configProperties = new HashMap<>();

        for (int i = 0; i < nifiIdentities.length; i++) {
            final String nifiIdentity = nifiIdentities[i];
            configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_NIFI_IDENTITY_PREFIX + (i + 1),
                    nifiIdentity);
        }
        configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_USER_GROUP_PROVIDER,
                "database-user-group-provider1");
        PropertyValue ugp = new StandardPropertyValue("database-user-group-provider1");
        configProperties.put("TestProperty 1", "Value 1");
        configProperties.put("TestProperty 2", "Value 2");

        PropertyValue nifiGroupNameProp = null;
        if (StringUtils.isNotEmpty(nifiGroupName)) {
            configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_NIFI_GROUP_NAME, nifiGroupName);
            nifiGroupNameProp = new StandardPropertyValue(nifiGroupName);
        } else {
            nifiGroupNameProp = new StandardPropertyValue("");
        }
        PropertyValue initialAdminProp = null;
        if (StringUtils.isNotEmpty(initialAdminIdentity)) {
            configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_INITIAL_ADMIN_IDENTITY, initialAdminIdentity);
            initialAdminProp = new StandardPropertyValue(initialAdminIdentity);
        } else {
            initialAdminProp = new StandardPropertyValue("");
        }

        final AuthorizerConfigurationContext configurationContext = mock(AuthorizerConfigurationContext.class);
        when(configurationContext.getProperties()).thenReturn(configProperties);
        when(configurationContext.getProperty("User Group Provider")).thenReturn(ugp);
        when(configurationContext.getProperty(CachedDatabaseAccessPolicyProvider.PROP_NIFI_GROUP_NAME)).
                thenReturn(nifiGroupNameProp);
        when(configurationContext.getProperty(CachedDatabaseAccessPolicyProvider.PROP_INITIAL_ADMIN_IDENTITY)).
                thenReturn(initialAdminProp);
        provider.onConfigured(configurationContext);
    }

    private void configureProvider(String initialAdminIdentity, String nifiGroupName, String... nifiIdentities) {
        final Map<String, String> configProperties = new HashMap<>();

        for (int i = 0; i < nifiIdentities.length; i++) {
            final String nifiIdentity = nifiIdentities[i];
            configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_NIFI_IDENTITY_PREFIX + (i + 1),
                    nifiIdentity);
        }
        configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_USER_GROUP_PROVIDER,
                "database-user-group-provider");
        PropertyValue ugp = new StandardPropertyValue("database-user-group-provider");
        configProperties.put("TestProperty 1", "Value 1");
        configProperties.put("TestProperty 2", "Value 2");

        PropertyValue nifiGroupNameProp = null;
        if (StringUtils.isNotEmpty(nifiGroupName)) {
            configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_NIFI_GROUP_NAME, nifiGroupName);
            nifiGroupNameProp = new StandardPropertyValue(nifiGroupName);
        } else {
            nifiGroupNameProp = new StandardPropertyValue("");
        }
        PropertyValue initialAdminProp = null;
        if (StringUtils.isNotEmpty(initialAdminIdentity)) {
            configProperties.put(CachedDatabaseAccessPolicyProvider.PROP_INITIAL_ADMIN_IDENTITY, initialAdminIdentity);
            initialAdminProp = new StandardPropertyValue(initialAdminIdentity);
        } else {
            initialAdminProp = new StandardPropertyValue("");
        }

        final AuthorizerConfigurationContext configurationContext = mock(AuthorizerConfigurationContext.class);
        when(configurationContext.getProperties()).thenReturn(configProperties);
        when(configurationContext.getProperty("User Group Provider")).thenReturn(ugp);
        when(configurationContext.getProperty(CachedDatabaseAccessPolicyProvider.PROP_NIFI_GROUP_NAME)).
                thenReturn(nifiGroupNameProp);
        when(configurationContext.getProperty(CachedDatabaseAccessPolicyProvider.PROP_INITIAL_ADMIN_IDENTITY)).
                thenReturn(initialAdminProp);
        provider.onConfigured(configurationContext);
    }

    //Utility methods:
    private Map<String, Integer> getPolicyCountByResourceForUser(String userIdentity) {
        User user = userGroupProvider.getUserByIdentity(userIdentity);
        Map<String, Integer> policiesCountByResource = new HashMap<>();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                    prepareStatement("select ap.resource, count(1) cnt " +
                            "from app_policy_user apu " +
                            "inner join app_policy ap on ap.identifier = apu.policy_identifier " +
                            "where apu.user_identifier = ? " +
                            "group by ap.resource")) {
                prSt.setString(1, user.getIdentifier());
                try (ResultSet rs = prSt.executeQuery()) {
                    while (rs.next()) {
                        policiesCountByResource.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
        } catch (SQLException ex) {
            Assertions.fail(ex);
        }
        return policiesCountByResource;
    }

    private static final String SELECT_POLICY_BY_ID = "select ap.resource, ap.action, " +
            "array_agg(apu.user_identifier) user_ids, " +
            "array_agg(apg.group_identifier) group_ids " +
            "from app_policy ap " +
            "left outer join app_policy_user apu on ap.identifier = apu.policy_identifier " +
            "left outer join app_policy_group apg on ap.identifier = apg.policy_identifier " +
            "where ap.identifier = ? " +
            "group by ap.resource, ap.action";

    private static void checkAccessPolicyInDB(AccessPolicy expectedPolicy,
                                            Set<String> expectedUserIds,
                                              Set<String> expectedGroupIds) {
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                    prepareStatement(SELECT_POLICY_BY_ID)) {
                prSt.setString(1, expectedPolicy.getIdentifier());
                try (ResultSet rs = prSt.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    Assertions.assertEquals(expectedPolicy.getResource(), rs.getString(1));
                    Assertions.assertEquals(expectedPolicy.getAction().toString(), rs.getString(2));
                    Set<String> userIds = MapperUtils.convertArrayToSet(rs.getArray(3));
                    if (expectedUserIds == null || expectedUserIds.isEmpty()) {
                        Assertions.assertEquals(0, userIds.size());
                    } else {
                        Assertions.assertEquals(expectedUserIds, userIds);
                    }
                    Set<String> groupIds = MapperUtils.convertArrayToSet(rs.getArray(4));
                    if (expectedGroupIds == null || expectedGroupIds.isEmpty()) {
                        Assertions.assertEquals(0, groupIds.size());
                    } else {
                        Assertions.assertEquals(expectedGroupIds, groupIds);
                    }
                    //only one row:
                    Assertions.assertFalse(rs.next());
                }
            }
        } catch (SQLException ex) {
            Assertions.fail(ex);
        }
    }

    private AccessPolicy createAccessPolicyInDB(String resource, RequestAction action,
                                                Set<String> userIdentities, Set<String> groupNames) {
        Set<String> userIds = new HashSet<>();
        for (String userIdentity : userIdentities) {
            User user = userGroupProvider.getUserByIdentity(userIdentity);
            userIds.add(user.getIdentifier());
        }
        Set<String> groupIds = new HashSet<>();
        Set<Group> allGroups = userGroupProvider.getGroups();
        for (Group group : allGroups) {
            if (groupNames.contains(group.getName())) {
                groupIds.add(group.getIdentifier());
            }
        }
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource(resource).
                action(action).
                addUsers(userIds).
                addGroups(groupIds).
                build();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                    prepareStatement("insert into app_policy (identifier, resource, action) values (?, ?, ?)")) {
                prSt.setString(1, ap.getIdentifier());
                prSt.setString(2, ap.getResource());
                prSt.setString(3, ap.getAction().toString());
                prSt.executeUpdate();
            }
            for (String userId : userIds) {
                try (PreparedStatement prSt = con.
                        prepareStatement(
                            "insert into app_policy_user (policy_identifier, user_identifier) values (?, ?)")) {
                    prSt.setString(1, ap.getIdentifier());
                    prSt.setString(2, userId);
                    prSt.executeUpdate();
                }
            }
            for (String groupId : groupIds) {
                try (PreparedStatement prSt = con.
                        prepareStatement(
                            "insert into app_policy_group (policy_identifier, group_identifier) values (?, ?)")) {
                    prSt.setString(1, ap.getIdentifier());
                    prSt.setString(2, groupId);
                    prSt.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            Assertions.fail(ex);
        }
        return ap;
    }

    private void assertAccessPoliciesEqual(AccessPolicy expected, AccessPolicy actual) {
        Assertions.assertEquals(expected.getIdentifier(), actual.getIdentifier());
        Assertions.assertEquals(expected.getResource(), actual.getResource());
        Assertions.assertEquals(expected.getAction(), actual.getAction());
        Assertions.assertEquals(expected.getUsers(), actual.getUsers());
        Assertions.assertEquals(expected.getGroups(), actual.getGroups());
    }

    private void assertDefaultAdminPoliciesExist(String resource,
                                                Set<String> expectedUsers,
                                                Map<PolicyKey, AccessPolicy> accessPolicyMapByPK) {
        assertDefaultAdminPolicyExists(resource, RequestAction.READ, expectedUsers, accessPolicyMapByPK);
        assertDefaultAdminPolicyExists(resource, RequestAction.WRITE, expectedUsers, accessPolicyMapByPK);
        assertDefaultAdminPolicyExists(resource, RequestAction.DELETE, expectedUsers, accessPolicyMapByPK);
    }

    private void assertDefaultAdminPolicyExists(String resource, RequestAction action,
                                                Set<String> expectedUsers,
                                                Map<PolicyKey, AccessPolicy> accessPolicyMapByPK) {
        PolicyKey pk = new PolicyKey(resource, action);
        Assertions.assertTrue(accessPolicyMapByPK.containsKey(pk));
        AccessPolicy actual = accessPolicyMapByPK.get(pk);
        Assertions.assertEquals(resource, actual.getResource());
        Assertions.assertEquals(action, actual.getAction());
        Assertions.assertEquals(expectedUsers, actual.getUsers());
        Assertions.assertEquals(Collections.emptySet(), actual.getGroups());
    }

    //Tests

    @Test
    public void initialAdminConfig() {
        configureUserGroupProvider("admin");
        configureProvider("admin", null);
        Map<String, Integer> policiesCountByResource = getPolicyCountByResourceForUser("admin");
        Assertions.assertEquals(6, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get(InitialPolicies.TENANTS_RESOURCE));
        Assertions.assertEquals(3, policiesCountByResource.get(InitialPolicies.PROXY_RESOURCE));
        Assertions.assertEquals(3, policiesCountByResource.get(InitialPolicies.POLICIES_RESOURCE));
        Assertions.assertEquals(3, policiesCountByResource.get(InitialPolicies.BUCKETS_RESOURCE));
        Assertions.assertEquals(3, policiesCountByResource.get(InitialPolicies.ACTUATOR_RESOURCE));
        Assertions.assertEquals(3, policiesCountByResource.get(InitialPolicies.SWAGGER_RESOURCE));
    }

    @Test
    public void initialAdminConfigEmptyUser() {
        configureUserGroupProvider("admin");
        configureProvider("", null);
        Map<String, Integer> policiesCountByResource = getPolicyCountByResourceForUser("admin");
        Assertions.assertEquals(0, policiesCountByResource.size());
    }

    @Test
    public void initialAdminConfigNonExistingUser() {
        configureUserGroupProvider("admin");
        Assertions.assertThrows(SecurityProviderCreationException.class,
                () -> configureProvider("admin1", null));
    }

    @Test
    public void nifiGroupConfig() throws SQLException {
        configureUserGroupProvider("admin");
        //create new group:
        Group nifiNodeGroup = new Group.Builder().
                name("nifiNodeGroup").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(nifiNodeGroup);
        configureProvider("admin", "nifiNodeGroup");
        Map<String, Integer> policiesCountByResource = new HashMap<>();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                    prepareStatement("select ap.resource, count(1) cnt " +
                            "from app_policy_group apg " +
                            "inner join app_policy ap on ap.identifier = apg.policy_identifier " +
                            "where apg.group_identifier = ? " +
                            "group by ap.resource")) {
                prSt.setString(1, nifiNodeGroup.getIdentifier());
                try (ResultSet rs = prSt.executeQuery()) {
                    while (rs.next()) {
                        policiesCountByResource.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
        }
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
    }

    @Test
    public void nifiGroupConfigWithoutAdminUser() throws SQLException {
        configureUserGroupProvider("admin");
        //create new group:
        Group nifiNodeGroup = new Group.Builder().
                name("nifiNodeGroup").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(nifiNodeGroup);
        configureProvider("", "nifiNodeGroup");
        Map<String, Integer> policiesCountByResource = new HashMap<>();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                    prepareStatement("select ap.resource, count(1) cnt " +
                            "from app_policy_group apg " +
                            "inner join app_policy ap on ap.identifier = apg.policy_identifier " +
                            "where apg.group_identifier = ? " +
                            "group by ap.resource")) {
                prSt.setString(1, nifiNodeGroup.getIdentifier());
                try (ResultSet rs = prSt.executeQuery()) {
                    while (rs.next()) {
                        policiesCountByResource.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
        }
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
    }

    @Test
    public void nifiGroupConfigForNonExistingGroup() {
        configureUserGroupProvider("admin");
        Assertions.assertThrows(SecurityProviderCreationException.class,
                () -> configureProvider("admin", "nifiNodeGroupNotExists"));
    }

    @Test
    public void nifiIdentitiesConfig() {
        configureUserGroupProvider("admin", "nifi-0", "nifi-1", "nifi-2");
        configureProvider("admin", null, "nifi-0", "nifi-1", "nifi-2");
        Map<String, Integer> policiesCountByResource = getPolicyCountByResourceForUser("nifi-0");
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
        policiesCountByResource = getPolicyCountByResourceForUser("nifi-1");
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
        policiesCountByResource = getPolicyCountByResourceForUser("nifi-2");
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
    }

    @Test
    public void nifiIdentitiesConfigWithEmptyIdentity() {
        configureUserGroupProvider("admin", "nifi-0", "nifi-1", "nifi-2");
        configureProvider("admin", null, "nifi-0", "nifi-1", "nifi-2", "");
        Map<String, Integer> policiesCountByResource = getPolicyCountByResourceForUser("nifi-0");
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
        policiesCountByResource = getPolicyCountByResourceForUser("nifi-1");
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
        policiesCountByResource = getPolicyCountByResourceForUser("nifi-2");
        Assertions.assertEquals(2, policiesCountByResource.size());
        Assertions.assertEquals(3, policiesCountByResource.get("/proxy"));
        Assertions.assertEquals(1, policiesCountByResource.get("/buckets"));
    }

    @Test
    public void nifiIdentitiesConfigForNonExistingUser() {
        configureUserGroupProvider("admin", "nifi-0");
        Assertions.assertThrows(SecurityProviderCreationException.class,
                () -> configureProvider("admin", null,
                        "nifi-0", "nifi-1", "nifi-2"));
    }

    @Test
    public void addNewPolicy() {
        configureUserGroupProvider("admin", "user1", "user2");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource1").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //create ap:
        AccessPolicy apReturned = provider.addAccessPolicy(ap);
        Assertions.assertEquals(ap.getIdentifier(), apReturned.getIdentifier());
        //get ap:
        AccessPolicy apGet = provider.getAccessPolicy(apReturned.getIdentifier());
        Assertions.assertEquals(ap.getIdentifier(), apGet.getIdentifier());
        checkAccessPolicyInDB(ap, Set.of(user1.getIdentifier(), user2.getIdentifier()), Collections.emptySet());
    }

    @Test
    public void addNewPolicyTwice() {
        configureUserGroupProvider("admin", "user1", "user2");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource1").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //create ap:
        AccessPolicy apReturned = provider.addAccessPolicy(ap);
        Assertions.assertEquals(ap.getIdentifier(), apReturned.getIdentifier());
        //DuplicateKeyException on 2nd creation of ap:
        Assertions.assertThrows(DuplicateKeyException.class, () -> provider.addAccessPolicy(ap));
    }

    @Test
    public void addDuplicatePolicy() {
        configureUserGroupProvider("admin", "user1", "user2");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource1").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //create ap:
        AccessPolicy apReturned = provider.addAccessPolicy(ap);
        Assertions.assertEquals(ap.getIdentifier(), apReturned.getIdentifier());
        //create duplicate policy with identical resource and action, but different identifier:
        AccessPolicy duplicatePolicy = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource1").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //DuplicateKeyException on duplicate creation:
        Assertions.assertThrows(DuplicateKeyException.class, () -> provider.addAccessPolicy(duplicatePolicy));
    }

    @Test
    public void updateExistingPolicyAddAndRemoveUsers() {
        configureUserGroupProvider("admin", "user1", "user2");
        User admin = userGroupProvider.getUserByIdentity("admin");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy proxyReadPolicy = provider.getAccessPolicy("/proxy", RequestAction.READ);
        Assertions.assertNotNull(proxyReadPolicy);
        //modify policy -- add user1, user2
        AccessPolicy modifiedPolicy = new AccessPolicy.Builder(proxyReadPolicy).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                removeUser(admin.getIdentifier()).
                build();
        AccessPolicy apReturned = provider.updateAccessPolicy(modifiedPolicy);
        Assertions.assertEquals(modifiedPolicy.getIdentifier(), apReturned.getIdentifier());
        //get ap:
        AccessPolicy apGet = provider.getAccessPolicy(apReturned.getIdentifier());
        Assertions.assertEquals(modifiedPolicy.getIdentifier(), apGet.getIdentifier());
        checkAccessPolicyInDB(modifiedPolicy, Set.of(user1.getIdentifier(), user2.getIdentifier()),
                Collections.emptySet());
    }

    @Test
    public void updateExistingPolicyAddAndRemoveGroups() {
        configureUserGroupProvider("admin", "user1", "user2");
        User admin = userGroupProvider.getUserByIdentity("admin");
        //create new group:
        Group group1 = new Group.Builder().
                name("group123").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group1);
        //create new group:
        Group group2 = new Group.Builder().
                name("group456").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group2);
        configureProvider("admin", null);
        AccessPolicy proxyReadPolicy = provider.getAccessPolicy("/proxy", RequestAction.READ);
        Assertions.assertNotNull(proxyReadPolicy);
        //modify policy -- add group1
        AccessPolicy modifiedPolicy = new AccessPolicy.Builder(proxyReadPolicy).
                addGroup(group1.getIdentifier()).
                build();
        AccessPolicy apReturned = provider.updateAccessPolicy(modifiedPolicy);
        Assertions.assertEquals(modifiedPolicy.getIdentifier(), apReturned.getIdentifier());
        checkAccessPolicyInDB(modifiedPolicy, Set.of(admin.getIdentifier()), Set.of(group1.getIdentifier()));
        modifiedPolicy = new AccessPolicy.Builder(proxyReadPolicy).
                removeGroup(group1.getIdentifier()).
                addGroup(group2.getIdentifier()).
                build();
        apReturned = provider.updateAccessPolicy(modifiedPolicy);
        Assertions.assertEquals(modifiedPolicy.getIdentifier(), apReturned.getIdentifier());
        checkAccessPolicyInDB(modifiedPolicy, Set.of(admin.getIdentifier()), Set.of(group2.getIdentifier()));
    }

    @Test
    public void updateNonExistingPolicy() {
        configureUserGroupProvider("admin", "user1", "user2");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource1").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //update ap:
        AccessPolicy apReturned = provider.updateAccessPolicy(ap);
        Assertions.assertNull(apReturned);
    }

    @Test
    public void deleteExistingPolicy() throws SQLException {
        configureUserGroupProvider("admin", "user1", "user2");
        configureProvider("admin", null);
        AccessPolicy proxyReadPolicy = provider.getAccessPolicy("/proxy", RequestAction.READ);
        Assertions.assertNotNull(proxyReadPolicy);
        AccessPolicy apReturned = provider.deleteAccessPolicy(proxyReadPolicy);
        Assertions.assertEquals(proxyReadPolicy.getIdentifier(), apReturned.getIdentifier());
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                    prepareStatement(SELECT_POLICY_BY_ID)) {
                prSt.setString(1, proxyReadPolicy.getIdentifier());
                try (ResultSet rs = prSt.executeQuery()) {
                    Assertions.assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    public void deleteNonExistingPolicy() {
        configureUserGroupProvider("admin", "user1", "user2");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource1").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //update ap:
        AccessPolicy apReturned = provider.deleteAccessPolicy(ap);
        Assertions.assertNull(apReturned);
    }

    @Test
    public void getAccessPolicyByIdFromDBWithUsers() {
        configureUserGroupProvider("admin", "user1", "user2");
        configureProvider("admin", null);
        AccessPolicy newApFromDB  = createAccessPolicyInDB("/test-resource1234", RequestAction.READ,
                Set.of("user1", "user2"), Collections.emptySet());
        //get ap:
        AccessPolicy apGet = provider.getAccessPolicy(newApFromDB.getIdentifier());
        Assertions.assertEquals(newApFromDB.getIdentifier(), apGet.getIdentifier());
        Assertions.assertEquals(newApFromDB.getResource(), apGet.getResource());
        Assertions.assertEquals(newApFromDB.getAction(), apGet.getAction());
        Assertions.assertEquals(newApFromDB.getUsers(), apGet.getUsers());
        Assertions.assertEquals(0, apGet.getGroups().size());
    }

    @Test
    public void getAccessPolicyByIdFromDBWithGroups() {
        configureUserGroupProvider("admin", "user1", "user2");
        //create new group:
        Group group1 = new Group.Builder().
                name("group123").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group1);
        Group group2 = new Group.Builder().
                name("group456").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group2);
        configureProvider("admin", null);
        AccessPolicy newApFromDB  = createAccessPolicyInDB("/test-resource1234", RequestAction.READ,
                Collections.emptySet(), Set.of("group123", "group456"));
        //get ap:
        AccessPolicy apGet = provider.getAccessPolicy(newApFromDB.getIdentifier());
        Assertions.assertEquals(newApFromDB.getIdentifier(), apGet.getIdentifier());
        Assertions.assertEquals(newApFromDB.getResource(), apGet.getResource());
        Assertions.assertEquals(newApFromDB.getAction(), apGet.getAction());
        Assertions.assertEquals(0, apGet.getUsers().size());
        Assertions.assertEquals(newApFromDB.getGroups(), apGet.getGroups());
    }

    @Test
    public void getNotExistingAccessPolicyById() {
        configureUserGroupProvider("admin", "user1", "user2");
        User user1 = userGroupProvider.getUserByIdentity("user1");
        User user2 = userGroupProvider.getUserByIdentity("user2");
        configureProvider("admin", null);
        AccessPolicy ap = new AccessPolicy.Builder().
                identifierGenerateRandom().
                resource("/test-resource2").
                action(RequestAction.READ).
                addUser(user1.getIdentifier()).
                addUser(user2.getIdentifier()).
                build();
        //get ap by id:
        AccessPolicy apReturned = provider.getAccessPolicy(ap.getIdentifier());
        Assertions.assertNull(apReturned);
    }

    @Test
    public void getAccessPolicyByResourceFromDBWithUsers() {
        configureUserGroupProvider("admin", "user1", "user2");
        configureProvider("admin", null);
        AccessPolicy newApFromDB  = createAccessPolicyInDB("/test-resource1234", RequestAction.WRITE,
                Set.of("user1", "user2"), Collections.emptySet());
        //get ap:
        AccessPolicy apGet = provider.getAccessPolicy("/test-resource1234", RequestAction.WRITE);
        Assertions.assertEquals(newApFromDB.getIdentifier(), apGet.getIdentifier());
        Assertions.assertEquals(newApFromDB.getResource(), apGet.getResource());
        Assertions.assertEquals(newApFromDB.getAction(), apGet.getAction());
        Assertions.assertEquals(newApFromDB.getUsers(), apGet.getUsers());
        Assertions.assertEquals(0, apGet.getGroups().size());
    }

    @Test
    public void getAccessPolicyByResourceFromDBWithGroups() {
        configureUserGroupProvider("admin", "user1", "user2");
        //create new group:
        Group group1 = new Group.Builder().
                name("group123").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group1);
        Group group2 = new Group.Builder().
                name("group456").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group2);
        configureProvider("admin", null);
        AccessPolicy newApFromDB  = createAccessPolicyInDB("/test-resource1234", RequestAction.DELETE,
                Collections.emptySet(), Set.of("group123", "group456"));
        //get ap:
        AccessPolicy apGet = provider.getAccessPolicy("/test-resource1234", RequestAction.DELETE);
        Assertions.assertEquals(newApFromDB.getIdentifier(), apGet.getIdentifier());
        Assertions.assertEquals(newApFromDB.getResource(), apGet.getResource());
        Assertions.assertEquals(newApFromDB.getAction(), apGet.getAction());
        Assertions.assertEquals(0, apGet.getUsers().size());
        Assertions.assertEquals(newApFromDB.getGroups(), apGet.getGroups());
    }

    @Test
    public void getNotExistingAccessPolicyByResource() {
        configureUserGroupProvider("admin", "user1", "user2");
        configureProvider("admin", null);
        //get ap by resource:
        AccessPolicy apReturned = provider.getAccessPolicy("/test-resource3", RequestAction.WRITE);
        Assertions.assertNull(apReturned);
    }

    @Test
    public void getAllAccessPoliciesFromDB() {
        configureUserGroupProvider("admin", "user1", "user2");
        User admin = userGroupProvider.getUserByIdentity("admin");
        Set<String> expectedAdminUserIds = Set.of(admin.getIdentifier());
        //create new group:
        Group group1 = new Group.Builder().
                name("group123").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group1);
        Group group2 = new Group.Builder().
                name("group456").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group2);
        configureProvider("admin", null);
        AccessPolicy newApFromDB1  = createAccessPolicyInDB("/test-resource123", RequestAction.READ,
                Set.of("admin", "user1"), Set.of("group123", "group456"));
        AccessPolicy newApFromDB2  = createAccessPolicyInDB("/test-resource456", RequestAction.READ,
                Set.of("admin", "user1"), Collections.emptySet());
        AccessPolicy newApFromDB3  = createAccessPolicyInDB("/test-resource789", RequestAction.READ,
                Collections.emptySet(), Set.of("group123", "group456"));
        AccessPolicy newApFromDB4  = createAccessPolicyInDB("/test-resource789", RequestAction.WRITE,
                Collections.emptySet(), Collections.emptySet());
        //get ap:
        Set<AccessPolicy> allAccessPolicies = provider.getAccessPolicies();
        Map<String, AccessPolicy> accessPolicyMapById = new HashMap<>();
        Map<PolicyKey, AccessPolicy> accessPolicyMapByPK = new HashMap<>();
        for (AccessPolicy ap : allAccessPolicies) {
            accessPolicyMapById.put(ap.getIdentifier(), ap);
            accessPolicyMapByPK.put(new PolicyKey(ap.getResource(), ap.getAction()), ap);
        }
        //additional policies:
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB1.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB1, accessPolicyMapById.get(newApFromDB1.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB2.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB2, accessPolicyMapById.get(newApFromDB2.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB3.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB3, accessPolicyMapById.get(newApFromDB3.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB4.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB4, accessPolicyMapById.get(newApFromDB4.getIdentifier()));
        //default policies:
        assertDefaultAdminPoliciesExist(InitialPolicies.TENANTS_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.POLICIES_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.BUCKETS_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.PROXY_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.ACTUATOR_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.SWAGGER_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
    }

    @Test
    public void getAllAccessPoliciesFromCache() {
        configureUserGroupProvider("admin", "user1", "user2");
        User admin = userGroupProvider.getUserByIdentity("admin");
        Set<String> expectedAdminUserIds = Set.of(admin.getIdentifier());
        //create new group:
        Group group1 = new Group.Builder().
                name("group123").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group1);
        Group group2 = new Group.Builder().
                name("group456").
                identifierGenerateRandom().build();
        userGroupProvider.addGroup(group2);
        configureProvider("admin", null);
        AccessPolicy newApFromDB1  = createAccessPolicyInDB("/test-resource123", RequestAction.READ,
                Set.of("admin", "user1"), Set.of("group123", "group456"));
        AccessPolicy newApFromDB2  = createAccessPolicyInDB("/test-resource456", RequestAction.READ,
                Set.of("admin", "user1"), Collections.emptySet());
        AccessPolicy newApFromDB3  = createAccessPolicyInDB("/test-resource789", RequestAction.READ,
                Collections.emptySet(), Set.of("group123", "group456"));
        AccessPolicy newApFromDB4  = createAccessPolicyInDB("/test-resource789", RequestAction.WRITE,
                Collections.emptySet(), Collections.emptySet());
        //get ap:
        Set<AccessPolicy> allAccessPolicies = provider.getAccessPolicies();
        Map<String, AccessPolicy> accessPolicyMapById = new HashMap<>();
        Map<PolicyKey, AccessPolicy> accessPolicyMapByPK = new HashMap<>();
        for (AccessPolicy ap : allAccessPolicies) {
            accessPolicyMapById.put(ap.getIdentifier(), ap);
            accessPolicyMapByPK.put(new PolicyKey(ap.getResource(), ap.getAction()), ap);
        }
        //additional policies:
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB1.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB1, accessPolicyMapById.get(newApFromDB1.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB2.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB2, accessPolicyMapById.get(newApFromDB2.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB3.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB3, accessPolicyMapById.get(newApFromDB3.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB4.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB4, accessPolicyMapById.get(newApFromDB4.getIdentifier()));
        //default policies:
        assertDefaultAdminPoliciesExist(InitialPolicies.TENANTS_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.POLICIES_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.BUCKETS_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.PROXY_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.ACTUATOR_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        assertDefaultAdminPoliciesExist(InitialPolicies.SWAGGER_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        //add new policy in DB:
        AccessPolicy newApFromDB5  = createAccessPolicyInDB("/test-resource123456", RequestAction.READ,
                Collections.emptySet(), Collections.emptySet());
        //get ap from cache:
        allAccessPolicies = provider.getAccessPolicies();
        accessPolicyMapById = new HashMap<>();
        accessPolicyMapByPK = new HashMap<>();
        for (AccessPolicy ap : allAccessPolicies) {
            accessPolicyMapById.put(ap.getIdentifier(), ap);
            accessPolicyMapByPK.put(new PolicyKey(ap.getResource(), ap.getAction()), ap);
        }
        //additional policies:
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB1.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB1, accessPolicyMapById.get(newApFromDB1.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB2.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB2, accessPolicyMapById.get(newApFromDB2.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB3.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB3, accessPolicyMapById.get(newApFromDB3.getIdentifier()));
        Assertions.assertTrue(accessPolicyMapById.containsKey(newApFromDB4.getIdentifier()));
        assertAccessPoliciesEqual(newApFromDB4, accessPolicyMapById.get(newApFromDB4.getIdentifier()));
        //default policies:
        assertDefaultAdminPoliciesExist(InitialPolicies.TENANTS_RESOURCE, expectedAdminUserIds, accessPolicyMapByPK);
        //new policy:
        Assertions.assertFalse(accessPolicyMapById.containsKey(newApFromDB5.getIdentifier()));
        PolicyKey pk = new PolicyKey(newApFromDB5.getResource(), newApFromDB5.getAction());
        Assertions.assertFalse(accessPolicyMapByPK.containsKey(pk));
    }

    @Test
    public void getUserGroupProvider() {
        configureUserGroupProvider("admin", "user1", "user2");
        configureProvider("admin", null);
        UserGroupProvider prov = provider.getUserGroupProvider();
        Assertions.assertNotNull(prov);
    }

    @Test
    public void configureInvalidUserGroupProvider() {
        configureUserGroupProvider("admin", "user1", "user2");
        Assertions.assertThrows(SecurityProviderCreationException.class,
                () -> configureInvalidProvider("admin", null));

    }
}
