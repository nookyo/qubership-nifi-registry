package org.qubership.cloud.nifi.registry.security.authorization.database;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.nifi.registry.security.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.registry.security.authorization.ConfigurableUserGroupProvider;
import org.apache.nifi.registry.security.authorization.Group;
import org.apache.nifi.registry.security.authorization.User;
import org.apache.nifi.registry.security.authorization.UserAndGroups;
import org.apache.nifi.registry.security.authorization.UserGroupProviderInitializationContext;
import org.apache.nifi.registry.security.authorization.annotation.AuthorizerContext;
import org.apache.nifi.registry.security.authorization.exception.AuthorizationAccessException;
import org.apache.nifi.registry.security.authorization.exception.UninheritableAuthorizationsException;
import org.apache.nifi.registry.security.exception.SecurityProviderCreationException;
import org.apache.nifi.registry.security.exception.SecurityProviderDestructionException;
import org.qubership.cloud.nifi.registry.security.authorization.database.mappers.GroupRowMapper;
import org.qubership.cloud.nifi.registry.security.authorization.database.mappers.UserRowMapper;
import org.qubership.cloud.nifi.registry.security.authorization.database.model.UserAndGroupsImpl;
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

public class CachedDatabaseUserGroupProvider
    implements ConfigurableUserGroupProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedDatabaseUserGroupProvider.class);
    public static final String PROP_INITIAL_USER_IDENTITY_PREFIX = "Initial User Identity ";
    public static final Pattern INITIAL_USER_IDENTITY_PATTERN =
            Pattern.compile(PROP_INITIAL_USER_IDENTITY_PREFIX + "\\S+");

    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ReadWriteLock dbLock = new ReentrantReadWriteLock();

    private final Map<String, User> userCacheById = new HashMap<>();
    private final Map<String, String> userCacheByIdentity = new HashMap<>();
    private boolean userCacheLoaded = false;
    private final Map<String, Group> groupCacheById = new HashMap<>();
    private boolean groupCacheLoaded = false;
    private final Map<String, Set<String>> userGroupIdsCache = new HashMap<>();


    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private final UserRowMapper userRowMapper = new UserRowMapper();
    private final GroupRowMapper groupRowMapper = new GroupRowMapper();

    /**
     * Sets data source.
     * @param newDataSource datasource to set
     */
    @AuthorizerContext
    public void setDataSource(final DataSource newDataSource) {
        this.dataSource = newDataSource;
    }

    /**
     * Gets fingerprint for users and groups configuration.
     * @return fingerprint for users and groups
     * @throws AuthorizationAccessException
     */
    @Override
    public String getFingerprint() throws AuthorizationAccessException {
        throw new UnsupportedOperationException("Fingerprinting is not supported by this provider");
    }

    /**
     * Loads users and groups configuration from fingerprint.
     * @param s fingerprint for  users and groups
     * @throws AuthorizationAccessException
     */
    @Override
    public void inheritFingerprint(String s) throws AuthorizationAccessException {
        throw new UnsupportedOperationException("Fingerprinting is not supported by this provider");
    }

    /**
     * Checks, if users and groups configuration can be inherited from fingerprint.
     * @param s fingerprint for users and groups
     * @throws AuthorizationAccessException
     */
    @Override
    public void checkInheritability(String s)
            throws AuthorizationAccessException, UninheritableAuthorizationsException {
        throw new UnsupportedOperationException("Fingerprinting is not supported by this provider");
    }

    /**
     * Creates new user.
     * @param user user to create
     * @return created user
     * @throws AuthorizationAccessException
     */
    @Override
    public User addUser(User user) throws AuthorizationAccessException {
        Objects.requireNonNull(user);

        final String sql = "insert into ugp_user (identifier, identity) values (?, ?)";
        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final int rowsUpdated = jdbcTemplate.update(sql,
                    user.getIdentifier(),
                    user.getIdentity());
            userCacheById.put(user.getIdentifier(), user);
            userCacheByIdentity.put(user.getIdentity(), user.getIdentifier());
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return user;
    }

    /**
     * Updates user.
     * @param user user to update
     * @return updated user
     * @throws AuthorizationAccessException
     */
    @Override
    public User updateUser(User user) throws AuthorizationAccessException {
        Objects.requireNonNull(user);

        final String sql = "update ugp_user set identity = ? where identifier = ?";
        User existingUser = getUser(user.getIdentifier());
        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final int rowsUpdated = jdbcTemplate.update(sql,
                    user.getIdentity(),
                    user.getIdentifier());
            userCacheById.put(user.getIdentifier(), user);
            userCacheByIdentity.remove(existingUser.getIdentity());
            userCacheByIdentity.put(user.getIdentity(), user.getIdentifier());
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return user;
    }

    /**
     * Delete user.
     * @param user user to delete
     * @return deleted user
     * @throws AuthorizationAccessException
     */
    @Override
    public User deleteUser(User user) throws AuthorizationAccessException {
        Objects.requireNonNull(user);

        final String sql = "delete from ugp_user where identifier = ?";
        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final int rowsUpdated = jdbcTemplate.update(sql,
                    user.getIdentifier());
            userCacheById.remove(user.getIdentifier());
            userCacheByIdentity.remove(user.getIdentity());
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return user;
    }

    /**
     * Creates group.
     * @param group group to create
     * @return created group
     * @throws AuthorizationAccessException
     */
    @Override
    public Group addGroup(Group group) throws AuthorizationAccessException {
        Objects.requireNonNull(group);

        final String sql =
                "with ins as (insert into ugp_group(identifier, identity) " +
                        "values (?, ?) returning identifier) " +
                        "insert into ugp_user_group (group_identifier, user_identifier) " +
                        "         select ins.identifier, user_ids.col from ins, unnest(?::text[]) user_ids(col)";
        String[] users = null;
        if (group.getUsers() != null) {
            users = group.getUsers().toArray(new String[0]);
        }

        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final int rowsUpdated = jdbcTemplate.update(sql,
                    group.getIdentifier(),
                    group.getName(),
                    users);
            groupCacheById.put(group.getIdentifier(), group);
            for (String userId : group.getUsers()) {
                Set<String> groupIds = userGroupIdsCache.get(userId);
                if (groupIds != null) {
                    groupIds.add(group.getIdentifier());
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return group;
    }

    /**
     * Updates group.
     * @param group group to update
     * @return updated group or null, if not found
     * @throws AuthorizationAccessException
     */
    @Override
    public Group updateGroup(Group group) throws AuthorizationAccessException {
        Objects.requireNonNull(group);

        Group existingGroup = getGroup(group.getIdentifier());
        if (existingGroup == null) {
            return null;
        }

        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();

            final String usersUpdate =
                    "with grp_upd as (update ugp_group set identity = ? where identifier = ?), " +
                        "usrs_ins as (insert into ugp_user_group (group_identifier, user_identifier) " +
                        "select ? group_identifier, user_ids.col user_identifier from unnest(?::text[]) user_ids(col)" +
                        "where not exists (select 1 from ugp_user_group uug " +
                        "         where uug.group_identifier = ? and uug.user_identifier = user_ids.col)) " +
                        "delete from ugp_user_group uug  " +
                        "where uug.group_identifier = ? and uug.user_identifier not in " +
                        "(select user_ids.col from unnest(?::text[]) user_ids(col))";

            String[] users = null;
            if (group.getUsers() != null) {
                users = group.getUsers().toArray(new String[0]);
            }
            int rowsUpdated = jdbcTemplate.update(usersUpdate,
                    group.getName(),
                    group.getIdentifier(),
                    group.getIdentifier(),
                    users,
                    group.getIdentifier(),
                    group.getIdentifier(),
                    users);

            groupCacheById.put(group.getIdentifier(), group);
            Set<String> removedUsers = new HashSet<>(existingGroup.getUsers());
            removedUsers.removeAll(group.getUsers());
            Set<String> addedUsers = new HashSet<>(group.getUsers());
            addedUsers.removeAll(existingGroup.getUsers());
            for (String userId : removedUsers) {
                Set<String> groupIds = userGroupIdsCache.get(userId);
                if (groupIds != null) {
                    groupIds.remove(group.getIdentifier());
                }
            }
            for (String userId : addedUsers) {
                Set<String> groupIds = userGroupIdsCache.get(userId);
                if (groupIds != null) {
                    groupIds.add(group.getIdentifier());
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return group;
    }

    /**
     * Deletes group.
     * @param group group to delete
     * @return deleted group
     * @throws AuthorizationAccessException
     */
    @Override
    public Group deleteGroup(Group group) throws AuthorizationAccessException {
        Objects.requireNonNull(group);

        final String sql = "delete from ugp_group where identifier = ?";
        try {
            dbLock.writeLock().lock();
            cacheLock.writeLock().lock();
            final int rowsUpdated = jdbcTemplate.update(sql,
                    group.getIdentifier());
            groupCacheById.remove(group.getIdentifier());
            for (String userId : group.getUsers()) {
                Set<String> groupIds = userGroupIdsCache.get(userId);
                if (groupIds != null) {
                    groupIds.remove(group.getIdentifier());
                }
            }
            if (rowsUpdated == 0) {
                //group does not exist anymore
                return null;
            }
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.writeLock().unlock();
        }

        return group;
    }

    /**
     * Gets all users.
     * @return set of existing users
     * @throws AuthorizationAccessException
     */
    @Override
    public Set<User> getUsers() throws AuthorizationAccessException {
        if (userCacheLoaded) {
            Set<User> users = null;
            try {
                cacheLock.readLock().lock();
                users = new HashSet<>();
                for (Map.Entry<String, User> entry : userCacheById.entrySet()) {
                    users.add(entry.getValue());
                }
            } finally {
                cacheLock.readLock().unlock();
            }
            return users;
        }
        final String usersSQL = "select u.identifier, u.identity from ugp_user u";
        Set<User> usersSet = null;
        try {
            dbLock.readLock().lock();
            cacheLock.writeLock().lock();
            //load data from DB:
            List<User> usersList = jdbcTemplate.query(usersSQL, userRowMapper);
            usersSet = new HashSet<>(usersList);
            //put to cache:
            for (User user : usersList) {
                userCacheById.put(user.getIdentifier(), user);
                userCacheByIdentity.put(user.getIdentity(), user.getIdentifier());
            }
            userCacheLoaded = true;
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.readLock().unlock();
        }
        return usersSet;
    }

    /**
     * Gets user by identifier.
     * @param identifier user id
     * @return user
     * @throws AuthorizationAccessException
     */
    @Override
    public User getUser(String identifier) throws AuthorizationAccessException {
        Validate.notBlank(identifier);

        User user = null;
        try {
            cacheLock.readLock().lock();
            user = userCacheById.get(identifier);
        } finally {
            cacheLock.readLock().unlock();
        }
        if (user == null) {
            try {
                dbLock.readLock().lock();
                cacheLock.writeLock().lock();
                try {
                    //try to load:
                    final String userSql = "select u.identifier, u.identity from ugp_user u where u.identifier = ?";
                    user = jdbcTemplate.queryForObject(userSql,
                            userRowMapper, identifier);
                } catch (EmptyResultDataAccessException ex) {
                    return null;
                }

                if (user == null) {
                    return null;
                }

                userCacheById.put(identifier, user);
                userCacheByIdentity.put(user.getIdentity(), user.getIdentifier());
            } finally {
                cacheLock.writeLock().unlock();
                dbLock.readLock().unlock();
            }
        }
        return user;
    }

    /**
     * Gets user by identity.
     * @param identity identity
     * @return user
     * @throws AuthorizationAccessException
     */
    @Override
    public User getUserByIdentity(String identity) throws AuthorizationAccessException {
        Validate.notBlank(identity);

        User user = null;
        try {
            cacheLock.readLock().lock();
            String userId = userCacheByIdentity.get(identity);
            if (userId != null) {
                user = userCacheById.get(userId);
            }
        } finally {
            cacheLock.readLock().unlock();
        }
        if (user == null) {
            try {
                dbLock.readLock().lock();
                cacheLock.writeLock().lock();
                //try to load:
                final String userSql = "select u.identifier, u.identity from ugp_user u where u.identity = ?";
                try {
                    user = jdbcTemplate.queryForObject(userSql,
                            userRowMapper, identity);
                } catch (EmptyResultDataAccessException ex) {
                    return null;
                }

                if (user == null) {
                    return null;
                }

                userCacheById.put(user.getIdentifier(), user);
                userCacheByIdentity.put(user.getIdentity(), user.getIdentifier());
            } finally {
                cacheLock.writeLock().unlock();
                dbLock.readLock().unlock();
            }
        }
        return user;
    }

    /**
     * Get all user groups.
     * @return set of all user groups
     * @throws AuthorizationAccessException
     */
    @Override
    public Set<Group> getGroups() throws AuthorizationAccessException {
        if (groupCacheLoaded) {
            Set<Group> groups = null;
            try {
                cacheLock.readLock().lock();
                groups = new HashSet<>();
                for (Map.Entry<String, Group> entry : groupCacheById.entrySet()) {
                    groups.add(entry.getValue());
                }
            } finally {
                cacheLock.readLock().unlock();
            }
            return groups;
        }
        final String groupsSql = "select g.identifier, g.identity, " +
                "array_agg(ug.user_identifier) user_ids " +
                "from ugp_group g " +
                "left outer join ugp_user_group ug on ug.group_identifier = g.identifier " +
                "group by g.identifier, g.identity";
        Set<Group> groupSet = null;
        try {
            dbLock.readLock().lock();
            cacheLock.writeLock().lock();
            //load data from DB:
            final List<Group> groupList = jdbcTemplate.query(groupsSql, groupRowMapper);
            groupSet = new HashSet<>(groupList);
            //put to cache:
            for (Group group : groupList) {
                groupCacheById.put(group.getIdentifier(), group);
            }
            groupCacheLoaded = true;
        } finally {
            cacheLock.writeLock().unlock();
            dbLock.readLock().unlock();
        }

        return groupSet;
    }

    /**
     * Get group by identifier.
     * @param identifier id of the group
     * @return group or null
     * @throws AuthorizationAccessException
     */
    @Override
    public Group getGroup(String identifier) throws AuthorizationAccessException {
        Validate.notBlank(identifier);

        Group group = null;
        try {
            cacheLock.readLock().lock();
            group = groupCacheById.get(identifier);
        } finally {
            cacheLock.readLock().unlock();
        }
        if (group == null) {
            if (groupCacheLoaded) {
                //if full cache loaded, then no need to lookup again:
                return null;
            }
            try {
                dbLock.readLock().lock();
                cacheLock.writeLock().lock();

                //try to load:
                final String groupSql = "select g.identifier, g.identity, " +
                        "array_agg(ug.user_identifier) user_ids " +
                        "from ugp_group g " +
                        "left outer join ugp_user_group ug on ug.group_identifier = g.identifier " +
                        "where g.identifier = ? " +
                        "group by g.identifier, g.identity";
                try {
                    group = jdbcTemplate.queryForObject(groupSql,
                            groupRowMapper, identifier);
                } catch (EmptyResultDataAccessException ex) {
                    return null;
                }

                if (group == null) {
                    return null;
                }

                groupCacheById.put(identifier, group);
            } finally {
                cacheLock.writeLock().unlock();
                dbLock.readLock().unlock();
            }
        }
        return group;
    }

    /**
     * Gets user and groups it belongs to.
     * @param userIdentity identity of the user
     * @return user and groups or null
     * @throws AuthorizationAccessException
     */
    @Override
    public UserAndGroups getUserAndGroups(String userIdentity) throws AuthorizationAccessException {
        Validate.notBlank(userIdentity);

        User user = getUserByIdentity(userIdentity);
        Set<Group> groups = null;
        //if user exists, get related groups:
        if (user != null) {
            Set<String> groupIds = null;
            try {
                cacheLock.readLock().lock();
                groupIds = userGroupIdsCache.get(user.getIdentifier());
            } finally {
                cacheLock.readLock().unlock();
            }
            if (groupIds != null) {
                //found, look up groups one-by-one, likely from cache:
                groups = new HashSet<>();
                for (String groupId : groupIds) {
                    Group group = getGroup(groupId);
                    if (group != null) {
                        groups.add(group);
                    }
                }
            }
            if (groups == null) {
                //not found in cache, load from DB:
                try {
                    dbLock.readLock().lock();
                    cacheLock.writeLock().lock();
                    final String groupsSql = "select g.identifier, g.identity, " +
                            "array_agg(ug.user_identifier) user_ids " +
                            "from ugp_user_group ug1 " +
                            "inner join ugp_group g on g.identifier = ug1.group_identifier " +
                            "left outer join ugp_user_group ug on ug.group_identifier = g.identifier " +
                            "where ug1.user_identifier = ? " +
                            "group by g.identifier, g.identity";
                    //load data from DB:
                    final List<Group> groupList = jdbcTemplate.query(groupsSql, groupRowMapper, user.getIdentifier());
                    groups = new HashSet<>(groupList);
                    groupIds = new HashSet<>();
                    for (Group group : groups) {
                        groupIds.add(group.getIdentifier());
                    }
                    userGroupIdsCache.put(user.getIdentifier(), groupIds);
                } finally {
                    cacheLock.writeLock().unlock();
                    dbLock.readLock().unlock();
                }
            }
        }
        return new UserAndGroupsImpl(user, groups);
    }

    /**
     * Initializes provider.
     * @param context init context
     * @throws SecurityProviderCreationException
     */
    @Override
    public void initialize(UserGroupProviderInitializationContext context)
            throws SecurityProviderCreationException {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private Set<String> getInitialUserIdentities(final AuthorizerConfigurationContext configurationContext) {
        final Set<String> initialUserIdentities = new HashSet<>();
        for (Map.Entry<String, String> entry : configurationContext.getProperties().entrySet()) {
            Matcher matcher = INITIAL_USER_IDENTITY_PATTERN.matcher(entry.getKey());
            if (matcher.matches() && !StringUtils.isBlank(entry.getValue())) {
                initialUserIdentities.add(entry.getValue());
            }
        }
        return initialUserIdentities;
    }

    /**
     * Initial provider configuration.
     * @param context configuration context
     * @throws SecurityProviderCreationException
     */
    @Override
    public void onConfigured(AuthorizerConfigurationContext context)
            throws SecurityProviderCreationException {
        Set<String> initialUserIdentities = getInitialUserIdentities(context);
        for (String userIdentity : initialUserIdentities) {
            User user = getUserByIdentity(userIdentity);
            if (user == null) {
                //create user
                user = new User.Builder().
                        identifierGenerateFromSeed(userIdentity).
                        identity(userIdentity).
                        build();
                addUser(user);
                LOGGER.info("Created initial user with identity = {} and identifier = {}.",
                        userIdentity, user.getIdentifier());
            } else {
                LOGGER.debug("Initial user with identity = {} already exists.", userIdentity);
            }
        }
    }

    @Override
    public void preDestruction() throws SecurityProviderDestructionException {

    }
}
