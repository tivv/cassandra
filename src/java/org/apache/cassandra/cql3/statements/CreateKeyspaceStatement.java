/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.db.migration.AddKeyspace;
import org.apache.cassandra.db.migration.Migration;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KsDef;
import org.apache.cassandra.thrift.SchemaDisagreementException;
import org.apache.cassandra.thrift.ThriftValidation;

/** A <code>CREATE KEYSPACE</code> statement parsed from a CQL query. */
public class CreateKeyspaceStatement extends SchemaAlteringStatement
{
    private final String name;
    private final Map<String, String> attrs;
    private String strategyClass;
    private Map<String, String> strategyOptions = new HashMap<String, String>();

    /**
     * Creates a new <code>CreateKeyspaceStatement</code> instance for a given
     * keyspace name and keyword arguments.
     *
     * @param name the name of the keyspace to create
     * @param attrs map of the raw keyword arguments that followed the <code>WITH</code> keyword.
     */
    public CreateKeyspaceStatement(String name, Map<String, String> attrs)
    {
        super();
        this.name = name;
        this.attrs = attrs;
    }

    /**
     * The <code>CqlParser</code> only goes as far as extracting the keyword arguments
     * from these statements, so this method is responsible for processing and
     * validating.
     *
     * @throws InvalidRequestException if arguments are missing or unacceptable
     */
    @Override
    public void validate(ClientState state) throws InvalidRequestException, SchemaDisagreementException
    {
        super.validate(state);
        ThriftValidation.validateKeyspaceNotSystem(name);

        // keyspace name
        if (!name.matches("\\w+"))
            throw new InvalidRequestException(String.format("\"%s\" is not a valid keyspace name", name));
        if (name.length() > 32)
            throw new InvalidRequestException(String.format("Keyspace names shouldn't be more than 32 character long (got \"%s\")", name));

        // required
        if (!attrs.containsKey("strategy_class"))
            throw new InvalidRequestException("missing required argument \"strategy_class\"");
        strategyClass = attrs.get("strategy_class");

        // optional
        for (String key : attrs.keySet())
            if ((key.contains(":")) && (key.startsWith("strategy_options")))
                strategyOptions.put(key.split(":")[1], attrs.get(key));

        // trial run to let ARS validate class + per-class options
        try
        {
            AbstractReplicationStrategy.createReplicationStrategy(name,
                                                                  AbstractReplicationStrategy.getClass(strategyClass),
                                                                  StorageService.instance.getTokenMetadata(),
                                                                  DatabaseDescriptor.getEndpointSnitch(),
                                                                  strategyOptions);
        }
        catch (ConfigurationException e)
        {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    public Migration getMigration() throws InvalidRequestException, ConfigurationException, IOException
    {
        KsDef ksd = new KsDef(name, strategyClass, Collections.<CfDef>emptyList());
        ksd.setStrategy_options(strategyOptions);
        ThriftValidation.validateKsDef(ksd);
        ThriftValidation.validateKeyspaceNotYetExisting(name);
        return new AddKeyspace(KSMetaData.fromThrift(ksd));
    }
}
