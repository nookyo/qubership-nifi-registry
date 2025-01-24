-- Copyright 2020-2025 NetCracker Technology Corporation
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

--migrate data only, if it wasn't migrated before (flyway is supposed to run this migration only once):
insert into nifi_registry.FLOW_PERSISTENCE_PROVIDER (bucket_id, flow_id, version, flow_content)
select bucket_id, flow_id, version, flow_content from public.MIG_FLOW_PERSISTENCE_PROVIDER;

commit;
--after copying data truncate MIG_FLOW_PERSISTENCE_PROVIDER
truncate table public.MIG_FLOW_PERSISTENCE_PROVIDER;
commit;
