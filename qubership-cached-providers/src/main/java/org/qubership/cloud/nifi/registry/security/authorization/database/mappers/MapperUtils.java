package org.qubership.cloud.nifi.registry.security.authorization.database.mappers;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class MapperUtils {
    private MapperUtils() {
    }

    /**
     * Converts SQL Array to Set of strings.
     * @param arr SQL array
     * @return set of strings
     * @throws SQLException
     */
    public static Set<String> convertArrayToSet(Array arr) throws SQLException {
        if (arr == null) {
            return Collections.emptySet();
        }
        String[] stringArray = (String[]) arr.getArray();
        Set<String> ids = new HashSet<>();
        if (stringArray != null) {
            for (String stringId : stringArray) {
                if (stringId != null) {
                    ids.add(stringId);
                }
            }
        }
        return ids;
    }
}
