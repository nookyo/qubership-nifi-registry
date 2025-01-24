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
import java.sql.*;
import java.util.Arrays;
import java.util.Objects;

@Component
public class DbManager {
    private static final int LOGIN_TIMEOUT = 10;
    private static final Logger log = LoggerFactory.getLogger(DbManager.class);

    public Connection createConnection(String url, String username, String password) throws ClassNotFoundException, SQLException {
        String driver = "org.postgresql.Driver";
        log.info("URL: {}", url);
        log.info("Username: {}", username);
        if(!password.isEmpty())
            log.info("Password: XXXXX");
        log.info("Driver: {}", driver);
        log.info("Set Login Timeout: {}", LOGIN_TIMEOUT);
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT);
        Class.forName(driver);
        Connection connection = DriverManager.getConnection(url, username, password);
        log.info("Connection: {}", connection);
        return connection;
    }

    public String runSqlStatement(String url, String username, String password, boolean migrateToDB) throws RuntimeException {
        String result = "";
        String createSchema = "CREATE SCHEMA IF NOT EXISTS nifi_registry";
        String migFlowPersProvTable = "CREATE TABLE IF NOT EXISTS MIG_FLOW_PERSISTENCE_PROVIDER (\n" +
                "    BUCKET_ID VARCHAR(50) NOT NULL,\n" +
                "    FLOW_ID VARCHAR(50) NOT NULL,\n" +
                "    VERSION INT NOT NULL,\n" +
                "    FLOW_CONTENT BYTEA NOT NULL\n" +
                ");\n";
        String migFlowPersProvStatusTable = "CREATE TABLE IF NOT EXISTS MIG_FLOW_PERSISTENCE_PROVIDER_STATUS (\n" +
                "    MIGRATION_TS timestamptz not null,\n" +
                "    STATUS INT NOT NULL\n" +
                ");\n";
        try(Connection con = createConnection(url,username,password);
            Statement statement = con.createStatement()) {
            log.info("SQL query for schema: {}", createSchema);
            statement.execute(createSchema);
            log.info("SQL query for flow: {}", migFlowPersProvTable);
            statement.execute(migFlowPersProvTable);
            log.info("SQL query for flow status: {}", migFlowPersProvStatusTable);
            statement.execute(migFlowPersProvStatusTable);
            if (migrateToDB && !checkIfMigrationRanBefore(statement))
                readFlowStorageAndPopulateDB(statement);
            else
                log.info("No migration needed OR migration is found to be already completed. No action needed henceforth.");
        } catch (ClassNotFoundException ex) {
            log.error("Failed to find JDBC driver", ex);
            result = ex.getMessage();
        } catch (SQLException ex) {
            log.error("Failed to create schema for NiFi Registry", ex);
            result = ex.getMessage();
        }
        return result;
    }

    public boolean checkIfMigrationRanBefore(Statement statement) throws SQLException {
        log.info("Checking if the migration was run before...");

        int count;
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS recordCount from mig_flow_persistence_provider_status where status = 1")) {
            rs.next();
            count = rs.getInt("recordCount");
        }

        return count != 0;
    }

    public void readFlowStorageAndPopulateDB(Statement statement) throws SQLException {
        log.info("Reading flow_storage directory and writing to database...");
        File directoryPath = new File(System.getenv("NIFI_REGISTRY_HOME")+ "/persistent_data/flow_storage/");
        if ( directoryPath.exists() ) {
            Arrays.stream(Objects.requireNonNull(directoryPath.listFiles())).forEach(bucket -> {
                String bucketId = bucket.getName();
                Arrays.stream(Objects.requireNonNull(new File(String.valueOf(bucket)).listFiles())).forEach(flow -> {
                    String flowId = flow.getName();
                    Arrays.stream(Objects.requireNonNull(new File(String.valueOf(flow)).listFiles())).forEach(version -> {
                        int ver = Integer.parseInt(version.getName());
                        Arrays.stream(Objects.requireNonNull(new File(String.valueOf(version)).listFiles())).forEach(content -> {
                            try (PreparedStatement prepStmt = statement.getConnection().prepareStatement("insert into mig_flow_persistence_provider(bucket_id, flow_id, version, flow_content) values (?,?,?,?)")) {
                                prepStmt.setString(1, bucketId);
                                prepStmt.setString(2, flowId);
                                prepStmt.setInt(3, ver);
                                prepStmt.setBytes(4, FileUtils.readFileToByteArray(content));
                                prepStmt.execute();
                            } catch (SQLException | IOException ex) {
                                log.error("Unexpected exception occurred.", ex);
                            }
                        });
                    });
                });
            });
            updateStatusOfMigration(statement);
        }
        else{
            log.warn("Dir " + System.getenv("NIFI_REGISTRY_HOME") + "/persistent_data/flow_storage not found. Skipping...");
        }
    }

    public void updateStatusOfMigration(Statement statement) throws SQLException {
        log.info("Updating status of migration...");
        statement.execute("insert into mig_flow_persistence_provider_status values(current_timestamp, 1)");
    }
}

