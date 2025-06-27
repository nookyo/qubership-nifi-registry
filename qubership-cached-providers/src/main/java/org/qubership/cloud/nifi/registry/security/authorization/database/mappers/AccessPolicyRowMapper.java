package org.qubership.cloud.nifi.registry.security.authorization.database.mappers;

import org.apache.nifi.registry.security.authorization.AccessPolicy;
import org.apache.nifi.registry.security.authorization.RequestAction;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

public class AccessPolicyRowMapper implements RowMapper<AccessPolicy> {

    /**
     * Maps result set row to access policy.
     *
     * @param rs result set
     * @param i  index
     * @return access policy
     * @throws SQLException
     */
    @Override
    public AccessPolicy mapRow(final ResultSet rs, final int i) throws SQLException {
        Array userIds = rs.getArray("USER_IDS");
        Set<String> userIdentifiers = MapperUtils.convertArrayToSet(userIds);
        Array groupIds = rs.getArray("GROUP_IDS");
        Set<String> groupIdentifiers = MapperUtils.convertArrayToSet(groupIds);
        return new AccessPolicy.Builder()
                .identifier(rs.getString("IDENTIFIER"))
                .resource(rs.getString("RESOURCE"))
                .action(RequestAction.valueOfValue(rs.getString("ACTION")))
                .addUsers(userIdentifiers)
                .addGroups(groupIdentifiers)
                .build();
    }
}
