package org.qubership.cloud.nifi.registry.security.authorization.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.registry.security.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserAndGroups;
import org.apache.nifi.registry.security.authorization.UserGroupProviderInitializationContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("DockerBased")
@Testcontainers
public class CachedDatabaseUserGroupProviderTest {
    private static final String POSTGRES_IMAGE = "postgres:16.8";

    private static final String DB_NAME = "testDb";
    private static final String USER = "postgres";
    private static final String PWD = "password";
    private static DataSource dataSource;

    @Container
    private static JdbcDatabaseContainer postgresContainer;

    private final CachedDatabaseUserGroupProvider provider = new CachedDatabaseUserGroupProvider();

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
        provider.setDataSource(dataSource);
        UserGroupProviderInitializationContext initializationContext =
                mock(UserGroupProviderInitializationContext.class);
        provider.initialize(initializationContext);
    }

    private void configureProvider(String... initialUserIdentities) {
        final Map<String, String> configProperties = new HashMap<>();

        for (int i = 0; i < initialUserIdentities.length; i++) {
            final String initialUserIdentity = initialUserIdentities[i];
            configProperties.put(CachedDatabaseUserGroupProvider.PROP_INITIAL_USER_IDENTITY_PREFIX + (i + 1),
                    initialUserIdentity);
        }
        configProperties.put("TestProperty 1", "Value 1");
        configProperties.put("TestProperty 2", "Value 2");

        final AuthorizerConfigurationContext configurationContext = mock(AuthorizerConfigurationContext.class);
        when(configurationContext.getProperties()).thenReturn(configProperties);

        provider.onConfigured(configurationContext);
    }

    @Test
    public void singleInitialUser() throws SQLException {
        configureProvider("admin");
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("select identifier, identity from ugp_user where identity = ?")) {
            prSt.setString(1, "admin");
            try (ResultSet rs = prSt.executeQuery()) {
                Assertions.assertTrue(rs.next());
                Assertions.assertTrue(StringUtils.isNotEmpty(rs.getString("identifier")));
            }
        }
    }

    @Test
    public void threeInitialUsers() throws SQLException {
        configureProvider("admin", "user1", "user2");
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("select identifier, identity from ugp_user where identity = ?")) {
            prSt.setString(1, "admin");
            try (ResultSet rs = prSt.executeQuery()) {
                Assertions.assertTrue(rs.next());
                Assertions.assertTrue(StringUtils.isNotEmpty(rs.getString("identifier")));
            }
            prSt.setString(1, "user1");
            try (ResultSet rs = prSt.executeQuery()) {
                Assertions.assertTrue(rs.next());
                Assertions.assertTrue(StringUtils.isNotEmpty(rs.getString("identifier")));
            }
            prSt.setString(1, "user2");
            try (ResultSet rs = prSt.executeQuery()) {
                Assertions.assertTrue(rs.next());
                Assertions.assertTrue(StringUtils.isNotEmpty(rs.getString("identifier")));
            }
        }
    }

    @Test
    public void singleInitialUserConfigureTwice() throws SQLException {
        configureProvider("admin");
        configureProvider("admin");
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("select identifier, identity from ugp_user where identity = ?")) {
            prSt.setString(1, "admin");
            try (ResultSet rs = prSt.executeQuery()) {
                Assertions.assertTrue(rs.next());
                Assertions.assertTrue(StringUtils.isNotEmpty(rs.getString("identifier")));
                Assertions.assertFalse(rs.next());
            }
        }
    }

    @Test
    public void getAllUsers() {
        configureProvider("admin", "user1", "user2");
        Set<User> users = provider.getUsers();
        Assertions.assertEquals(3, users.size());
        Set<String> userIdentities = getUserIdentities(users);
        Assertions.assertTrue(userIdentities.contains("admin"));
        Assertions.assertTrue(userIdentities.contains("user1"));
        Assertions.assertTrue(userIdentities.contains("user2"));
    }

    @Test
    public void getAllUsersWithModification() {
        configureProvider("admin", "user1", "user2");
        Set<User> users = provider.getUsers();
        Assertions.assertEquals(3, users.size());
        Set<String> userIdentities = getUserIdentities(users);
        Assertions.assertTrue(userIdentities.containsAll(Arrays.asList("admin", "user1", "user2")));
        User newUser5 = new User.Builder().
                identifierGenerateFromSeed("user5").
                identity("user5").
                build();
        User newUser6 = new User.Builder().
                identifierGenerateFromSeed("user6").
                identity("user6").
                build();
        provider.addUser(newUser5);
        provider.addUser(newUser6);
        users = provider.getUsers();
        Assertions.assertEquals(5, users.size());
        userIdentities = getUserIdentities(users);
        Assertions.assertTrue(userIdentities.containsAll(Arrays.asList(
                "admin", "user1", "user2", "user5", "user6")));
    }

    @Test
    public void addUserTwice() {
        configureProvider("admin", "user1", "user2");
        User newUser123 = new User.Builder().
                identifierGenerateFromSeed("user123").
                identity("user123").
                build();
        User creationResult = provider.addUser(newUser123);
        Assertions.assertNotNull(creationResult);
        //DuplicateKeyException on 2nd creation of the same user:
        Assertions.assertThrows(DuplicateKeyException.class, () -> provider.addUser(newUser123));
    }

    @Test
    public void addUserWithDuplicateIdentity() {
        configureProvider("admin", "user1", "user2");
        User newUser123 = new User.Builder().
                identifierGenerateFromSeed("user123").
                identity("user123").
                build();
        User creationResult = provider.addUser(newUser123);
        Assertions.assertNotNull(creationResult);
        User newUser12345 = new User.Builder().
                identifierGenerateFromSeed("user12345").
                identity("user123").
                build();
        //DuplicateKeyException on 2nd creation of the same user:
        Assertions.assertThrows(DuplicateKeyException.class, () -> provider.addUser(newUser12345));
    }

    @Test
    public void getUserByIdentity() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user1");
        Assertions.assertEquals("user1", user.getIdentity());
    }

    @Test
    public void getUserByIdentityNotExists() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user5");
        Assertions.assertNull(user);
    }

    @Test
    public void getUserByIdentityWithModification() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user1");
        Assertions.assertEquals("user1", user.getIdentity());
        User modifiedUser = new User.Builder(user).identity("user10").build();
        provider.updateUser(modifiedUser);
        user = provider.getUserByIdentity("user1");
        Assertions.assertNull(user);
        user = provider.getUserByIdentity("user10");
        Assertions.assertEquals("user10", user.getIdentity());
    }

    @Test
    public void getUserByIdentityWithDelete() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user1");
        Assertions.assertEquals("user1", user.getIdentity());
        String userIdentifier = user.getIdentifier();
        user = provider.deleteUser(user);
        Assertions.assertEquals("user1", user.getIdentity());
        Assertions.assertEquals(userIdentifier, user.getIdentifier());
        user = provider.getUserByIdentity("user1");
        Assertions.assertNull(user);
    }

    @Test
    public void getUserByIdWithDelete() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user1");
        Assertions.assertEquals("user1", user.getIdentity());
        String userIdentifier = user.getIdentifier();
        User userById = provider.getUser(userIdentifier);
        Assertions.assertEquals("user1", userById.getIdentity());
        Assertions.assertEquals(userIdentifier, userById.getIdentifier());
        User userDeleted = provider.deleteUser(userById);
        Assertions.assertEquals("user1", userDeleted.getIdentity());
        Assertions.assertEquals(userIdentifier, userDeleted.getIdentifier());
        user = provider.getUser(userIdentifier);
        Assertions.assertNull(user);
    }

    @Test
    public void getUserByIdentityForCreatedInDB() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user10");
        Assertions.assertNull(user);
        User userInDB = createUserInDB("user10");
        user = provider.getUserByIdentity("user10");
        Assertions.assertEquals("user10", user.getIdentity());
        Assertions.assertEquals(userInDB.getIdentifier(), user.getIdentifier());
    }

    @Test
    public void getUserByIdForCreatedInDB() {
        configureProvider("admin", "user1", "user2");
        User user = provider.getUserByIdentity("user11");
        Assertions.assertNull(user);
        User userInDB = createUserInDB("user11");
        user = provider.getUser(userInDB.getIdentifier());
        Assertions.assertEquals("user11", user.getIdentity());
        Assertions.assertEquals(userInDB.getIdentifier(), user.getIdentifier());
    }

    @Test
    public void getNonExistingUserById() {
        configureProvider("admin", "user1", "user2");
        User user = new User.Builder().
                identifierGenerateFromSeed("user5").
                identity("user5").
                build();
        user = provider.getUser(user.getIdentifier());
        Assertions.assertNull(user);
    }

    @Test
    public void addGroup() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String userId2 = provider.getUserByIdentity("user2").getIdentifier();
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                addUser(userId1).
                addUser(userId2).
                build();
        Group createdGroup = provider.addGroup(group);
        Assertions.assertEquals("group1", createdGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), createdGroup.getIdentifier());
        Group groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertEquals("group1", groupByGet.getName());
        Assertions.assertEquals(group.getIdentifier(), groupByGet.getIdentifier());
        Assertions.assertTrue(groupByGet.getUsers().containsAll(Arrays.asList(userId1, userId2)));
        Assertions.assertEquals(2, groupByGet.getUsers().size());
    }

    @Test
    public void addGroupWithoutUsers() {
        configureProvider("admin", "user1", "user2");
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                build();
        Group createdGroup = provider.addGroup(group);
        Assertions.assertEquals("group1", createdGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), createdGroup.getIdentifier());
        Group groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertEquals("group1", groupByGet.getName());
        Assertions.assertEquals(group.getIdentifier(), groupByGet.getIdentifier());
        Assertions.assertNotNull(groupByGet.getUsers());
        Assertions.assertEquals(0, groupByGet.getUsers().size());
    }

    @Test
    public void addGroupTwice() {
        configureProvider("admin", "user1", "user2");
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                build();
        Group createdGroup = provider.addGroup(group);
        Assertions.assertEquals("group1", createdGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), createdGroup.getIdentifier());
        //DuplicateKeyException on 2nd creation of group:
        Assertions.assertThrows(DuplicateKeyException.class, () -> provider.addGroup(group));
    }

    @Test
    public void addGroupWithDuplicateName() {
        configureProvider("admin", "user1", "user2");
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                build();
        Group createdGroup = provider.addGroup(group);
        Assertions.assertEquals("group1", createdGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), createdGroup.getIdentifier());
        Group duplicateGroup = new Group.Builder().
                identifierGenerateFromSeed("group12345").
                name("group1").
                build();
        //DuplicateKeyException on creation of duplicate group:
        Assertions.assertThrows(DuplicateKeyException.class, () -> provider.addGroup(duplicateGroup));
    }

    @Test
    public void updateGroup() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String userId2 = provider.getUserByIdentity("user2").getIdentifier();
        String adminId = provider.getUserByIdentity("admin").getIdentifier();
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                addUser(userId1).
                addUser(userId2).
                build();
        Group createdGroup = provider.addGroup(group);
        Assertions.assertEquals("group1", createdGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), createdGroup.getIdentifier());
        Group groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertEquals("group1", groupByGet.getName());
        Assertions.assertEquals(group.getIdentifier(), groupByGet.getIdentifier());
        Assertions.assertTrue(groupByGet.getUsers().containsAll(Arrays.asList(userId1, userId2)));
        Assertions.assertEquals(2, groupByGet.getUsers().size());

        Group modifiedGroup = new Group.Builder(groupByGet).
                name("group11").
                removeUser(userId2).
                addUser(adminId).
                build();
        Group groupAfterModify = provider.updateGroup(modifiedGroup);
        Assertions.assertEquals("group11", groupAfterModify.getName());
        Assertions.assertEquals(group.getIdentifier(), groupAfterModify.getIdentifier());
        //
        groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertEquals("group11", groupByGet.getName());
        Assertions.assertEquals(group.getIdentifier(), groupByGet.getIdentifier());
        Assertions.assertTrue(groupByGet.getUsers().containsAll(Arrays.asList(userId1, adminId)));
        Assertions.assertEquals(2, groupByGet.getUsers().size());
    }

    @Test
    public void deleteGroup() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String userId2 = provider.getUserByIdentity("user2").getIdentifier();
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                addUser(userId1).
                addUser(userId2).
                build();
        Group createdGroup = provider.addGroup(group);
        Assertions.assertEquals("group1", createdGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), createdGroup.getIdentifier());
        Group groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertEquals("group1", groupByGet.getName());
        Assertions.assertEquals(group.getIdentifier(), groupByGet.getIdentifier());
        Assertions.assertTrue(groupByGet.getUsers().containsAll(Arrays.asList(userId1, userId2)));
        Assertions.assertEquals(2, groupByGet.getUsers().size());
        Group deletedGroup = provider.deleteGroup(group);
        Assertions.assertEquals("group1", deletedGroup.getName());
        Assertions.assertEquals(group.getIdentifier(), deletedGroup.getIdentifier());
        groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertNull(groupByGet);
    }

    @Test
    public void deleteNonExistingGroup() {
        configureProvider("admin", "user1", "user2");
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                addUser(provider.getUserByIdentity("user1").getIdentifier()).
                addUser(provider.getUserByIdentity("user2").getIdentifier()).
                build();
        Group deletedGroup = provider.deleteGroup(group);
        Assertions.assertNull(deletedGroup);
        Group groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertNull(groupByGet);
    }

    @Test
    public void updateNonExistingGroup() {
        configureProvider("admin", "user1", "user2");
        Group group = new Group.Builder().
                identifierGenerateFromSeed("group1").
                name("group1").
                addUser(provider.getUserByIdentity("user1").getIdentifier()).
                addUser(provider.getUserByIdentity("user2").getIdentifier()).
                build();
        Group modifiedGroup = provider.updateGroup(group);
        Assertions.assertNull(modifiedGroup);
        Group groupByGet = provider.getGroup(group.getIdentifier());
        Assertions.assertNull(groupByGet);
    }

    @Test
    public void getGroupByIdForCreatedInDB() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String adminId = provider.getUserByIdentity("admin").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        Group groupByGet = provider.getGroup(groupCreatedInDB.getIdentifier());
        Assertions.assertEquals("group123", groupByGet.getName());
        Assertions.assertEquals(groupCreatedInDB.getIdentifier(), groupByGet.getIdentifier());
        Assertions.assertEquals(2, groupByGet.getUsers().size());
        Assertions.assertTrue(groupByGet.getUsers().containsAll(Arrays.asList(userId1, adminId)),
                "Actual users: " + groupByGet.getUsers());
    }

    @Test
    public void getGroupsForCreatedInDB() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String userId2 = provider.getUserByIdentity("user2").getIdentifier();
        String adminId = provider.getUserByIdentity("admin").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        Group groupCreatedInDB2 = createGroupInDB("group456", "user1", "user2");
        Set<Group> allGroups = provider.getGroups();
        Assertions.assertEquals(2, allGroups.size());
        Assertions.assertTrue(allGroups.stream().anyMatch(group -> "group123".equals(group.getName())));
        Assertions.assertTrue(allGroups.stream().anyMatch(group -> "group456".equals(group.getName())));
        Group group1FromAPI = allGroups.stream().filter(group -> "group123".
                equals(group.getName())).findFirst().get();
        Group group2FromAPI = allGroups.stream().filter(group -> "group456".
                equals(group.getName())).findFirst().get();
        Assertions.assertEquals(groupCreatedInDB.getIdentifier(), group1FromAPI.getIdentifier());
        Assertions.assertEquals(2, group1FromAPI.getUsers().size());
        Assertions.assertTrue(group1FromAPI.getUsers().containsAll(Arrays.asList(userId1, adminId)),
                "Actual users: " + group1FromAPI.getUsers());
        Assertions.assertEquals(groupCreatedInDB2.getIdentifier(), group2FromAPI.getIdentifier());
        Assertions.assertEquals(2, groupCreatedInDB2.getUsers().size());
        Assertions.assertTrue(groupCreatedInDB2.getUsers().containsAll(Arrays.asList(userId1, userId2)),
                "Actual users: " + groupCreatedInDB2.getUsers());

        //second call does not reread DB:
        Group groupCreatedInDB3 = createGroupInDB("group678", "user1");
        Assertions.assertNotNull(groupCreatedInDB3);
        allGroups = provider.getGroups();
        Assertions.assertEquals(2, allGroups.size());
    }

    @Test
    public void getUserAndGroupsForCreatedInDB() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        Assertions.assertNotNull(groupCreatedInDB);
        UserAndGroups ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        Group groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group123", groupFromGet.getName());
    }

    @Test
    public void getUserAndGroupsForCached() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        Assertions.assertNotNull(groupCreatedInDB);
        UserAndGroups ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        Group groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group123", groupFromGet.getName());
        //cached version:
        ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
    }

    @Test
    public void getUserAndGroupsForNonExisting() {
        configureProvider("admin", "user1", "user2");
        UserAndGroups ug = provider.getUserAndGroups("user12345");
        Assertions.assertNotNull(ug);
        Assertions.assertNull(ug.getUser());
        Assertions.assertNull(ug.getGroups());
    }

    @Test
    public void getUserAndGroupsForModifiedGroup() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String userId2 = provider.getUserByIdentity("user2").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        //to force caching for group:
        Group groupByGet = provider.getGroup(groupCreatedInDB.getIdentifier());
        //first get to fill caches for both user1 and user2:
        UserAndGroups ug = provider.getUserAndGroups("user2");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user2", ug.getUser().getIdentity());
        Assertions.assertEquals(userId2, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(0, ug.getGroups().size());
        ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        Group groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group123", groupFromGet.getName());
        //modify group:
        Group modifiedGroup = new Group.Builder(groupByGet).removeUser(userId1).addUser(userId2).build();
        provider.updateGroup(modifiedGroup);
        //run get after modify to check for reflected changes:
        ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(0, ug.getGroups().size());
        //run get after modify to check for reflected changes:
        ug = provider.getUserAndGroups("user2");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user2", ug.getUser().getIdentity());
        Assertions.assertEquals(userId2, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group123", groupFromGet.getName());
    }

    @Test
    public void getUserAndGroupsForDeletedGroup() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        //to force caching for group:
        Group groupByGet = provider.getGroup(groupCreatedInDB.getIdentifier());
        //first get to fill caches for user1:
        UserAndGroups ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        Group groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group123", groupFromGet.getName());
        //delete group:
        provider.deleteGroup(groupByGet);
        //run get after delete to check for reflected changes:
        ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(0, ug.getGroups().size());
    }

    @Test
    public void getUserAndGroupsForAddedGroup() {
        configureProvider("admin", "user1", "user2");
        String userId1 = provider.getUserByIdentity("user1").getIdentifier();
        String userId2 = provider.getUserByIdentity("user2").getIdentifier();
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        Assertions.assertNotNull(groupCreatedInDB);
        //to force caching for existing groups:
        provider.getGroups();
        //first get to fill caches for both user1 and user2:
        UserAndGroups ug = provider.getUserAndGroups("user2");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user2", ug.getUser().getIdentity());
        Assertions.assertEquals(userId2, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(0, ug.getGroups().size());
        ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        Group groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group123", groupFromGet.getName());
        //add group:
        Group addedGroup = new Group.Builder().
                identifierGenerateFromSeed("group567").
                name("group567").
                addUser(userId1).
                addUser(userId2).
                build();
        provider.addGroup(addedGroup);
        //run get after add to check for reflected changes:
        ug = provider.getUserAndGroups("user1");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user1", ug.getUser().getIdentity());
        Assertions.assertEquals(userId1, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(2, ug.getGroups().size());
        //run get after add to check for reflected changes:
        ug = provider.getUserAndGroups("user2");
        Assertions.assertNotNull(ug);
        Assertions.assertEquals("user2", ug.getUser().getIdentity());
        Assertions.assertEquals(userId2, ug.getUser().getIdentifier());
        Assertions.assertNotNull(ug.getGroups());
        Assertions.assertEquals(1, ug.getGroups().size());
        groupFromGet = ug.getGroups().stream().toList().get(0);
        Assertions.assertEquals("group567", groupFromGet.getName());
    }

    @Test
    public void getGroupForAddedGroupInDBAfterCacheFilled() {
        configureProvider("admin", "user1", "user2");
        Group groupCreatedInDB = createGroupInDB("group123", "user1", "admin");
        //to force caching for existing groups:
        provider.getGroups();
        Group groupFromGet = provider.getGroup(groupCreatedInDB.getIdentifier());
        Assertions.assertNotNull(groupFromGet);
        Assertions.assertEquals("group123", groupFromGet.getName());
        //new group
        Group groupCreatedInDB2 = createGroupInDB("group567", "user1", "admin");
        //getGroup does not see manually added group in DB:
        groupFromGet = provider.getGroup(groupCreatedInDB2.getIdentifier());
        Assertions.assertNull(groupFromGet);
    }

    //Utility methods
    private static @NotNull Set<String> getUserIdentities(Set<User> users) {
        Set<String> userIdentities = new HashSet<>();
        for (User user : users) {
            userIdentities.add(user.getIdentity());
        }
        return userIdentities;
    }

    private User createUserInDB(String identity) {
        User user = new User.Builder().
                identifierGenerateFromSeed(identity).
                identity(identity).
                build();
        try (Connection con = dataSource.getConnection();
             PreparedStatement prSt = con.
                     prepareStatement("insert into ugp_user (identifier, identity) values (?, ?)")) {
            prSt.setString(1, user.getIdentifier());
            prSt.setString(2, user.getIdentity());
            prSt.executeUpdate();
        } catch (SQLException ex) {
            Assertions.fail(ex);
        }
        return user;
    }

    private Group createGroupInDB(String groupName, String... userIdentities) {
        Set<String> userIds = new HashSet<>();
        for (String userIdentity : userIdentities) {
            User user = provider.getUserByIdentity(userIdentity);
            userIds.add(user.getIdentifier());
        }
        Group group = new Group.Builder().
                identifierGenerateFromSeed(groupName).
                name(groupName).
                addUsers(userIds).
                build();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement prSt = con.
                     prepareStatement("insert into ugp_group (identifier, identity) values (?, ?)")) {
                prSt.setString(1, group.getIdentifier());
                prSt.setString(2, group.getName());
                prSt.executeUpdate();
            }
            for (String userId : userIds) {
                try (PreparedStatement prSt = con.
                        prepareStatement(
                                "insert into ugp_user_group (group_identifier, user_identifier) values (?, ?)")) {
                    prSt.setString(1, group.getIdentifier());
                    prSt.setString(2, userId);
                    prSt.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            Assertions.fail(ex);
        }
        return group;
    }
}
