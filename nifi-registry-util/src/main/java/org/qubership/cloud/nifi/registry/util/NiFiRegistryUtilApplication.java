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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
public class NiFiRegistryUtilApplication implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(NiFiRegistryUtilApplication.class);

	@Autowired
	private DbManager dbManager;

	public static void main(String[] args) {
		SpringApplication.run(NiFiRegistryUtilApplication.class, args);
	}

	@Override
	public void run(String... args) {
		String result = dbManager.runSqlStatement(args[0],args[1],args[2],Boolean.parseBoolean(args[3]));
		if("".equals(result))
			log.info("Schema creation and migration successful.");
		else
			log.error(result);
	}
}
