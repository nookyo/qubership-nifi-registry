/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.cloud.nifi.registry.util;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.ResultSet;

import java.util.Arrays;
import java.util.Objects;

@Component
public class DbManager {
    private static final int LOGIN_TIMEOUT = 10;
    private static final Logger LOG = LoggerFactory.getLogger(DbManager.class);

    /**
     * Creates connection to DB.
     * @param url URL to connect to DB with
     * @param username user name
     * @param password password
     * @return DB connection
     * @throws ClassNotFoundException
     * @throws SQLException
     */
    public Connection createConnection(String url, String username, String password)
            throws ClassNotFoundException, SQLException {
        String driver = "org.postgresql.Driver";
        LOG.info("URL: {}", url);
        LOG.info("Username: {}", username);
        if (!password.isEmpty()) {
            LOG.info("Password: XXXXX");
        }
        LOG.info("Driver: {}", driver);
        LOG.info("Set Login Timeout: {}", LOGIN_TIMEOUT);
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT);
        Class.forName(driver);
        Connection connection = DriverManager.getConnection(url, username, password);
        LOG.info("Connection: {}", connection);
        return connection;
    }

    /**
     * Runs SQL statement.
     * @param url DB connection URL
     * @param username user name
     * @param password password
     * @param migrateToDB true, if data migration should be executed for this DB
     * @return empty string, if success, or error message if error occurred.
     * @throws RuntimeException
     */
    public String runSqlStatement(String url, String username, String password, boolean migrateToDB)
            throws RuntimeException {
        String result = "";
        String createSchema = "CREATE SCHEMA IF NOT EXISTS nifi_registry";
        String migFlowPersistProvTable = "CREATE TABLE IF NOT EXISTS MIG_FLOW_PERSISTENCE_PROVIDER (\n" +
                "    BUCKET_ID VARCHAR(50) NOT NULL,\n" +
                "    FLOW_ID VARCHAR(50) NOT NULL,\n" +
                "    VERSION INT NOT NULL,\n" +
                "    FLOW_CONTENT BYTEA NOT NULL\n" +
                ");\n";
        String migFlowPersistProvStatusTable = "CREATE TABLE IF NOT EXISTS MIG_FLOW_PERSISTENCE_PROVIDER_STATUS (\n" +
                "    MIGRATION_TS timestamptz not null,\n" +
                "    STATUS INT NOT NULL\n" +
                ");\n";
        try (Connection con = createConnection(url, username, password);
             Statement statement = con.createStatement()) {
            LOG.info("SQL query for schema: {}", createSchema);
            statement.execute(createSchema);
            LOG.info("SQL query for flow: {}", migFlowPersistProvTable);
            statement.execute(migFlowPersistProvTable);
            LOG.info("SQL query for flow status: {}", migFlowPersistProvStatusTable);
            statement.execute(migFlowPersistProvStatusTable);
            if (migrateToDB && !checkIfMigrationRanBefore(statement)) {
                readFlowStorageAndPopulateDB(statement);
            } else {
                LOG.info("No migration needed OR migration is found to be already completed. " +
                        "No action needed henceforth.");
            }
        } catch (ClassNotFoundException ex) {
            LOG.error("Failed to find JDBC driver", ex);
            result = ex.getMessage();
        } catch (SQLException ex) {
            LOG.error("Failed to create schema for NiFi Registry", ex);
            result = ex.getMessage();
        }
        return result;
    }

    /**
     * Checks if migration was executed previously.
     * @param statement SQL statement to use
     * @return true, if migration already applied. False, otherwise.
     * @throws SQLException
     */
    public boolean checkIfMigrationRanBefore(Statement statement) throws SQLException {
        LOG.info("Checking if the migration was run before...");

        int count;
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) AS recordCount " +
                "from mig_flow_persistence_provider_status " +
                "where status = 1")) {
            rs.next();
            count = rs.getInt("recordCount");
        }

        return count != 0;
    }

    /**
     * Reads NiFi Registry flow storage from disk and fills DB with data from it.
     * @param statement SQL statement
     * @throws SQLException
     */
    public void readFlowStorageAndPopulateDB(Statement statement) throws SQLException {
        LOG.info("Reading flow_storage directory and writing to database...");
        File directoryPath = new File(System.getenv("NIFI_REGISTRY_HOME") + "/persistent_data/flow_storage/");
        if (directoryPath.exists()) {
            Arrays.stream(Objects.requireNonNull(directoryPath.listFiles())).forEach(bucket -> {
                String bucketId = bucket.getName();
                Arrays.stream(Objects.requireNonNull(new File(String.valueOf(bucket)).listFiles())).forEach(flow -> {
                    String flowId = flow.getName();
                    Arrays.stream(Objects.requireNonNull(new File(String.valueOf(flow)).listFiles())).
                            forEach(version -> {
                                int ver = Integer.parseInt(version.getName());
                                Arrays.stream(Objects.requireNonNull(new File(String.valueOf(version)).listFiles())).
                                        forEach(content -> {
                                            try (PreparedStatement prepStmt = statement.getConnection().
                                                    prepareStatement(
                                                    "insert into mig_flow_persistence_provider" +
                                                            "(bucket_id, flow_id, version, flow_content) " +
                                                        "values (?,?,?,?)")) {
                                                final int versionIndex = 3;
                                                final int contentIndex = 4;
                                                prepStmt.setString(1, bucketId);
                                                prepStmt.setString(2, flowId);
                                                prepStmt.setInt(versionIndex, ver);
                                                prepStmt.setBytes(contentIndex,
                                                        FileUtils.readFileToByteArray(content));
                                                prepStmt.execute();
                                            } catch (SQLException | IOException ex) {
                                                LOG.error("Unexpected exception occurred.", ex);
                                            }
                                        });
                            });
                });
            });
            updateStatusOfMigration(statement);
        } else {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Dir {}/persistent_data/flow_storage not found. Skipping...",
                        System.getenv("NIFI_REGISTRY_HOME"));
            }
        }
    }

    /**
     * Updates migration status.
     * @param statement SQL statement
     * @throws SQLException
     */
    public void updateStatusOfMigration(Statement statement) throws SQLException {
        LOG.info("Updating status of migration...");
        statement.execute("insert into mig_flow_persistence_provider_status values(current_timestamp, 1)");
    }
}

