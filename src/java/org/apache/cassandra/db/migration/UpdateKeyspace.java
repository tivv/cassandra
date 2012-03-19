/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.migration;

import java.io.IOException;

import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.thrift.KsDef;

public class UpdateKeyspace extends Migration
{
    private final KsDef newState;

    public UpdateKeyspace(KsDef newState) throws ConfigurationException
    {
        super(System.nanoTime());

        if (newState.isSetCf_defs() && newState.getCf_defs().size() > 0)
            throw new ConfigurationException("Updated keyspace must not contain any column families.");

        if (Schema.instance.getKSMetaData(newState.name) == null)
            throw new ConfigurationException(newState.name + " cannot be updated because it doesn't exist.");

        this.newState = newState;
    }

    protected void applyImpl() throws ConfigurationException, IOException
    {
        MigrationHelper.updateKeyspace(newState, timestamp);

        logger.info("Keyspace updated. Please perform any manual operations.");
    }

    @Override
    public String toString()
    {
        return String.format("Update keyspace %s with %s", newState.name, newState);
    }
}
