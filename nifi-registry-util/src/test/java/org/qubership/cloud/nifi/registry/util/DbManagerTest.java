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

import org.junit.jupiter.api.AfterAll;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.io.File;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("DockerBased")
@Testcontainers
@ExtendWith(SystemStubsExtension.class)
@Slf4j
public class DbManagerTest {

    private static final String POSTGRES_IMAGE = "postgres:16.8";

    private static final String DB_NAME = "testDb";
    private static final String USER = "postgres";
    private static final String PWD = "password";
    private static String dbUrl;
    private static DbManager dbManager;

    @Container
    private static JdbcDatabaseContainer postgresContainer;

    @SystemStub
    private EnvironmentVariables environmentVariables;

    static {
        postgresContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(DB_NAME)
                .withUsername(USER)
                .withPassword(PWD);
        postgresContainer.start();
        dbManager = new DbManager();
    }

    @BeforeEach
    public void setUp() {
        dbUrl = "jdbc:postgresql://" + postgresContainer.getContainerIpAddress() +
                ":" + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) +
                "/" + DB_NAME;
    }

    @Test
    public void errorCreationDatabaseTest() throws SQLException, ClassNotFoundException {
        if (postgresContainer.isRunning()) {
            postgresContainer.stop();
            String runSqlStatementResult = dbManager.runSqlStatement(dbUrl, USER, PWD, true);
            assertFalse(runSqlStatementResult.isEmpty());
            postgresContainer.start();
        }
    }

    @Test
    public void successCreationDatabaseTest() throws SQLException, ClassNotFoundException {
        String runSqlStatementResult = dbManager.runSqlStatement(dbUrl, USER, PWD, true);
        assertTrue(runSqlStatementResult.isEmpty());
    }

    @Test
    public void migrToDBNoFile() {
        String runSqlStatementResult = dbManager.runSqlStatement(dbUrl, USER, PWD, true);
        assertTrue(runSqlStatementResult.isEmpty());
    }

    @Test
    public void successMigrToDB() {
        environmentVariables.set("NIFI_REGISTRY_HOME", "src/test/resources/success");
        String runSqlStatementResult = dbManager.runSqlStatement(dbUrl, USER, PWD, true);
        assertTrue(runSqlStatementResult.isEmpty());
        checkDataInDb("9f36ac47-a6d4-4a9a-a824-dc4db141e8b5",
                "d7573974-9305-418b-b1bb-7774d9a9195a", 1, 1);
    }

    //@Test
    //TODO uncomment after fixing migration to database
    public void errorMigrToDB() {
        environmentVariables.set("NIFI_REGISTRY_HOME", "src/test/resources/error");
        String dirName = "9f36ac47-a6d4-4a9a-a824-dc4db141e8b5-9f36ac47-a6d4-4a9a-a824";
        String fixDirName = "40f9e387-890b-4916-9100-77048472a5c8";
        int migrStatus = 0;
        String runSqlStatementResult = dbManager.runSqlStatement(dbUrl, USER, PWD, true);
        assertFalse(runSqlStatementResult.isEmpty());
        //check that status not set
        try (Connection con = dbManager.createConnection(dbUrl, USER, PWD);
             Statement statement = con.createStatement()) {
            try (ResultSet statusInfo = statement.executeQuery("select * " +
                    "from MIG_FLOW_PERSISTENCE_PROVIDER_STATUS")) {
                if (statusInfo.next()) {
                    migrStatus = statusInfo.getInt(2);
                    if (statusInfo.next()) {
                        fail("Status row is duplicated!");
                    }
                } else {
                    fail("Status row was not found!");
                }
                assertEquals(0, migrStatus);
            } catch (SQLException ex) {
                log.error("Failed to get status from MIG_FLOW_PERSISTENCE_PROVIDER_STATUS table", ex);
                fail("Failed to get status from MIG_FLOW_PERSISTENCE_PROVIDER_STATUS table" + ex.getMessage());
            }
        } catch (ClassNotFoundException ex) {
            log.error("Failed to find JDBC driver", ex);
            fail("Failed to find JDBC driver" + ex.getMessage());
        } catch (SQLException ex) {
            log.error("Failed to get data from DB", ex);
            fail("Failed to get data from DB" + ex.getMessage());
        }

        File dir = new File("src/test/resources/error/persistent_data/flow_storage/" + dirName);
        File newDir = new File(dir.getParent() + "\\" + fixDirName);
        dir.renameTo(newDir);
        runSqlStatementResult = dbManager.runSqlStatement(dbUrl, USER, PWD, true);
        assertTrue(runSqlStatementResult.isEmpty());
        checkDataInDb("40f9e387-890b-4916-9100-77048472a5c8",
                "d7573974-9305-418b-b1bb-7774d9a9195a", 1, 1);

        File dir2 = new File("src/test/resources/error/persistent_data/flow_storage/" + fixDirName);
        File newDir2 = new File(dir2.getParent() + "\\" + dirName);
        dir2.renameTo(newDir2);
    }

    private void checkDataInDb(String expBucketId, String expFlowId, int expRowNumber, int expMigrStatus) {
        String bucketIdDb = "";
        String flowtIdDb = "";
        int rowNumber = 0;
        int migrStatus = 0;
        try (Connection con = dbManager.createConnection(dbUrl, USER, PWD);
             Statement statement = con.createStatement()) {
            try (
                    PreparedStatement preparedStatement = statement.getConnection().prepareStatement(
                            "select bucket_id, flow_id from MIG_FLOW_PERSISTENCE_PROVIDER" +
                                    " where bucket_id = ? and flow_id = ?");
            ) {
                preparedStatement.setString(1, expBucketId);
                preparedStatement.setString(2, expFlowId);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        bucketIdDb = resultSet.getString("bucket_id");
                        flowtIdDb = resultSet.getString("flow_id");
                    }
                    assertEquals(expBucketId, bucketIdDb);
                    assertEquals(expFlowId, flowtIdDb);
                } catch (SQLException ex) {
                    log.error("Failed to get data from MIG_FLOW_PERSISTENCE_PROVIDER", ex);
                    fail("Failed to get data from MIG_FLOW_PERSISTENCE_PROVIDER" + ex.getMessage());
                }
            } catch (SQLException ex) {
                log.error("Unexpected exception occurred.", ex);
                fail("Failed to get data from MIG_FLOW_PERSISTENCE_PROVIDER" + ex.getMessage());
            }
            try (ResultSet rowNum = statement.executeQuery("select count(*) as number " +
                    "from MIG_FLOW_PERSISTENCE_PROVIDER")) {
                if (rowNum.next()) {
                    rowNumber = rowNum.getInt("number");
                    assertEquals(expRowNumber, rowNumber);
                } else {
                    fail("No records found in table!");
                }
            } catch (SQLException ex) {
                log.error("Failed to get number of records from MIG_FLOW_PERSISTENCE_PROVIDER", ex);
                fail("Failed to get number of records from MIG_FLOW_PERSISTENCE_PROVIDER" + ex.getMessage());
            }
            try (ResultSet statusInfo = statement.executeQuery("select * " +
                    "from MIG_FLOW_PERSISTENCE_PROVIDER_STATUS")) {
                if (statusInfo.next()) {
                    migrStatus = statusInfo.getInt(2);
                    if (statusInfo.next()) {
                        fail("Status row is duplicated!");
                    }
                } else {
                    fail("Status row was not found!");
                }
                assertEquals(expMigrStatus, migrStatus);
            } catch (SQLException ex) {
                log.error("Failed to get status from MIG_FLOW_PERSISTENCE_PROVIDER_STATUS", ex);
                fail("Failed to get status from MIG_FLOW_PERSISTENCE_PROVIDER_STATUS" + ex.getMessage());
            }
        } catch (ClassNotFoundException ex) {
            log.error("Failed to find JDBC driver", ex);
            fail("Failed to find JDBC driver" + ex.getMessage());
        } catch (SQLException ex) {
            //fix message
            log.error("Failed to get data from DB", ex);
            fail("Failed to get data from DB" + ex.getMessage());
        }
    }

    @AfterAll
    public static void tearDown() {
        postgresContainer.stop();
    }
}
