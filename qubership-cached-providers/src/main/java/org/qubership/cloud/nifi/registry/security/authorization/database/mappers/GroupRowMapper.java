package org.qubership.cloud.nifi.registry.security.authorization.database.mappers;

import org.apache.nifi.registry.security.authorization.Group;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

public class GroupRowMapper implements RowMapper<Group> {

    /**
     * Maps result set row to user group.
     *
     * @param rs result set
     * @param i  index
     * @return user group
     * @throws SQLException
     */
    @Override
    public Group mapRow(final ResultSet rs, final int i) throws SQLException {
        Array userIds = rs.getArray("USER_IDS");
        Set<String> userIdentifiers = MapperUtils.convertArrayToSet(userIds);
        return new Group.Builder()
                .identifier(rs.getString("IDENTIFIER"))
                .name(rs.getString("IDENTITY"))
                .addUsers(userIdentifiers)
                .build();
    }
}
