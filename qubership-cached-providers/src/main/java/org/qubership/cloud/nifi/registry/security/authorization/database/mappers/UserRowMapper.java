package org.qubership.cloud.nifi.registry.security.authorization.database.mappers;

import org.apache.nifi.registry.security.authorization.User;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class UserRowMapper implements RowMapper<User> {

    /**
     * Maps result set row to user.
     *
     * @param rs result set
     * @param i  index
     * @return user
     * @throws SQLException
     */
    @Override
    public User mapRow(final ResultSet rs, final int i) throws SQLException {
        return new User.Builder()
                .identifier(rs.getString("IDENTIFIER"))
                .identity(rs.getString("IDENTITY"))
                .build();
    }
}
